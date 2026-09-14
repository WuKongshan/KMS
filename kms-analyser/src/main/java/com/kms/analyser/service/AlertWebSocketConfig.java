package com.kms.analyser.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the alert push endpoint {@code /ws/alerts} on the analyser's
 * port (8082). All origins are allowed so the frontend (served from the web
 * module on 8080, or from the Vite dev server) can connect directly.
 *
 * <p>【中文】在分析模块的端口（8082）上注册预警推送端点 {@code /ws/alerts}。
 * 放开全部来源是因为前端跑在 Web 模块的 8080（或 Vite 开发服务器）上，
 * 而 WebSocket 连的是 8082，属于跨域请求：浏览器握手时会带上 Origin 头，
 * 不做 {@code setAllowedOrigins("*")} 的话握手会直接被拒绝。
 */
@Configuration
@EnableWebSocket
public class AlertWebSocketConfig implements WebSocketConfigurer {

    private final AlertWebSocketHandler alertWebSocketHandler;

    // 注入唯一的处理器单例，保证所有连接共享同一份在线会话集合
    // （如果每次连接都新建处理器，广播时就找不到其他客户端了）
    public AlertWebSocketConfig(AlertWebSocketHandler alertWebSocketHandler) {
        this.alertWebSocketHandler = alertWebSocketHandler;
    }

    // 把处理器挂到 /ws/alerts 路径上，并放开跨域来源限制：
    // 前端（8080 或 Vite 开发服务器）直连 8082 时属于跨域，Origin 校验必须放行
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(alertWebSocketHandler, "/ws/alerts").setAllowedOrigins("*");
    }
}
