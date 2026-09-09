package com.kms.database.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DM8 (primary datasource) connection settings, bound from the
 * {@code kms.datasource.dm.*} keys in application.yml.
 *
 * <p>The URL targets the Docker-mapped port {@code jdbc:dm://localhost:30236}
 * (spec 4.1); host/credentials are parameterized via environment variables
 * DB_HOST / DB_USER / DB_PASSWORD so deployments can switch freely (spec 4.4).
 */
@ConfigurationProperties(prefix = "kms.datasource.dm")
public class DmDataSourceProperties {

    /** JDBC driver class, e.g. {@code dm.jdbc.driver.DmDriver}. */
    private String driverClassName;

    /** JDBC URL, e.g. {@code jdbc:dm://localhost:30236}. */
    private String url;

    /** Login user, e.g. {@code SYSDBA}. */
    private String username;

    /** Login password. */
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
