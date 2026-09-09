package com.kms.collector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One collection cycle's snapshot of the host metrics (spec 4.2): CPU %,
 * memory %, disk free GB, network send/recv KB/s and CPU temperature.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectedMetrics {

    private LocalDateTime collectedAt;

    /** CPU usage percent (0-100). */
    private double cpuUsage;

    /** Memory usage percent (0-100). */
    private double memUsage;

    /** Free space on the collector's working drive, GB. */
    private double diskFreeGb;

    /** Network send rate since the previous cycle, KB/s. */
    private double netSendKbps;

    /** Network receive rate since the previous cycle, KB/s. */
    private double netRecvKbps;

    /** CPU temperature in degrees C; null when OSHI cannot read it (spec: "if supported"). */
    private Double cpuTemp;
}
