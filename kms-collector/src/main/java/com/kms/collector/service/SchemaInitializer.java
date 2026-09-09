package com.kms.collector.service;

import com.kms.database.config.TdengineDataSourceProperties;
import com.kms.database.entity.CollectorConfig;
import com.kms.database.entity.MetricLatest;
import com.kms.database.mapper.CollectorConfigMapper;
import com.kms.database.mapper.MetricLatestMapper;
import com.kms.database.tdengine.TdengineTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;

/**
 * Creates/verifies the KMS schema on startup (idempotent — safe on every
 * boot). TDengine: the {@code kms} database + supertable + this host's
 * subtable. DM8: t_collector_config / t_metric_latest tables plus their seed
 * rows. Runs before the collection loop (see {@link CollectionLoop}).
 * Canonical DDL: kms-database/src/main/resources/sql/*.sql.
 */
@Component
@Order(1)
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    // Note: "host" is a TDengine keyword, so the tag is named host_name.
    private static final String CREATE_STABLE = "CREATE STABLE IF NOT EXISTS t_perf_realtime ("
            + "ts TIMESTAMP, cpu_usage DOUBLE, mem_usage DOUBLE, disk_free DOUBLE, "
            + "net_send DOUBLE, net_recv DOUBLE, cpu_temp DOUBLE) TAGS (host_name VARCHAR(64))";

    private static final String CREATE_DM_CONFIG_TABLE = "CREATE TABLE t_collector_config ("
            + "id INT NOT NULL, period_seconds INT NOT NULL, enabled INT NOT NULL, "
            + "update_time TIMESTAMP, PRIMARY KEY (id))";

    private static final String CREATE_DM_METRIC_TABLE = "CREATE TABLE t_metric_latest ("
            + "metric_code VARCHAR(32) NOT NULL, metric_name VARCHAR(64), current_value DOUBLE, "
            + "unit VARCHAR(16), update_time TIMESTAMP, PRIMARY KEY (metric_code))";

    private final JdbcTemplate taosJdbcTemplate;
    private final JdbcTemplate dmJdbcTemplate;
    private final CollectorConfigMapper collectorConfigMapper;
    private final MetricLatestMapper metricLatestMapper;
    private final TdengineDataSourceProperties tdengineProperties;

    public SchemaInitializer(@Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate,
                             @Qualifier("dmJdbcTemplate") JdbcTemplate dmJdbcTemplate,
                             CollectorConfigMapper collectorConfigMapper,
                             MetricLatestMapper metricLatestMapper,
                             TdengineDataSourceProperties tdengineProperties) {
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.dmJdbcTemplate = dmJdbcTemplate;
        this.collectorConfigMapper = collectorConfigMapper;
        this.metricLatestMapper = metricLatestMapper;
        this.tdengineProperties = tdengineProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        initTdengine();
        initDm8();
    }

    private void initTdengine() {
        ensureDatabase();
        taosJdbcTemplate.execute(CREATE_STABLE);
        String host = TdengineTableNames.localHostName();
        String subTable = TdengineTableNames.subTable(host);
        taosJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + subTable
                + " USING t_perf_realtime TAGS ('" + host + "')");
        log.info("TDengine schema ready: supertable t_perf_realtime, subtable {} (host {})", subTable, host);
    }

    /**
     * The configured JDBC URL points at the {@code kms} database, so it cannot
     * be used to create that database itself. Open a raw server-level
     * connection (URL without the database path) for the one-off DDL.
     */
    private void ensureDatabase() {
        String url = tdengineProperties.getUrl();
        int queryIndex = url.indexOf('?');
        String query = queryIndex >= 0 ? url.substring(queryIndex) : "";
        String base = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        String serverUrl = base.substring(0, base.lastIndexOf('/')) + query;
        try {
            Class.forName(tdengineProperties.getDriverClassName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("TDengine driver not found: "
                    + tdengineProperties.getDriverClassName(), e);
        }
        try (Connection connection = DriverManager.getConnection(
                serverUrl, tdengineProperties.getUsername(), tdengineProperties.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS kms");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to ensure TDengine database 'kms'", e);
        }
    }

    private void initDm8() {
        // DM8 has no CREATE TABLE IF NOT EXISTS: check USER_TABLES first (names are uppercase).
        Integer configTableCount = dmJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = 'T_COLLECTOR_CONFIG'", Integer.class);
        if (configTableCount == null || configTableCount == 0) {
            dmJdbcTemplate.execute(CREATE_DM_CONFIG_TABLE);
        }
        Integer metricTableCount = dmJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = 'T_METRIC_LATEST'", Integer.class);
        if (metricTableCount == null || metricTableCount == 0) {
            dmJdbcTemplate.execute(CREATE_DM_METRIC_TABLE);
        }

        // Seed the single config row.
        if (collectorConfigMapper.selectById(1) == null) {
            CollectorConfig config = new CollectorConfig();
            config.setId(1);
            config.setPeriodSeconds(60);
            config.setEnabled(1);
            config.setUpdateTime(LocalDateTime.now());
            collectorConfigMapper.insert(config);
        }

        // Seed the six metric rows.
        for (String[] def : new String[][]{
                {"cpu_usage", "CPU Usage", "%"},
                {"mem_usage", "Memory Usage", "%"},
                {"disk_free", "Disk Free Space", "GB"},
                {"net_send", "Network Send Rate", "KB/s"},
                {"net_recv", "Network Receive Rate", "KB/s"},
                {"cpu_temp", "CPU Temperature", "C"}}) {
            if (metricLatestMapper.selectById(def[0]) == null) {
                MetricLatest row = new MetricLatest();
                row.setMetricCode(def[0]);
                row.setMetricName(def[1]);
                row.setUnit(def[2]);
                metricLatestMapper.insert(row);
            }
        }
        log.info("DM8 schema ready: t_collector_config and t_metric_latest verified/created");
    }
}
