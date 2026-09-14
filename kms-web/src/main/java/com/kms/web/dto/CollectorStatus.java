package com.kms.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Collector status as returned by the collector's control API
 * ({@code GET /api/collector/status} on port 8081), enriched with an
 * {@code available} flag so the ops page can show "collector down" instead of
 * failing. Mirrors the collector's CollectorState.
 *
 * <p>【中文】采集器状态的镜像 DTO：字段与采集器原生的 CollectorState 一一对应。
 * <p>【中文】{@code available} 是本模块的本地扩展字段（采集器原生的 CollectorState 并没有它），
 * 专供运维页显示“采集器不可达”，而不是让页面加载失败。
 */
@Data
public class CollectorStatus {

    /**
     * false when the collector process is unreachable.
     *
     * <p>【中文】采集器进程不可达时为 false。默认值为 true（采集器正常应答时无需显式赋值）；
     * 只有 {@code CollectorProxyService.getStatus()} 在捕获到异常后，才会把它置为 false。
     */
    private boolean available = true;

    /**
     * Whether collection is currently enabled.
     *
     * <p>【中文】采集当前是否处于启用状态（对应采集器的启用/禁用开关）。
     */
    private boolean enabled;

    /**
     * Current collection period in seconds.
     *
     * <p>【中文】当前采集周期，单位秒；采集器侧保证不小于 60 秒。
     */
    private int periodSeconds;

    /**
     * Timestamp of the last successful collection.
     *
     * <p>【中文】最近一次成功采集的时间戳；从未成功采集时为 null。
     */
    private LocalDateTime lastCollectTime;

    /**
     * Values of the last successful collection (null when none yet).
     *
     * <p>【中文】最近一次成功采集得到的各项指标值；尚未采集成功时为 null。
     */
    private LastMetrics lastMetrics;

    /**
     * Message of the last failure; null when the last cycle succeeded.
     *
     * <p>【中文】最近一次失败的提示信息；上一轮采集成功时为 null。
     */
    private String lastError;

    /**
     * Total number of successful collection cycles.
     *
     * <p>【中文】累计成功采集的周期数（自采集器启动起算，不持久化）。
     */
    private long collectCount;

    /**
     * Last values per metric collected on the host (mirrors CollectedMetrics).
     *
     * <p>【中文】宿主机最近一次采集到的各项指标值（镜像采集器的 CollectedMetrics）。
     * <p>【中文】字段刻意使用包装类型 {@code Double} 而不是基本类型 double：温度等指标在宿主机
     * 不支持时为 null，需要原样透传给前端。
     */
    @Data
    public static class LastMetrics {
        // CPU 使用率（百分比）。
        private Double cpuUsage;
        // 内存使用率（百分比）。
        private Double memUsage;
        // 磁盘剩余空间（GB）。
        private Double diskFreeGb;
        // 网络发送速率（KB/s）。
        private Double netSendKbps;
        // 网络接收速率（KB/s）。
        private Double netRecvKbps;
        // CPU 温度（摄氏度）；宿主机无温度传感器时为 null（OSHI 返回空）。
        private Double cpuTemp;
        // 本组指标值的采集时间。
        private LocalDateTime collectedAt;
    }
}
