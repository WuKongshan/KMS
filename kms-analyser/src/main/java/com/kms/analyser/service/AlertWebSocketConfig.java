package com.kms.analyser.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the alert push endpoint {@code /ws/alerts} on the analyser's
 * port (8082). All origins are allowed so the frontend (served from the web
 * module on 8080, or from the Vite dev server) can connect directly.
 */
@Configuration
@EnableWebSocket
public class AlertWebSocketConfig implements WebSocketConfigurer {

    private final AlertWebSocketHandler alertWebSocketHandler;

    public AlertWebSocketConfig(AlertWebSocketHandler alertWebSocketHandler) {
        this.alertWebSocketHandler = alertWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(alertWebSocketHandler, "/ws/alerts").setAllowedOrigins("*");
    }
}
