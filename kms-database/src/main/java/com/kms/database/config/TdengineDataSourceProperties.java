package com.kms.database.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TDengine (time-series datasource) connection settings, bound from the
 * {@code kms.datasource.tdengine.*} keys in application.yml.
 *
 * <p>Default protocol is WebSocket ({@code jdbc:TAOS-WS://localhost:6041}) —
 * pure Java, works without the TDengine C client. The native protocol
 * ({@code jdbc:TAOS://localhost:6030}) requires taos.dll/libtaos.so on the
 * host; the REST fallback is {@code jdbc:TAOS-RS://localhost:6041}. The
 * {@code ?timezone=Asia/Shanghai} parameter is mandatory (container timezone
 * differs from the host — see CLAUDE.md).
 */
@ConfigurationProperties(prefix = "kms.datasource.tdengine")
public class TdengineDataSourceProperties {

    /** JDBC driver class, e.g. {@code com.taosdata.jdbc.ws.WebSocketDriver}. */
    private String driverClassName;

    /** JDBC URL, e.g. {@code jdbc:TAOS-WS://localhost:6041/?timezone=Asia/Shanghai}. */
    private String url;

    /** Login user, e.g. {@code root}. */
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
