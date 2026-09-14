package com.kms.collector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One collection cycle's snapshot of the host metrics (spec 4.2): CPU %,
 * memory %, disk free GB, network send/recv KB/s and CPU temperature.
 *
 * <p>【中文】一个采集周期内宿主机指标的快照（规格 4.2）：CPU 使用率（%）、
 * 内存使用率（%）、磁盘剩余空间（GB）、网络发送/接收速率（KB/s）与 CPU 温度。
 * 该对象由采集线程构造后既写入 TDengine 时序表，也用于刷新达梦当前值表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectedMetrics {

    // 采集时刻（宿主机本地时间），同时作为达梦 update_time 的取值
    private LocalDateTime collectedAt;

    /**
     * CPU usage percent (0-100).
     *
     * <p>【中文】CPU 使用率百分比，取值 0-100（OSHI 阻塞采样 1 秒后的结果乘以 100）。
     */
    private double cpuUsage;

    /**
     * Memory usage percent (0-100).
     *
     * <p>【中文】内存使用率百分比，取值 0-100，按（总量 − 可用量）/ 总量 计算；
     * 用的是 available 而不是 free，可回收的缓存已计入“可用”。
     */
    private double memUsage;

    /**
     * Free space on the collector's working drive, GB.
     *
     * <p>【中文】采集器工作盘（当前工作目录所在磁盘）的剩余空间，单位 GB。
     */
    private double diskFreeGb;

    /**
     * Network send rate since the previous cycle, KB/s.
     *
     * <p>【中文】相对上一次采集的网卡发送速率，单位 KB/s；由累计字节计数器差分得到，
     * 首次采集没有基线，取 0。
     */
    private double netSendKbps;

    /**
     * Network receive rate since the previous cycle, KB/s.
     *
     * <p>【中文】相对上一次采集的网卡接收速率，单位 KB/s；同样是差分值，首次为 0。
     */
    private double netRecvKbps;

    /**
     * CPU temperature in degrees C; null when OSHI cannot read it (spec: "if supported").
     *
     * <p>【中文】CPU 温度（摄氏度）；OSHI 读不到时为 null，表示“宿主不支持”而不是采集失败
     * （规格要求“若支持则采集”）。此时 TDengine 写入 NULL、达梦当前值表也会被置空。
     */
    private Double cpuTemp;
}
