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
 * <p>【中文】达梦 DM8 主数据源：承载元数据与预警数据（规格 4.1 的模块 B）。
 *
 * <p>Marked {@link Primary} so that Spring Boot auto-configuration
 * (MyBatis-Plus {@code SqlSessionFactory}, {@code DataSourceTransactionManager})
 * binds to DM8; TDengine stays secondary and is accessed explicitly via
 * {@code taosJdbcTemplate}. Mappers live in {@code com.kms.database.mapper}.
 *
 * <p>【中文】标注 {@link Primary} 是为了让 Spring Boot 的自动配置（MyBatis-Plus 的
 * {@code SqlSessionFactory}、{@code DataSourceTransactionManager}）绑定到达梦；
 * TDengine 保持为从数据源，只能通过 {@code taosJdbcTemplate} 显式注入访问。
 * Mapper 接口统一位于 {@code com.kms.database.mapper} 包，由本类的 {@code @MapperScan} 扫描注册。
 */
@Configuration
@EnableConfigurationProperties(DmDataSourceProperties.class)
@MapperScan(basePackages = "com.kms.database.mapper")
public class DmDataSourceConfig {

    @Primary
    @Bean(name = "dmDataSource")
    public DataSource dmDataSource(DmDataSourceProperties props) {
        // 这里手工 new HikariDataSource，而不依赖 Spring Boot 的 DataSource 自动配置：
        // 双数据源场景下 spring.datasource.* 只能描述一个库，两个池必须显式声明
        HikariDataSource ds = new HikariDataSource();
        // 池名用于在日志/JMX 中区分达梦池与 TDengine 池；同一 JVM 内应保持唯一，重名会导致 JMX MBean 注册冲突
        ds.setPoolName("kms-dm8");
        ds.setDriverClassName(props.getDriverClassName());
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        return ds;
    }

    /**
     * Direct JDBC access to DM8 (MyBatis-Plus covers most use cases).
     *
     * <p>【中文】达梦的原生 JDBC 访问入口（绝大多数场景由 MyBatis-Plus 覆盖，
     * 这里主要留给建表/初始化等 DDL 与不便用 Mapper 的查询）。
     * 注入时必须写 {@code @Qualifier("dmJdbcTemplate")}——容器中同时存在 TDengine 的
     * {@code taosJdbcTemplate}，不限定会因类型歧义启动失败。
     */
    @Bean(name = "dmJdbcTemplate")
    public JdbcTemplate dmJdbcTemplate(@Qualifier("dmDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
