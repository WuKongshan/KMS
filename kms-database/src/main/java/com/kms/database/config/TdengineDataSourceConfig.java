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
 * <p>SQL against {@code t_perf_realtime} goes through {@code taosJdbcTemplate};
 * schema/DDL management is done with raw SQL (TDengine has no MyBatis-Plus
 * dialect, and its SQL subset is small).
 */
@Configuration
@EnableConfigurationProperties(TdengineDataSourceProperties.class)
public class TdengineDataSourceConfig {

    @Bean(name = "taosDataSource")
    public DataSource taosDataSource(TdengineDataSourceProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("kms-taos");
        ds.setDriverClassName(props.getDriverClassName());
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        return ds;
    }

    /** Time-series access for all modules: collector writes, analyser/web read. */
    @Bean(name = "taosJdbcTemplate")
    public JdbcTemplate taosJdbcTemplate(@Qualifier("taosDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
