package com.kms.analyser.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kms.analyser.rule.RuleExpression;
import com.kms.database.entity.AlertRecord;
import com.kms.database.entity.AlertRule;
import com.kms.database.entity.MetricDef;
import com.kms.database.mapper.AlertRecordMapper;
import com.kms.database.mapper.AlertRuleMapper;
import com.kms.database.mapper.MetricDefMapper;
import com.kms.database.metric.KmsMetrics;
import com.kms.database.tdengine.TdengineTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The analysis loop (spec 4.3): reads the last two TDengine points of every
 * metric, matches the {@code t_alert_rule} condition expression against the
 * latest value (rule engine), computes the adjacent-point volatility, and
 * derives the graded alert level:
 *
 * <ul>
 *   <li>level 1 — fixed-threshold breach only</li>
 *   <li>level 2 — volatility above the configured percent while the value is
 *       still within the normal range</li>
 *   <li>level 3 — threshold breach and volatility breach at the same time</li>
 * </ul>
 *
 * <p>Alerts are inserted into {@code t_alert_record} (with a cooldown per
 * metric + level to avoid flooding) and pushed to the frontend over the
 * {@code /ws/alerts} WebSocket. Every evaluation is logged.
 */
@Component
public class AnalysisLoop {

    private static final Logger log = LoggerFactory.getLogger(AnalysisLoop.class);

    private final MetricDefMapper metricDefMapper;
    private final AlertRuleMapper alertRuleMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final JdbcTemplate taosJdbcTemplate;
    private final JdbcTemplate dmJdbcTemplate;
    private final AlertWebSocketHandler webSocketHandler;
    private final String subTable;
    private final long cooldownMinutes;

    public AnalysisLoop(MetricDefMapper metricDefMapper,
                        AlertRuleMapper alertRuleMapper,
                        AlertRecordMapper alertRecordMapper,
                        @Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate,
                        @Qualifier("dmJdbcTemplate") JdbcTemplate dmJdbcTemplate,
                        AlertWebSocketHandler webSocketHandler,
                        @Value("${kms.analyser.alert-cooldown-minutes:5}") long cooldownMinutes) {
        this.metricDefMapper = metricDefMapper;
        this.alertRuleMapper = alertRuleMapper;
        this.alertRecordMapper = alertRecordMapper;
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.dmJdbcTemplate = dmJdbcTemplate;
        this.webSocketHandler = webSocketHandler;
        this.subTable = TdengineTableNames.subTable(TdengineTableNames.localHostName());
        this.cooldownMinutes = cooldownMinutes;
    }

    @Scheduled(fixedDelayString = "${kms.analyser.interval-ms:30000}",
            initialDelayString = "${kms.analyser.initial-delay-ms:15000}")
    public void runAnalysis() {
        log.info("Analysis cycle started");
        Map<String, MetricDef> defs;
        Map<String, AlertRule> rules;
        try {
            defs = metricDefMapper.selectList(null).stream()
                    .collect(Collectors.toMap(MetricDef::getMetricCode, Function.identity()));
            rules = alertRuleMapper.selectList(
                            new LambdaQueryWrapper<AlertRule>().eq(AlertRule::getEnabled, 1)).stream()
                    .collect(Collectors.toMap(AlertRule::getMetricCode, Function.identity()));
        } catch (Exception e) {
            log.error("Analysis cycle aborted: cannot read DM8 (database unavailable?)", e);
            return;
        }
        for (MetricDef def : defs.values()) {
            AlertRule rule = rules.get(def.getMetricCode());
            if (rule == null) {
                log.debug("No enabled rule for metric {}, skipped", def.getMetricCode());
                continue;
            }
            if (!KmsMetrics.isKnownCode(def.getMetricCode())) {
                log.warn("Metric {} is not a known metric code, skipped", def.getMetricCode());
                continue;
            }
            try {
                analyseMetric(def, rule);
            } catch (Exception e) {
                log.error("Analysis failed for metric {}", def.getMetricCode(), e);
            }
        }
    }

