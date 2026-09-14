package com.kms.collector.service;

import com.kms.collector.state.CollectorState;
import com.kms.database.entity.CollectorConfig;
import com.kms.database.mapper.CollectorConfigMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Business operations behind the REST control API (spec 4.2): change the
 * collection period, enable/disable collection. All changes persist to
 * t_collector_config and wake the collection loop so they apply immediately.
 *
 * <p>【中文】REST 控制 API 背后的业务逻辑（规格 4.2）：修改采集周期、启用/停用采集。
 * 所有变更都会持久化到 t_collector_config 并唤醒采集循环，因此无需重启立即生效。
 */
@Service
public class CollectorConfigService {

    // 规格要求：采集周期不得小于 60 秒；接口校验与采集循环的运行期兜底共用该常量
    public static final int MIN_PERIOD_SECONDS = 60;

    private final CollectorConfigMapper collectorConfigMapper;
    private final CollectionLoop collectionLoop;
    private final CollectorState state;

    // 改配置依赖三个组件：写库的 Mapper、负责重新读库的循环、以及给 REST 读的状态对象
    public CollectorConfigService(CollectorConfigMapper collectorConfigMapper,
                                  CollectionLoop collectionLoop,
                                  CollectorState state) {
        this.collectorConfigMapper = collectorConfigMapper;
        this.collectionLoop = collectionLoop;
        this.state = state;
    }

    // 查询当前状态：状态对象的字段均为 volatile，可直接序列化为 JSON 返回
    public CollectorState getStatus() {
        return state;
    }

    // 修改采集周期：校验 → 写库 → 更新内存状态 → 唤醒循环，“四步”缺一不可
    public void updatePeriod(int periodSeconds) {
        // 先做参数校验，非法入参抛 IllegalArgumentException，由 Controller 翻译成 HTTP 400
        if (periodSeconds < MIN_PERIOD_SECONDS) {
            throw new IllegalArgumentException(
                    "Collection period must be >= " + MIN_PERIOD_SECONDS + " seconds");
        }
        CollectorConfig config = loadConfig();
        config.setPeriodSeconds(periodSeconds);
        config.setUpdateTime(LocalDateTime.now());
        // 持久化到数据库：循环每轮都会重读该行，重启后同样生效
        collectorConfigMapper.updateById(config);
        // Reflect the change in the status immediately; the loop re-reads the row too.
        // 同步更新内存状态，前端无需等到下一轮采集就能查到新周期
        state.setPeriodSeconds(periodSeconds);
        // 必须唤醒循环：否则要等满一个旧周期（可能 60 秒）新周期才生效
        collectionLoop.wakeUp();
    }

    // 启用/停用采集：同样走“写库 + 更新状态 + 唤醒循环”三步
    public void setEnabled(boolean enabled) {
        CollectorConfig config = loadConfig();
        // 达梦用 INT 表示布尔：1 启用 / 0 停用
        config.setEnabled(enabled ? 1 : 0);
        config.setUpdateTime(LocalDateTime.now());
        collectorConfigMapper.updateById(config);
        state.setEnabled(enabled);
        // 立即生效：启用时无需再等 10 秒轮询，停用时立刻停止采集
        collectionLoop.wakeUp();
    }

    // 读取单行配置；配置缺失说明建表初始化没跑成功，直接抛异常比默默使用默认值更安全
    private CollectorConfig loadConfig() {
        CollectorConfig config = collectorConfigMapper.selectById(1);
        if (config == null) {
            throw new IllegalStateException(
                    "t_collector_config row (id=1) is missing — schema not initialized");
        }
        return config;
    }
}
