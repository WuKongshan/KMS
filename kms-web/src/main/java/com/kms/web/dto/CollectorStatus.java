package com.kms.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Collector status as returned by the collector's control API
 * ({@code GET /api/collector/status} on port 8081), enriched with an
 * {@code available} flag so the ops page can show "collector down" instead of
 * failing. Mirrors the collector's CollectorState.
 */
@Data
public class CollectorStatus {

    /** false when the collector process is unreachable. */
    private boolean available = true;

    /** Whether collection is currently enabled. */
    private boolean enabled;

    /** Current collection period in seconds. */
    private int periodSeconds;

    /** Timestamp of the last successful collection. */
    private LocalDateTime lastCollectTime;

    /** Values of the last successful collection (null when none yet). */
    private LastMetrics lastMetrics;

    /** Message of the last failure; null when the last cycle succeeded. */
    private String lastError;

    /** Total number of successful collection cycles. */
    private long collectCount;

    /** Last values per metric collected on the host (mirrors CollectedMetrics). */
    @Data
    public static class LastMetrics {
        private Double cpuUsage;
        private Double memUsage;
        private Double diskFreeGb;
        private Double netSendKbps;
        private Double netRecvKbps;
        private Double cpuTemp;
        private LocalDateTime collectedAt;
    }
}
