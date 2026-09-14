package com.kms.database.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DM8 (primary datasource) connection settings, bound from the
 * {@code kms.datasource.dm.*} keys in application.yml.
 *
 * <p>【中文】达梦 DM8（主数据源）的连接配置，绑定 application.yml 中
 * {@code kms.datasource.dm.*} 前缀下的配置项。
 *
 * <p>The URL targets the Docker-mapped port {@code jdbc:dm://localhost:30236}
 * (spec 4.1); host/credentials are parameterized via environment variables
 * DB_HOST / DB_USER / DB_PASSWORD so deployments can switch freely (spec 4.4).
 *
 * <p>【中文】URL 指向 Docker 映射到宿主机的端口 {@code jdbc:dm://localhost:30236}（规格 4.1）；
 * 主机与账号密码通过环境变量 DB_HOST / DB_USER / DB_PASSWORD 参数化，便于部署环境自由切换（规格 4.4）。
 *
 * <p>【中文】本类本身不含逻辑，仅作为类型安全的配置载体：由
 * {@link DmDataSourceConfig} 上的 {@code @EnableConfigurationProperties} 注册，
 * 因此只有引入 kms-database 模块的应用才会创建该配置对象。
 */
@ConfigurationProperties(prefix = "kms.datasource.dm")
public class DmDataSourceProperties {

    /**
     * JDBC driver class, e.g. {@code dm.jdbc.driver.DmDriver}.
     *
     * <p>【中文】JDBC 驱动类名，达梦 8 为 {@code dm.jdbc.driver.DmDriver}，
     * 由父 pom 中 {@code com.dameng:DmJdbcDriver18} 依赖提供（坐标见 CLAUDE.md）。
     */
    private String driverClassName;

    /**
     * JDBC URL, e.g. {@code jdbc:dm://localhost:30236}.
     *
     * <p>【中文】JDBC 连接串。30236 是 docker-compose 映射到宿主机的端口
     * （容器内实际监听 5236），采集程序运行在宿主机上，故使用 localhost。
     */
    private String url;

    /**
     * Login user, e.g. {@code SYSDBA}.
     *
     * <p>【中文】登录用户名，达梦默认管理员账号为 {@code SYSDBA}（可由 DB_USER 环境变量覆盖）。
     */
    private String username;

    /**
     * Login password.
     *
     * <p>【中文】登录密码，通过 DB_PASSWORD 环境变量注入，不要硬编码进仓库。
     */
    private String password;

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
