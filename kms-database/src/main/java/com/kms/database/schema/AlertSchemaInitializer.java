package com.kms.database.schema;

import com.kms.database.entity.AlertRule;
import com.kms.database.entity.MetricDef;
import com.kms.database.mapper.AlertRuleMapper;
import com.kms.database.mapper.MetricDefMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Creates/verifies the analyser tables in DM8 on startup (idempotent, runs in
 * every module that scans {@code com.kms} — analyser, web and collector).
 * Tables: t_metric_def (thresholds), t_alert_rule (expressions),
 * t_alert_record (generated alerts). Seeds metric definitions and rules on
 * first start. Canonical DDL: kms-database/src/main/resources/sql/dm8-schema.sql.
 */
@Component
@Order(1)
public class AlertSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AlertSchemaInitializer.class);

    private static final String CREATE_METRIC_DEF_TABLE = "CREATE TABLE t_metric_def ("
            + "metric_code VARCHAR(32) NOT NULL, metric_name VARCHAR(64), unit VARCHAR(16), "
            + "upper_threshold DOUBLE, lower_threshold DOUBLE, volatility_threshold DOUBLE, "
            + "dynamic_threshold INT, update_time TIMESTAMP, PRIMARY KEY (metric_code))";

    private static final String CREATE_ALERT_RULE_TABLE = "CREATE TABLE t_alert_rule ("
            + "id INT NOT NULL, rule_code VARCHAR(64), rule_name VARCHAR(128), "
            + "metric_code VARCHAR(32), expression VARCHAR(256), enabled INT, "
            + "update_time TIMESTAMP, PRIMARY KEY (id))";

    private static final String CREATE_ALERT_RECORD_TABLE = "CREATE TABLE t_alert_record ("
            + "id BIGINT IDENTITY(1,1), metric_code VARCHAR(32), metric_name VARCHAR(64), "
            + "alert_level INT, alert_type VARCHAR(32), message VARCHAR(512), "
            + "current_value DOUBLE, threshold_value DOUBLE, volatility DOUBLE, "
            + "status INT, create_time TIMESTAMP, update_time TIMESTAMP, PRIMARY KEY (id))";

    private final JdbcTemplate dmJdbcTemplate;
    private final MetricDefMapper metricDefMapper;
    private final AlertRuleMapper alertRuleMapper;

    public AlertSchemaInitializer(@Qualifier("dmJdbcTemplate") JdbcTemplate dmJdbcTemplate,
                                  MetricDefMapper metricDefMapper,
                                  AlertRuleMapper alertRuleMapper) {
        this.dmJdbcTemplate = dmJdbcTemplate;
        this.metricDefMapper = metricDefMapper;
        this.alertRuleMapper = alertRuleMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureTable("T_METRIC_DEF", CREATE_METRIC_DEF_TABLE);
        ensureTable("T_ALERT_RULE", CREATE_ALERT_RULE_TABLE);
        ensureTable("T_ALERT_RECORD", CREATE_ALERT_RECORD_TABLE);
        seedMetricDefs();
        seedAlertRules();
        log.info("DM8 analyser schema ready: t_metric_def, t_alert_rule, t_alert_record verified/created");
    }

    /** DM8 has no CREATE TABLE IF NOT EXISTS: check USER_TABLES first (names are uppercase). */
    private void ensureTable(String tableName, String ddl) {
        Integer count = dmJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?", Integer.class, tableName);
        if (count == null || count == 0) {
            dmJdbcTemplate.execute(ddl);
        }
    }

    /** Seed metric definitions (spec 4.3: thresholds per metric; volatility in percent). */
    private void seedMetricDefs() {
        // metricCode, metricName, unit, upper, lower, volatility%, dynamic(0/1)
        Object[][] defs = {
                {"cpu_usage", "CPU Usage", "%", 90.0, 0.0, 10.0, 1},
                {"mem_usage", "Memory Usage", "%", 90.0, 0.0, 10.0, 0},
                {"disk_free", "Disk Free Space", "GB", null, 10.0, 10.0, 0},
                {"net_send", "Network Send Rate", "KB/s", 51200.0, 0.0, 15.0, 0},
                {"net_recv", "Network Receive Rate", "KB/s", 51200.0, 0.0, 15.0, 0},
                {"cpu_temp", "CPU Temperature", "C", 90.0, 0.0, 10.0, 0},
        };
        for (Object[] def : defs) {
            String code = (String) def[0];
            if (metricDefMapper.selectById(code) == null) {
                MetricDef row = new MetricDef();
                row.setMetricCode(code);
                row.setMetricName((String) def[1]);
                row.setUnit((String) def[2]);
                row.setUpperThreshold((Double) def[3]);
                row.setLowerThreshold((Double) def[4]);
                row.setVolatilityThreshold((Double) def[5]);
                row.setDynamicThreshold((Integer) def[6]);
                row.setUpdateTime(LocalDateTime.now());
                metricDefMapper.insert(row);
            }
        }
    }

    /** Seed alert rules (spec 4.3: condition expressions matched against the latest value). */
    private void seedAlertRules() {
        Object[][] rules = {
                {1, "RULE_CPU", "CPU usage threshold", "cpu_usage", "value > upper_threshold"},
                {2, "RULE_MEM", "Memory usage threshold", "mem_usage", "value > upper_threshold"},
                {3, "RULE_DISK", "Disk free space floor", "disk_free", "value < lower_threshold"},
                {4, "RULE_NET_SEND", "Network send rate ceiling", "net_send", "value > upper_threshold"},
                {5, "RULE_NET_RECV", "Network receive rate ceiling", "net_recv", "value > upper_threshold"},
                {6, "RULE_TEMP", "CPU temperature threshold", "cpu_temp", "value > upper_threshold"},
        };
        for (Object[] rule : rules) {
            Integer id = (Integer) rule[0];
            if (alertRuleMapper.selectById(id) == null) {
                AlertRule row = new AlertRule();
                row.setId(id);
                row.setRuleCode((String) rule[1]);
                row.setRuleName((String) rule[2]);
                row.setMetricCode((String) rule[3]);
                row.setExpression((String) rule[4]);
                row.setEnabled(1);
                row.setUpdateTime(LocalDateTime.now());
                alertRuleMapper.insert(row);
            }
        }
    }
}
