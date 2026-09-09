package com.kms.analyser.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kms.database.entity.AlertRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint backing {@code /ws/alerts} (spec 4.3: alerts are pushed
 * to the frontend in real time). Clients only receive broadcasts; incoming
 * messages are ignored. Alert records are serialized to JSON with a formatted
 * timestamp so the frontend can render them without extra conversion.
 */
@Component
public class AlertWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertWebSocketHandler.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public AlertWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WS client connected: {} (total {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WS client disconnected: {} (total {})", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Ignoring WS message from {}: {}", session.getId(), message.getPayload());
    }

    /** Push a freshly generated alert to every connected frontend. */
    public void broadcast(AlertRecord alert) {
        if (sessions.isEmpty()) {
            log.info("No WS clients connected; alert {} not pushed", alert.getId());
            return;
        }
        String json = toJson(alert);
        for (WebSocketSession session : sessions) {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.warn("WS send to {} failed: {}", session.getId(), e.getMessage());
            }
        }
        log.info("Alert {} pushed to {} WS client(s)", alert.getId(), sessions.size());
    }

    private String toJson(AlertRecord alert) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", alert.getId());
        payload.put("metricCode", alert.getMetricCode());
        payload.put("metricName", alert.getMetricName());
        payload.put("alertLevel", alert.getAlertLevel());
        payload.put("alertType", alert.getAlertType());
        payload.put("message", alert.getMessage());
        payload.put("currentValue", alert.getCurrentValue());
        payload.put("thresholdValue", alert.getThresholdValue());
        payload.put("volatility", alert.getVolatility());
        payload.put("status", alert.getStatus());
        payload.put("createTime", alert.getCreateTime() == null ? null : alert.getCreateTime().format(TIME_FMT));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize alert {}", alert.getId(), e);
            return "{}";
        }
    }
}
