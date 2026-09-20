package com.kms.analyser.service;

import com.kms.database.security.KmsJwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * Validates the JWT during the WebSocket handshake. The browser talks to the
 * analyser (8082) directly while tokens are issued by the web module (3000),
 * so this interceptor verifies the token <em>statelessly</em> with the shared
 * secret — no call back to the web module, no shared session store.
 *
 * <p>【中文】在 WebSocket 握手阶段校验 JWT。浏览器直连分析器（8082），
 * 而令牌由 Web 模块（3000）签发，因此这里用共享密钥做<b>无状态</b>校验：
 * 不回调查询 Web 模块，也不需要共享会话存储。
 *
 * <p>【中文】为什么令牌走查询串（{@code ?token=...}）而不是请求头：
 * 浏览器原生的 WebSocket 构造函数<b>不支持自定义请求头</b>，
 * 只能把令牌放在 URL 里。副作用是令牌可能出现在代理/网关日志里，
 * 因此这里的令牌有效期不宜过长（见 kms.security.jwt-expire-minutes）。
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    // 与常见做法一致的查询参数名
    private static final String TOKEN_PARAM = "token";

    private final KmsJwtService jwtService;

    public WebSocketAuthInterceptor(KmsJwtService jwtService) {
        this.jwtService = jwtService;
    }

    // 握手前校验：通过返回 true 继续建立连接，失败返回 false 直接拒绝
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request.getURI());
        var principal = jwtService.verify(token);
        if (principal.isEmpty()) {
            // 只记远程地址，不记令牌本身（日志里出现令牌等于泄露凭证）
            log.warn("WS handshake rejected (missing/invalid token) from {}", request.getRemoteAddress());
            return false;
        }
        // 把用户名放进会话属性，处理器后续若需要可按连接区分用户
        attributes.put("username", principal.get().username());
        return true;
    }

    // 握手完成后无额外动作（连接成功的日志由处理器负责）
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    // 从 ?token=xxx 里取令牌；缺失返回 null，交给 verify 统一判为空
    private String extractToken(URI uri) {
        String query = uri.getQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && TOKEN_PARAM.equals(pair.substring(0, idx))) {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }
}
