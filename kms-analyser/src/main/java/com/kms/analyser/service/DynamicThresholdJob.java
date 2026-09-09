package com.kms.analyser.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kms.database.entity.MetricDef;
import com.kms.database.mapper.MetricDefMapper;
import com.kms.database.metric.KmsMetrics;
import com.kms.database.tdengine.TdengineTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic threshold recomputation (spec 4.3, bonus feature): every 5 minutes,
 * for every metric flagged dynamic in {@code t_metric_def}, compute the mean
 * and standard deviation of the last hour of TDengine data and adjust the
 * thresholds to mean ± 2×std.
 */
@Component
public class DynamicThresholdJob {

    private static final Logger log = LoggerFactory.getLogger(DynamicThresholdJob.class);

    /** Minimum number of samples before dynamic thresholds are trusted. */
    private static final long MIN_SAMPLES = 5;

    private final MetricDefMapper metricDefMapper;
    private final JdbcTemplate taosJdbcTemplate;
    private final String subTable;

    public DynamicThresholdJob(MetricDefMapper metricDefMapper,
                               @Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate) {
        this.metricDefMapper = metricDefMapper;
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.subTable = TdengineTableNames.subTable(TdengineTableNames.localHostName());
    }

    @Scheduled(fixedDelayString = "${kms.analyser.dynamic-threshold-interval-ms:300000}",
            initialDelayString = "${kms.analyser.dynamic-threshold-initial-delay-ms:120000}")
    public void recompute() {
        log.info("Dynamic threshold recomputation started");
        List<MetricDef> dynamicDefs = metricDefMapper.selectList(
                new LambdaQueryWrapper<MetricDef>().eq(MetricDef::getDynamicThreshold, 1));
        for (MetricDef def : dynamicDefs) {
            if (!KmsMetrics.isKnownCode(def.getMetricCode())) {
                log.warn("Metric {} is not a known metric code, skipped", def.getMetricCode());
                continue;
            }
            try {
                recomputeMetric(def);
            } catch (Exception e) {
                log.error("Dynamic threshold recomputation failed for metric {}", def.getMetricCode(), e);
            }
        }
    }

    private void recomputeMetric(MetricDef def) {
        String code = def.getMetricCode();
        // Mean / stddev / count of the last hour from TDengine (server clock,
        // Asia/Shanghai connection timezone).
        List<Double[]> stats = taosJdbcTemplate.query(
                "SELECT AVG(" + code + "), STDDEV(" + code + "), COUNT(" + code + ") "
                        + "FROM " + subTable + " WHERE ts >= NOW - 1h",
                (rs, i) -> {
                    double avg = rs.getDouble(1);
                    Double avgVal = rs.wasNull() ? null : avg;
                    double std = rs.getDouble(2);
                    Double stdVal = rs.wasNull() ? null : std;
                    return new Double[]{avgVal, stdVal, (double) rs.getLong(3)};
                });
        Double[] s = stats.get(0);
        long samples = s[2] == null ? 0 : s[2].longValue();
        if (samples < MIN_SAMPLES || s[0] == null) {
            log.info("Metric {}: only {} sample(s) in the last hour (need {}), "
                    + "thresholds left unchanged", code, samples, MIN_SAMPLES);
            return;
        }
        double mean = s[0];
        double std = s[1] == null ? 0.0 : s[1];
        double upper = mean + 2 * std;
        double lower = Math.max(0.0, mean - 2 * std);

        LambdaUpdateWrapper<MetricDef> wrapper = Wrappers.<MetricDef>lambdaUpdate()
                .eq(MetricDef::getMetricCode, code)
                .set(MetricDef::getUpperThreshold, upper)
                .set(MetricDef::getLowerThreshold, lower)
                .set(MetricDef::getUpdateTime, LocalDateTime.now());
        metricDefMapper.update(null, wrapper);
        log.info("Metric {}: dynamic thresholds adjusted to [{}, {}] "
                        + "(mean {} ± 2×std {} over {} samples, last hour)",
                code, String.format(Locale.ROOT, "%.2f", lower), String.format(Locale.ROOT, "%.2f", upper),
                String.format(Locale.ROOT, "%.2f", mean), String.format(Locale.ROOT, "%.2f", std), samples);
    }
}
