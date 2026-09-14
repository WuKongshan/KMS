package com.kms.collector.service;

import com.kms.collector.model.CollectedMetrics;
import com.kms.collector.state.CollectorState;
import com.kms.database.entity.CollectorConfig;
import com.kms.database.mapper.CollectorConfigMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The collection loop. Reads {@code t_collector_config} every cycle so period
 * changes and enable/disable apply without restart (spec 4.2). While disabled
 * it polls every {@link #DISABLED_POLL_SECONDS}s; config changes made through
 * the REST API wake the loop immediately. Starts after the schema initializer.
 *
 * <p>【中文】采集循环。每个周期都重新读取 {@code t_collector_config}，因此修改周期
 * 与启停无需重启即可生效（规格 4.2）；停用期间按 {@link #DISABLED_POLL_SECONDS} 秒
 * 轮询配置；通过 REST API 修改配置会立即唤醒循环。在表结构初始化之后启动
 * （{@code @Order(2)} 晚于 {@code SchemaInitializer} 的 {@code @Order(1)}）。
 *
 * <p>【中文】这里刻意手写守护线程而不用 {@code @Scheduled}：{@code @Scheduled} 的
 * 表达式只在容器启动时解析一次，直接改数据库不会被重新读取，无法满足“改配置无需
 * 重启即生效”的要求。
 */
@Component
@Order(2)
public class CollectionLoop implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectionLoop.class);

    // t_collector_config 是单行配置表，主键固定为 1
    private static final int CONFIG_ID = 1;
    // 配置行缺失或周期字段为空时使用的默认周期（秒）
    private static final int DEFAULT_PERIOD_SECONDS = 60;
    // 规格要求的最小采集周期（秒），运行期同样用它做兜底
    private static final int MIN_PERIOD_SECONDS = 60;
    // 停用期间的轮询间隔（秒）：只查配置不采集，间隔取小值以便尽快感知“重新启用”
    private static final int DISABLED_POLL_SECONDS = 10;

    // wait/notifyAll 的锁对象：Thread.sleep 无法被外部唤醒，用对象锁实现“可被提前叫醒的睡眠”
    private final Object lock = new Object();
    // 停机标志：@PreDestroy 置为 false 并唤醒线程，volatile 保证另一线程立即可见
    private volatile boolean running = true;
    // 持有采集线程引用，便于停机时 interrupt
    private Thread loopThread;

    private final CollectorConfigMapper collectorConfigMapper;
    private final MetricCollectorService metricCollectorService;
    private final TdengineMetricWriter tdengineMetricWriter;
    private final MetricLatestUpdater metricLatestUpdater;
    private final CollectorState state;

    // 依赖注入：Mapper 每轮重读配置；三个组件分别负责 TDengine 写入、达梦当前值更新与内存状态
    public CollectionLoop(CollectorConfigMapper collectorConfigMapper,
                          MetricCollectorService metricCollectorService,
                          TdengineMetricWriter tdengineMetricWriter,
                          MetricLatestUpdater metricLatestUpdater,
                          CollectorState state) {
        this.collectorConfigMapper = collectorConfigMapper;
        this.metricCollectorService = metricCollectorService;
        this.tdengineMetricWriter = tdengineMetricWriter;
        this.metricLatestUpdater = metricLatestUpdater;
        this.state = state;
    }

    // ApplicationRunner 回调：应用启动后拉起采集线程就返回，不阻塞 Spring 启动流程
    @Override
    public void run(ApplicationArguments args) {
        // 线程命名为 kms-collect-loop，便于日志与 jstack 快速定位
        loopThread = new Thread(this::loop, "kms-collect-loop");
        // 守护线程：JVM 退出时不阻塞（正规停机仍由 @PreDestroy 负责）
        loopThread.setDaemon(true);
        loopThread.start();
        log.info("Collection loop started (default period {} s)", DEFAULT_PERIOD_SECONDS);
    }

    /**
     * Wake the loop so a config change (period/enable) applies immediately.
     *
     * <p>【中文】唤醒采集循环，使周期/启停变更立即生效；由 REST 接口在改完配置后调用。
     */
    public void wakeUp() {
        synchronized (lock) {
            // 唤醒正在 wait 的采集线程，让它立刻重新读配置而不是睡满旧周期
            lock.notifyAll();
        }
    }

    // 采集主循环：读配置 → 按需采集 → 可被唤醒的睡眠，如此往复
    private void loop() {
        while (running) {
            long waitSeconds;
            try {
                // 每轮都重新 selectById 读配置，这是“改配置无需重启即生效”的关键
                CollectorConfig config = collectorConfigMapper.selectById(CONFIG_ID);
                // 运行期兜底：即使有人直接改数据库把周期设成 1 秒，也至少按 60 秒执行
                long period = (config != null && config.getPeriodSeconds() != null)
                        ? Math.max(config.getPeriodSeconds(), MIN_PERIOD_SECONDS)
                        : DEFAULT_PERIOD_SECONDS;
                // 达梦用 INT 存布尔：1 表示启用，null 或其它值都视为停用
                boolean enabled = config != null && Integer.valueOf(1).equals(config.getEnabled());

                // 同步到状态对象，REST 查询能立刻看到实际生效的周期与启停状态
                state.setPeriodSeconds((int) period);
                state.setEnabled(enabled);

                // 启用：先采集再按周期休眠；停用：只按 10 秒轮询配置，不执行采集
                if (enabled) {
                    runCollectionCycle();
                    waitSeconds = period;
                } else {
                    waitSeconds = DISABLED_POLL_SECONDS;
                }
            } catch (Exception e) {
                // 任何异常都不能让采集线程死掉：记录错误、写入状态，并降级为 10 秒后重试
                log.error("Collection loop error (database unavailable?)", e);
                state.onError("Loop error: " + e.getMessage());
                waitSeconds = DISABLED_POLL_SECONDS;
            }
            // 可被 wakeUp() 提前打断的睡眠
            sleep(waitSeconds);
        }
    }

    // 单个采集周期：TDengine 写时序、达梦写当前值，两步都成功才算本轮成功
    private void runCollectionCycle() {
        log.info("Collection cycle started");
        try {
            // OSHI 采样，内部会阻塞约 1 秒
            CollectedMetrics metrics = metricCollectorService.collect();
            // 时序行写入 TDengine（时间戳用数据库服务端的 now）
            tdengineMetricWriter.write(metrics);
            // 当前值写入达梦 t_metric_latest，供页面快速读取
            metricLatestUpdater.update(metrics);
            // 两个库都写成功后才更新状态，避免前端看到未落库的脏数据
            state.onSuccess(metrics);
            log.info("Collection cycle finished: cpu {}%, mem {}%, disk {} GB, "
                            + "net send {} KB/s, recv {} KB/s, temp {}",
                    metrics.getCpuUsage(), metrics.getMemUsage(), metrics.getDiskFreeGb(),
                    metrics.getNetSendKbps(), metrics.getNetRecvKbps(),
                    metrics.getCpuTemp() == null ? "n/a" : metrics.getCpuTemp() + " C");
        } catch (Exception e) {
            // 采集或写库失败：只记录并更新状态，不向上抛出，线程继续下一轮
            log.error("Collection cycle failed", e);
            state.onError("Collection failed: " + e.getMessage());
        }
    }

    // 可被外部唤醒的睡眠，替代 Thread.sleep（后者无法被 notify 打断）
    private void sleep(long seconds) {
        synchronized (lock) {
            try {
                // 注意 wait 参数单位是毫秒，而配置周期单位是秒
                lock.wait(seconds * 1000);
            } catch (InterruptedException e) {
                // 被 interrupt 时恢复中断标志，让外层 while(running) 尽快结束
                Thread.currentThread().interrupt();
            }
        }
    }

    // Spring 容器关闭时回调，实现优雅停机：先置标志再唤醒线程，让它尽快退出 while 循环
    @PreDestroy
    public void shutdown() {
        // volatile 写：采集线程下一轮判断即可看到
        running = false;
        // 若线程正在 wait 中，立即唤醒它去检查 running
        wakeUp();
        if (loopThread != null) {
            // 兜底：即使线程卡在采样或 JDBC 调用上也能被打断，加快停机
            loopThread.interrupt();
        }
    }
}
