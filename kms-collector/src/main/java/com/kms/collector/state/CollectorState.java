package com.kms.collector.state;

import com.kms.collector.model.CollectedMetrics;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Volatile runtime status of the collector, exposed through the REST API
 * (spec 4.2: failures must be reported to the caller). Written by the
 * collection loop, read by the controller.
 *
 * <p>【中文】采集器的运行期状态（全部字段为 volatile），通过 REST API 暴露给调用方
 * （规格 4.2 要求把失败原因反馈给调用者）。由采集线程写入、Tomcat 请求线程读取，
 * 因此所有字段都必须用 volatile 保证跨线程可见性。
 */
@Data
@Component
public class CollectorState {

    /**
     * Whether collection is currently enabled (from t_collector_config).
     *
     * <p>【中文】当前是否启用采集（来源 t_collector_config.enabled）；采集线程写、
     * Tomcat 线程读，因此必须 volatile 保证可见性。
     */
    private volatile boolean enabled = true;

    /**
     * Current collection period in seconds.
     *
     * <p>【中文】当前采集周期（秒）。改周期时先写库、再更新本字段并唤醒循环，
     * 循环每轮还会重新读库，两处保持一致。
     */
    private volatile int periodSeconds = 60;

    /**
     * Timestamp of the last successful collection.
     *
     * <p>【中文】最近一次采集成功的时间戳。
     */
    private volatile LocalDateTime lastCollectTime;

    /**
     * Values of the last successful collection.
     *
     * <p>【中文】最近一次采集成功的指标快照。每个周期都整体替换引用，因此 volatile
     * 只需保证引用可见性（不会原地修改对象内部字段）。
     */
    private volatile CollectedMetrics lastMetrics;

    /**
     * Message of the last failure; null when the last cycle succeeded.
     *
     * <p>【中文】最近一次失败的错误消息；为 null 表示“最后一次操作是成功的”，
     * 它反映最近一次结果而不是历史错误清单。
     */
    private volatile String lastError;

    /**
     * Total number of successful collection cycles.
     *
     * <p>【中文】累计采集成功次数。只有唯一的采集线程会自增，单写线程场景无需
     * AtomicLong，volatile 仅用于让读取方看到最新值。
     */
    private volatile long collectCount;

    // 一次采集周期全部成功（TDengine 与达梦都写成功）后的回调，刷新状态供 REST 查询
    public void onSuccess(CollectedMetrics metrics) {
        this.lastCollectTime = metrics.getCollectedAt();
        this.lastMetrics = metrics;
        // 成功后必须清空错误：lastError 表示“最后一次操作的结果”，而不是历史错误列表
        this.lastError = null;
        // 只有采集线程会执行自增，单写线程场景普通 long 自增即可
        this.collectCount++;
    }

    // 采集失败回调：只记录错误消息，保留上一次成功的指标与时间，便于前端同时看到“最后好数据”与失败原因
    public void onError(String message) {
        this.lastError = message;
    }
}
