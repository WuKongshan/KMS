package com.kms.database.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TDengine (time-series datasource) connection settings, bound from the
 * {@code kms.datasource.tdengine.*} keys in application.yml.
 *
 * <p>【中文】TDengine（时序数据源）的连接配置，绑定 application.yml 中
 * {@code kms.datasource.tdengine.*} 前缀下的配置项。
 *
 * <p>Default protocol is WebSocket ({@code jdbc:TAOS-WS://localhost:6041}) —
 * pure Java, works without the TDengine C client. The native protocol
 * ({@code jdbc:TAOS://localhost:6030}) requires taos.dll/libtaos.so on the
 * host; the REST fallback is {@code jdbc:TAOS-RS://localhost:6041}. The
 * {@code ?timezone=Asia/Shanghai} parameter is mandatory (container timezone
 * differs from the host — see CLAUDE.md).
 *
 * <p>【中文】默认协议为 WebSocket（{@code jdbc:TAOS-WS://localhost:6041}）——纯 Java 实现，
 * 宿主机无需安装 TDengine C 客户端；原生协议（{@code jdbc:TAOS://localhost:6030}）需要
 * 宿主机具备 taos.dll/libtaos.so（本机未安装）；REST 备选协议为
 * {@code jdbc:TAOS-RS://localhost:6041}。{@code ?timezone=Asia/Shanghai} 为必填参数
 * ——容器时区与宿主机不同（见 CLAUDE.md），缺失会使写入/查询的时间戳整体偏移。
 */
@ConfigurationProperties(prefix = "kms.datasource.tdengine")
public class TdengineDataSourceProperties {

    /**
     * JDBC driver class, e.g. {@code com.taosdata.jdbc.ws.WebSocketDriver}.
     *
     * <p>【中文】JDBC 驱动类名。选择该驱动必须与 URL 协议一致：
     * {@code jdbc:TAOS-WS} 对应 {@code com.taosdata.jdbc.ws.WebSocketDriver}，
     * 若切到 TAOS/TAOS-RS 协议需同步换成对应的 Driver 实现。
     */
    private String driverClassName;

    /**
     * JDBC URL, e.g. {@code jdbc:TAOS-WS://localhost:6041/?timezone=Asia/Shanghai}.
     *
     * <p>【中文】JDBC 连接串，必须携带 {@code ?timezone=Asia/Shanghai}：
     * TDengine 容器时区与宿主机不一致，缺少该参数会导致写入/查询的时间戳整体偏移。
     */
    private String url;

    /**
     * Login user, e.g. {@code root}.
     *
     * <p>【中文】登录用户名，TDengine 默认超级用户为 {@code root}（可由 TDENGINE_USER 环境变量覆盖）。
     */
    private String username;

    /**
     * Login password.
     *
     * <p>【中文】登录密码，默认 {@code taosdata}，通过 TDENGINE_PASSWORD 环境变量注入，避免硬编码。
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
