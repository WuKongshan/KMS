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
 *
 * <p>【中文】启动时创建/校验 KMS 表结构（幂等，每次启动执行都安全）。
 * TDengine 侧：{@code kms} 库 + 超级表 + 本机子表；达梦侧：t_collector_config /
 * t_metric_latest 两张表及其种子数据。通过 {@code @Order(1)} 早于采集循环执行
 * （{@link CollectionLoop} 为 {@code @Order(2)}），保证第一轮采集时表已就绪。
 * 规范 DDL 见 kms-database/src/main/resources/sql/*.sql。
 */
@Component
@Order(1)
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    // Note: "host" is a TDengine keyword, so the tag is named host_name.
    // 注意："host" 是 TDengine 的关键字，因此标签列取名 host_name 以避免语法冲突
    // 超级表定义：ts 为时间戳主列，6 个 DOUBLE 指标列，host_name 为标签（用于区分不同宿主机）
    private static final String CREATE_STABLE = "CREATE STABLE IF NOT EXISTS t_perf_realtime ("
            + "ts TIMESTAMP, cpu_usage DOUBLE, mem_usage DOUBLE, disk_free DOUBLE, "
            + "net_send DOUBLE, net_recv DOUBLE, cpu_temp DOUBLE) TAGS (host_name VARCHAR(64))";

    // 达梦采集配置表：单行配置（id 固定为 1），周期与启停标志都由它驱动，无需重启
    private static final String CREATE_DM_CONFIG_TABLE = "CREATE TABLE t_collector_config ("
            + "id INT NOT NULL, period_seconds INT NOT NULL, enabled INT NOT NULL, "
            + "update_time TIMESTAMP, PRIMARY KEY (id))";

    // 达梦指标当前值表：metric_code 为主键，页面读它获得最新值，避免每次查时序库
    private static final String CREATE_DM_METRIC_TABLE = "CREATE TABLE t_metric_latest ("
            + "metric_code VARCHAR(32) NOT NULL, metric_name VARCHAR(64), current_value DOUBLE, "
            + "unit VARCHAR(16), update_time TIMESTAMP, PRIMARY KEY (metric_code))";

    private final JdbcTemplate taosJdbcTemplate;
    private final JdbcTemplate dmJdbcTemplate;
    private final CollectorConfigMapper collectorConfigMapper;
    private final MetricLatestMapper metricLatestMapper;
    private final TdengineDataSourceProperties tdengineProperties;

    // 两个 JdbcTemplate 靠 @Qualifier 区分：taosJdbcTemplate 连 TDengine，dmJdbcTemplate 连达梦
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

    // ApplicationRunner 回调：容器启动完成后执行，先建 TDengine 再建达梦，顺序不可颠倒
    @Override
    public void run(ApplicationArguments args) {
        initTdengine();
        initDm8();
    }

    // TDengine 侧初始化：先确保库存在，再建超级表，最后为本机建子表
    private void initTdengine() {
        // 必须先建库：配置的 JDBC URL 里带了库名，库不存在时后面的 JdbcTemplate 根本连不上
        ensureDatabase();
        taosJdbcTemplate.execute(CREATE_STABLE);
        String host = TdengineTableNames.localHostName();
        // 子表名由主机名派生（内部已做安全化），多宿主机共用同一超级表、各写各的子表
        String subTable = TdengineTableNames.subTable(host);
        taosJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + subTable
                + " USING t_perf_realtime TAGS ('" + host + "')");
        log.info("TDengine schema ready: supertable t_perf_realtime, subtable {} (host {})", subTable, host);
    }

    /**
     * The configured JDBC URL points at the {@code kms} database, so it cannot
     * be used to create that database itself. Open a raw server-level
     * connection (URL without the database path) for the one-off DDL.
     *
     * <p>【中文】配置的 JDBC URL 指向 {@code kms} 库，因此无法用它来创建这个库本身
     * （先有鸡还是先有蛋的问题：库不存在时连接建立就会失败）。这里用原生
     * DriverManager 打开一条“服务器级”连接（URL 去掉库名部分），只执行一次建库 DDL。
     */
    private void ensureDatabase() {
        String url = tdengineProperties.getUrl();
        // 拆出查询串（?timezone=Asia/Shanghai），建库连接同样需要它保持时区一致
        int queryIndex = url.indexOf('?');
        String query = queryIndex >= 0 ? url.substring(queryIndex) : "";
        // base 是去掉查询串的部分，例如 jdbc:TAOS-WS://localhost:6041/kms
        String base = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        // 砍掉最后一段库名（最后一个 '/' 之后的内容），再拼回查询串，得到服务器级 URL
        String serverUrl = base.substring(0, base.lastIndexOf('/')) + query;
        try {
            // 手动加载驱动：此处绕开 Spring 的连接池与自动配置，直接使用 DriverManager
            Class.forName(tdengineProperties.getDriverClassName());
        } catch (ClassNotFoundException e) {
            // 驱动缺失直接 fail-fast，避免拖到真正写数据时才暴露问题
            throw new IllegalStateException("TDengine driver not found: "
                    + tdengineProperties.getDriverClassName(), e);
        }
        try (Connection connection = DriverManager.getConnection(
                serverUrl, tdengineProperties.getUsername(), tdengineProperties.getPassword());
             Statement statement = connection.createStatement()) {
            // IF NOT EXISTS 保证幂等：库已存在时该语句是空操作
            statement.execute("CREATE DATABASE IF NOT EXISTS kms");
        } catch (java.sql.SQLException e) {
            // 建库失败属于致命错误：后续建表与写入都不可能成功，直接终止启动
            throw new IllegalStateException("Failed to ensure TDengine database 'kms'", e);
        }
    }

    // 达梦侧初始化：两张表 + 种子数据，全部幂等，重启不会重复插入
    private void initDm8() {
        // DM8 has no CREATE TABLE IF NOT EXISTS: check USER_TABLES first (names are uppercase).
        // 达梦不支持 CREATE TABLE IF NOT EXISTS：先查 USER_TABLES 判断是否已存在（表名在数据字典中为大写）
        Integer configTableCount = dmJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = 'T_COLLECTOR_CONFIG'", Integer.class);
        // count 为 null 或 0 都视为表不存在，需要建表
        if (configTableCount == null || configTableCount == 0) {
            dmJdbcTemplate.execute(CREATE_DM_CONFIG_TABLE);
        }
        Integer metricTableCount = dmJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = 'T_METRIC_LATEST'", Integer.class);
        if (metricTableCount == null || metricTableCount == 0) {
            dmJdbcTemplate.execute(CREATE_DM_METRIC_TABLE);
        }

        // Seed the single config row.
        // 写入单行采集配置（id=1）：默认周期 60 秒、启用采集，仅在缺失时插入以免覆盖用户修改
        if (collectorConfigMapper.selectById(1) == null) {
            CollectorConfig config = new CollectorConfig();
            config.setId(1);
            config.setPeriodSeconds(60);
            config.setEnabled(1);
            config.setUpdateTime(LocalDateTime.now());
            collectorConfigMapper.insert(config);
        }

        // Seed the six metric rows.
        // 写入 6 个指标的元数据行：编码与 t_metric_latest 主键一致，分析模块与前端都按编码取值
        for (String[] def : new String[][]{
                {"cpu_usage", "CPU Usage", "%"},
                {"mem_usage", "Memory Usage", "%"},
                {"disk_free", "Disk Free Space", "GB"},
                {"net_send", "Network Send Rate", "KB/s"},
                {"net_recv", "Network Receive Rate", "KB/s"},
                {"cpu_temp", "CPU Temperature", "C"}}) {
            // 幂等：仅当该指标行缺失时插入，不覆盖已有的当前值
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
