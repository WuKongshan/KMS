package com.kms.analyser.config;

import com.kms.database.security.KmsJwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT bean for the analyser (used to validate the WebSocket handshake token).
 *
 * <p>【中文】分析器侧的 JWT Bean（用于校验 WebSocket 握手令牌）。
 *
 * <p>【中文】为什么单独一个配置类而不是放在 AlertWebSocketConfig 里：
 * AlertWebSocketConfig 的构造参数需要 {@link com.kms.analyser.service.WebSocketAuthInterceptor}，
 * 而拦截器又需要 KmsJwtService；若把 KmsJwtService 的 @Bean 也写在 AlertWebSocketConfig 内，
 * 就形成"A 依赖 B、B 又依赖 A 内部定义的 Bean"的循环依赖，Spring Boot 3 默认直接启动失败
 * （真机启动时确实报了这个错）。拆到独立的配置类后依赖变成单向：
 * AnalyserSecurityConfig → WebSocketAuthInterceptor → AlertWebSocketConfig。
 *
 * <p>【中文】密钥必须与 Web 模块一致（同一个 JWT_SECRET 环境变量）；
 * 这里不配置有效期——过期时间写在令牌自身，校验方只认 exp。
 */
@Configuration
public class AnalyserSecurityConfig {

    @Bean
    public KmsJwtService kmsJwtService(
            @Value("${kms.security.jwt-secret:" + KmsJwtService.DEFAULT_SECRET + "}") String secret) {
        // 有效期参数在这里无意义（校验只读令牌里的 exp），给一个占位值即可
        return new KmsJwtService(secret, 120);
    }
}
