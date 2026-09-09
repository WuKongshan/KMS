package com.kms.database.config;

import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * DM8 primary datasource: metadata and alert data (spec 4.1, module B).
 *
 * <p>Marked {@link Primary} so that Spring Boot auto-configuration
 * (MyBatis-Plus {@code SqlSessionFactory}, {@code DataSourceTransactionManager})
 * binds to DM8; TDengine stays secondary and is accessed explicitly via
 * {@code taosJdbcTemplate}. Mappers live in {@code com.kms.database.mapper}.
 */
@Configuration
@EnableConfigurationProperties(DmDataSourceProperties.class)
@MapperScan(basePackages = "com.kms.database.mapper")
public class DmDataSourceConfig {

    @Primary
    @Bean(name = "dmDataSource")
    public DataSource dmDataSource(DmDataSourceProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("kms-dm8");
        ds.setDriverClassName(props.getDriverClassName());
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        return ds;
    }

    /** Direct JDBC access to DM8 (MyBatis-Plus covers most use cases). */
    @Bean(name = "dmJdbcTemplate")
    public JdbcTemplate dmJdbcTemplate(@Qualifier("dmDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
