package com.kms.collector.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kms.collector.model.CollectedMetrics;
import com.kms.database.entity.MetricLatest;
import com.kms.database.mapper.MetricLatestMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Updates the current values in DM8 {@code t_metric_latest} after each cycle
 * (spec 4.2: "optional, for fast display of latest values").
 */
@Component
public class MetricLatestUpdater {

    private final MetricLatestMapper metricLatestMapper;

    public MetricLatestUpdater(MetricLatestMapper metricLatestMapper) {
        this.metricLatestMapper = metricLatestMapper;
    }

    public void update(CollectedMetrics m) {
        LocalDateTime now = m.getCollectedAt();
        update("cpu_usage", m.getCpuUsage(), now);
        update("mem_usage", m.getMemUsage(), now);
        update("disk_free", m.getDiskFreeGb(), now);
        update("net_send", m.getNetSendKbps(), now);
        update("net_recv", m.getNetRecvKbps(), now);
        update("cpu_temp", m.getCpuTemp(), now);
    }

    /** Explicit set() so a null value (unsupported temperature) clears the column. */
    private void update(String metricCode, Double value, LocalDateTime now) {
        LambdaUpdateWrapper<MetricLatest> wrapper = Wrappers.<MetricLatest>lambdaUpdate()
                .eq(MetricLatest::getMetricCode, metricCode)
                .set(MetricLatest::getCurrentValue, value)
                .set(MetricLatest::getUpdateTime, now);
        metricLatestMapper.update(null, wrapper);
    }
}
