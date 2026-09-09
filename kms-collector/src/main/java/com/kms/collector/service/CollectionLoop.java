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
 */
@Component
@Order(2)
public class CollectionLoop implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectionLoop.class);

    private static final int CONFIG_ID = 1;
    private static final int DEFAULT_PERIOD_SECONDS = 60;
    private static final int MIN_PERIOD_SECONDS = 60;
    private static final int DISABLED_POLL_SECONDS = 10;

    private final Object lock = new Object();
    private volatile boolean running = true;
    private Thread loopThread;

    private final CollectorConfigMapper collectorConfigMapper;
    private final MetricCollectorService metricCollectorService;
    private final TdengineMetricWriter tdengineMetricWriter;
    private final MetricLatestUpdater metricLatestUpdater;
    private final CollectorState state;

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

    @Override
    public void run(ApplicationArguments args) {
        loopThread = new Thread(this::loop, "kms-collect-loop");
        loopThread.setDaemon(true);
        loopThread.start();
        log.info("Collection loop started (default period {} s)", DEFAULT_PERIOD_SECONDS);
    }

    /** Wake the loop so a config change (period/enable) applies immediately. */
    public void wakeUp() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void loop() {
        while (running) {
            long waitSeconds;
            try {
                CollectorConfig config = collectorConfigMapper.selectById(CONFIG_ID);
                long period = (config != null && config.getPeriodSeconds() != null)
                        ? Math.max(config.getPeriodSeconds(), MIN_PERIOD_SECONDS)
                        : DEFAULT_PERIOD_SECONDS;
                boolean enabled = config != null && Integer.valueOf(1).equals(config.getEnabled());

                state.setPeriodSeconds((int) period);
                state.setEnabled(enabled);

                if (enabled) {
                    runCollectionCycle();
                    waitSeconds = period;
                } else {
                    waitSeconds = DISABLED_POLL_SECONDS;
                }
            } catch (Exception e) {
                log.error("Collection loop error (database unavailable?)", e);
                state.onError("Loop error: " + e.getMessage());
                waitSeconds = DISABLED_POLL_SECONDS;
            }
            sleep(waitSeconds);
        }
    }

    private void runCollectionCycle() {
        log.info("Collection cycle started");
        try {
            CollectedMetrics metrics = metricCollectorService.collect();
            tdengineMetricWriter.write(metrics);
            metricLatestUpdater.update(metrics);
            state.onSuccess(metrics);
            log.info("Collection cycle finished: cpu {}%, mem {}%, disk {} GB, "
                            + "net send {} KB/s, recv {} KB/s, temp {}",
                    metrics.getCpuUsage(), metrics.getMemUsage(), metrics.getDiskFreeGb(),
                    metrics.getNetSendKbps(), metrics.getNetRecvKbps(),
                    metrics.getCpuTemp() == null ? "n/a" : metrics.getCpuTemp() + " C");
        } catch (Exception e) {
            log.error("Collection cycle failed", e);
            state.onError("Collection failed: " + e.getMessage());
        }
    }

    private void sleep(long seconds) {
        synchronized (lock) {
            try {
                lock.wait(seconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        wakeUp();
        if (loopThread != null) {
            loopThread.interrupt();
        }
    }
}
