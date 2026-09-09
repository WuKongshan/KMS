package com.kms.collector.state;

import com.kms.collector.model.CollectedMetrics;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Volatile runtime status of the collector, exposed through the REST API
 * (spec 4.2: failures must be reported to the caller). Written by the
 * collection loop, read by the controller.
 */
@Data
@Component
public class CollectorState {

    /** Whether collection is currently enabled (from t_collector_config). */
    private volatile boolean enabled = true;

    /** Current collection period in seconds. */
    private volatile int periodSeconds = 60;

    /** Timestamp of the last successful collection. */
    private volatile LocalDateTime lastCollectTime;

    /** Values of the last successful collection. */
    private volatile CollectedMetrics lastMetrics;

    /** Message of the last failure; null when the last cycle succeeded. */
    private volatile String lastError;

    /** Total number of successful collection cycles. */
    private volatile long collectCount;

    public void onSuccess(CollectedMetrics metrics) {
        this.lastCollectTime = metrics.getCollectedAt();
        this.lastMetrics = metrics;
        this.lastError = null;
        this.collectCount++;
    }

    public void onError(String message) {
        this.lastError = message;
    }
}
