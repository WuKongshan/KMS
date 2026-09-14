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
 *
 * <p>【中文】{@code /ws/alerts} 端点背后的处理器（规格 4.3：预警要实时推送到前端）。
 * 这是一条"单向广播"通道：客户端只负责接收，发过来的消息一律忽略。
 * 预警记录在这里被手工序列化成 JSON（时间字段提前格式化好），
 * 前端拿到即可直接展示，不需要再做任何转换。
 */
@Component
public class AlertWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertWebSocketHandler.class);

    // 时间格式与 REST 接口保持一致（yyyy-MM-dd HH:mm:ss），
    // 这样 WebSocket 推送的数据和接口查出来的数据在前端可以直接混排展示
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 在线会话集合：连接建立/断开发生在 Tomcat 的 IO 线程，而广播发生在
    // 定时任务线程，两者并发操作这个集合，所以用 ConcurrentHashMap.newKeySet()
    // 而不是普通 HashSet（后者的并发修改会抛 ConcurrentModificationException 或丢元素）
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    // Spring 容器里配置好的 Jackson ObjectMapper，复用它的序列化配置
    private final ObjectMapper objectMapper;

    // 构造注入 ObjectMapper；处理器本身是单例，会话集合在所有连接间共享
    public AlertWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 握手完成即登记会话；日志带上当前在线总数，便于排查"推送没到"类问题
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WS client connected: {} (total {})", session.getId(), sessions.size());
    }

    // 连接关闭（含客户端刷新页面、异常断线）时移除会话，
    // 避免之后每轮广播都往一个已死的连接上重试发送
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WS client disconnected: {} (total {})", session.getId(), sessions.size());
    }

    // 空实现：这是单向广播通道，客户端不需要发消息；
    // 万一收到消息也只记一条 debug 日志，不做任何处理
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Ignoring WS message from {}: {}", session.getId(), message.getPayload());
    }

    /**
     * Push a freshly generated alert to every connected frontend.
     *
     * <p>【中文】把刚生成的预警广播给所有在线前端：先判空提前返回，再只序列化一次 JSON、
     * 遍历会话逐个发送（N 个客户端只付 1 次序列化成本），单个客户端发送失败只记 warn、
     * 不中断其余客户端的推送。
     */
    public void broadcast(AlertRecord alert) {
        if (sessions.isEmpty()) {
            // 没有客户端在线就直接返回，省掉一次没有意义的序列化开销
            log.info("No WS clients connected; alert {} not pushed", alert.getId());
            return;
        }
        // 只序列化一次：json 字符串被所有会话复用，避免 N 个客户端做 N 次 Jackson 序列化
        String json = toJson(alert);
        for (WebSocketSession session : sessions) {
            try {
                // synchronized (session) 是 Spring 的硬性要求：同一个 WebSocketSession
                // 不允许并发 sendMessage，否则会抛 TEXT_PARTIAL_WRITING 之类的状态异常
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                // 单个客户端发送失败（对方刚断网/正在刷新）只记警告不中断循环，
                // 其余客户端照常收到这条推送
                log.warn("WS send to {} failed: {}", session.getId(), e.getMessage());
            }
        }
        log.info("Alert {} pushed to {} WS client(s)", alert.getId(), sessions.size());
    }

    // 手工组装 JSON 而不是直接序列化实体，有两个原因：
    // ① 用 LinkedHashMap 保证字段顺序稳定，便于人工比对与前端调试；
    // ② LocalDateTime 在服务端就格式化成字符串，不依赖前端/Jackson 的时间模块配置
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
        // createTime 为空时给 null，否则格式化成 "yyyy-MM-dd HH:mm:ss"（前端直接显示）
        payload.put("createTime", alert.getCreateTime() == null ? null : alert.getCreateTime().format(TIME_FMT));
        // 序列化失败时返回 "{}" 兜底：宁可推一个空对象出去，
        // 也不能让异常从广播方法里抛出来打断分析循环
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize alert {}", alert.getId(), e);
            return "{}";
        }
    }
}
