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
 */
@Service
public class CollectorConfigService {

    public static final int MIN_PERIOD_SECONDS = 60;

    private final CollectorConfigMapper collectorConfigMapper;
    private final CollectionLoop collectionLoop;
    private final CollectorState state;

    public CollectorConfigService(CollectorConfigMapper collectorConfigMapper,
                                  CollectionLoop collectionLoop,
                                  CollectorState state) {
        this.collectorConfigMapper = collectorConfigMapper;
        this.collectionLoop = collectionLoop;
        this.state = state;
    }

    public CollectorState getStatus() {
        return state;
    }

    public void updatePeriod(int periodSeconds) {
        if (periodSeconds < MIN_PERIOD_SECONDS) {
            throw new IllegalArgumentException(
                    "Collection period must be >= " + MIN_PERIOD_SECONDS + " seconds");
        }
        CollectorConfig config = loadConfig();
        config.setPeriodSeconds(periodSeconds);
        config.setUpdateTime(LocalDateTime.now());
        collectorConfigMapper.updateById(config);
        // Reflect the change in the status immediately; the loop re-reads the row too.
        state.setPeriodSeconds(periodSeconds);
        collectionLoop.wakeUp();
    }

    public void setEnabled(boolean enabled) {
        CollectorConfig config = loadConfig();
        config.setEnabled(enabled ? 1 : 0);
        config.setUpdateTime(LocalDateTime.now());
        collectorConfigMapper.updateById(config);
        state.setEnabled(enabled);
        collectionLoop.wakeUp();
    }

    private CollectorConfig loadConfig() {
        CollectorConfig config = collectorConfigMapper.selectById(1);
        if (config == null) {
            throw new IllegalStateException(
                    "t_collector_config row (id=1) is missing — schema not initialized");
        }
        return config;
    }
}