    private void analyseMetric(MetricDef def, AlertRule rule) {
        String code = def.getMetricCode();
        List<Map<String, Object>> rows = taosJdbcTemplate.queryForList(
                "SELECT ts, " + code + " FROM " + subTable + " ORDER BY ts DESC LIMIT 2");
        if (rows.size() < 2) {
            log.info("Metric {}: only {} point(s) collected so far, need 2, skipped",
                    code, rows.size());
            return;
        }
        Double latest = toDouble(rows.get(0).get(code));
        Double previous = toDouble(rows.get(1).get(code));
        if (latest == null) {
            log.info("Metric {}: latest value is null (unsupported sensor?), skipped", code);
            return;
        }

        // Bind rule variables: value, thresholds and observed volatility.
        Map<String, Double> vars = new HashMap<>();
        vars.put("value", latest);
        vars.put("upper_threshold", def.getUpperThreshold());
        vars.put("lower_threshold", def.getLowerThreshold());
        vars.put("volatility_threshold", def.getVolatilityThreshold());
        Double volatility = null;
        if (previous != null && previous != 0.0) {
            volatility = Math.abs(latest - previous) / Math.abs(previous) * 100;
        }
        vars.put("volatility", volatility);

        boolean thresholdBreach;
        try {
            thresholdBreach = RuleExpression.evaluate(rule.getExpression(), vars);
        } catch (IllegalArgumentException e) {
            log.error("Metric {}: rule {} has an invalid expression '{}': {}",
                    code, rule.getRuleCode(), rule.getExpression(), e.getMessage());
            return;
        }
        boolean volatilityBreach = volatility != null && def.getVolatilityThreshold() != null
                && volatility > def.getVolatilityThreshold();

        log.info("Metric {}: value={} previous={} volatility={}% (limit {}%) "
                        + "thresholdBreach={} volatilityBreach={}",
                code, fmt(latest), previous == null ? "n/a" : fmt(previous),
                volatility == null ? "n/a" : fmt(volatility),
                def.getVolatilityThreshold() == null ? "n/a" : fmt(def.getVolatilityThreshold()),
                thresholdBreach, volatilityBreach);

        if (!thresholdBreach && !volatilityBreach) {
            return;
        }

        // Level 1 = threshold only, level 2 = volatility only (value normal),
        // level 3 = both (spec 4.3).
        int level = thresholdBreach && volatilityBreach ? 3 : thresholdBreach ? 1 : 2;
        if (!checkCooldown(code, level)) {
            log.info("Metric {}: level {} alert suppressed by cooldown ({} min)", code, level, cooldownMinutes);
            return;
        }

        AlertRecord record = buildRecord(def, rule, latest, volatility, thresholdBreach, volatilityBreach, level);
        alertRecordMapper.insert(record);
        log.warn("ALERT generated (level {}): {}", level, record.getMessage());
        webSocketHandler.broadcast(record);
    }

    /**
     * Suppress repeated alerts for the same metric + level within the cooldown
     * window (DM8 stores the newest t_alert_record row per key).
     */
    private boolean checkCooldown(String metricCode, int level) {
        Timestamp last = dmJdbcTemplate.queryForObject(
                "SELECT MAX(create_time) FROM t_alert_record WHERE metric_code = ? AND alert_level = ?",
                Timestamp.class, metricCode, level);
        return last == null || System.currentTimeMillis() - last.getTime() >= cooldownMinutes * 60_000L;
    }

    private AlertRecord buildRecord(MetricDef def, AlertRule rule, Double latest, Double volatility,
                                    boolean thresholdBreach, boolean volatilityBreach, int level) {
        String unit = def.getUnit() == null ? "" : def.getUnit();
        Double thresholdValue = null;
        if (thresholdBreach && def.getUpperThreshold() != null && latest > def.getUpperThreshold()) {
            thresholdValue = def.getUpperThreshold();
        } else if (thresholdBreach && def.getLowerThreshold() != null && latest < def.getLowerThreshold()) {
            thresholdValue = def.getLowerThreshold();
        }

        AlertRecord record = new AlertRecord();
        record.setMetricCode(def.getMetricCode());
        record.setMetricName(def.getMetricName());
        record.setAlertLevel(level);
        record.setCurrentValue(latest);
        record.setThresholdValue(thresholdValue);
        record.setVolatility(volatilityBreach ? volatility : null);
        record.setStatus(0);
        LocalDateTime now = LocalDateTime.now();
        record.setCreateTime(now);
        record.setUpdateTime(now);
        record.setAlertType(switch (level) {
            case 1 -> "THRESHOLD";
            case 2 -> "VOLATILITY";
            default -> "THRESHOLD_VOLATILITY";
        });

        String message;
        if (level == 1) {
            message = thresholdValue != null
                    ? String.format(Locale.ROOT, "%s %s%s breaches threshold %s%s (rule %s)",
                    def.getMetricName(), fmt(latest), unit, fmt(thresholdValue), unit, rule.getRuleCode())
                    : String.format(Locale.ROOT, "%s value %s%s matched rule %s",
                    def.getMetricName(), fmt(latest), unit, rule.getRuleCode());
        } else if (level == 2) {
            message = String.format(Locale.ROOT,
                    "%s volatility %s%% exceeds %s%% (value %s%s still within normal range)",
                    def.getMetricName(), fmt(volatility), fmt(def.getVolatilityThreshold()), fmt(latest), unit);
        } else {
            message = String.format(Locale.ROOT,
                    "%s %s%s breaches threshold %s%s and volatility %s%% exceeds %s%%",
                    def.getMetricName(), fmt(latest), unit,
                    thresholdValue == null ? "n/a" : fmt(thresholdValue), unit,
                    fmt(volatility), fmt(def.getVolatilityThreshold()));
        }
        record.setMessage(message);
        return record;
    }

    private static Double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static String fmt(Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.2f", value);
    }
}
