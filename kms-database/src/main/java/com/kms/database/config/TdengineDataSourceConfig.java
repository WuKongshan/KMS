package com.kms.database.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * TDengine secondary datasource: real-time time-series data (spec 4.1,
 * module B). No {@link org.springframework.context.annotation.Primary} here —
 * MyBatis-Plus and the transaction manager must bind to DM8 instead.
 *
 * <p>【中文】TDengine 从数据源：承载实时时序数据（规格 4.1 的模块 B）。
 * 此处刻意不加 {@link org.springframework.context.annotation.Primary}——
 * 否则 MyBatis-Plus 与事务管理器会绑定到 TDengine 而非达梦主库。
 *
 * <p>SQL against {@code t_perf_realtime} goes through {@code taosJdbcTemplate};
 * schema/DDL management is done with raw SQL (TDengine has no MyBatis-Plus
 * dialect, and its SQL subset is small).
 *
 * <p>【中文】针对 {@code t_perf_realtime} 的读写统一走 {@code taosJdbcTemplate}；
 * 建库、建超级表/子表等 DDL 也使用原生 SQL——TDengine 没有 MyBatis-Plus 方言，
 * 且其 SQL 子集很小，直接用 JdbcTemplate 更简单可控。
 */
@Configuration
@EnableConfigurationProperties(TdengineDataSourceProperties.class)
public class TdengineDataSourceConfig {

    @Bean(name = "taosDataSource")
    public DataSource taosDataSource(TdengineDataSourceProperties props) {
        // 与达梦池并列的第二个连接池；本 Bean 未标注 @Primary，
        // 因此 DataSource 注入点必须显式写 @Qualifier("taosDataSource")，
        // 否则不会报错，而是静默拿到 @Primary 的达梦池（连错库）
        HikariDataSource ds = new HikariDataSource();
        // 池名与达梦池（kms-dm8）区分开，便于在日志中定位时序库的连接/超时问题
        ds.setPoolName("kms-taos");
        ds.setDriverClassName(props.getDriverClassName());
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        return ds;
    }

    /**
     * Time-series access for all modules: collector writes, analyser/web read.
     *
     * <p>【中文】面向所有模块的时序数据访问入口：采集模块写入，分析与 Web 模块读取。
     * 注入时必须写 {@code @Qualifier("taosJdbcTemplate")}——容器中同时存在达梦的
     * {@code dmJdbcTemplate}，不限定会因类型歧义启动失败。
     * 注意：该 JdbcTemplate 不走 MyBatis-Plus，分页/方言需在 SQL 中自行处理。
     */
    @Bean(name = "taosJdbcTemplate")
    public JdbcTemplate taosJdbcTemplate(@Qualifier("taosDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
