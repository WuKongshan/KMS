package com.kms.database.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies the JWT used by the login layer. Lives in the shared
 * module on purpose: the web module (3000) issues tokens, and the analyser
 * (8082) verifies them during the WebSocket handshake — both must use exactly
 * the same signing code and secret, which a single implementation guarantees.
 *
 * <p>【中文】签发与校验登录 JWT。刻意放在共享库（kms-database）里：
 * Web 模块（3000）签发令牌，分析器（8082）在 WebSocket 握手时校验令牌——
 * 一处实现保证两边的签名算法与密钥完全一致（各自复制一份迟早会不一致）。
 *
 * <p>【中文】令牌是无状态（stateless）的：服务端不存会话，只靠签名验证真伪，
 * 因此重启进程不会让用户掉线，也无需引入 Redis 存会话。
 * 代价是无法即时吊销单个令牌——需要"立即踢下线"时，把 {@code jwt-expire-minutes} 调小，
 * 或后续引入令牌黑名单。
 *
 * <p>【中文】本类刻意<b>不加 {@code @Component}</b>：它位于被三个应用共同扫描的共享库里，
 * 若自动注册，采集器（不参与登录体系）也会创建一个用不到的实例并打印密钥相关的启动告警。
 * 需要它的两个模块（web 签发、analyser 校验）各自在配置类里显式声明为 Bean。
 */
public class KmsJwtService {

    private static final Logger log = LoggerFactory.getLogger(KmsJwtService.class);

    /**
     * Development fallback secret. MUST be overridden in production via the
     * JWT_SECRET environment variable (a warning is logged when it is still used).
     *
     * <p>【中文】开发兜底密钥。生产环境必须用 JWT_SECRET 环境变量覆盖——
     * 启动时会检测并打印 WARN 提醒。长度 ≥ 32 字节是 HS256 的硬性要求。
     */
    public static final String DEFAULT_SECRET = "kms-dev-secret-please-override-in-production-0123456789";

    // 自定义声明名：角色（用于接口级鉴权，如只有 ADMIN 能改采集周期）
    private static final String CLAIM_ROLE = "role";

    // HMAC-SHA256 密钥，构造时生成一次并复用（Jwts 每次调用都会用它）
    private final SecretKey key;
    // 令牌有效期（毫秒）
    private final long expireMillis;

    public KmsJwtService(@Value("${kms.security.jwt-secret:" + DEFAULT_SECRET + "}") String secret,
                         @Value("${kms.security.jwt-expire-minutes:120}") long expireMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // 密钥过短会导致 HS256 签名强度不足，直接 fail-fast 而不是带病启动
            throw new IllegalStateException(
                    "kms.security.jwt-secret must be at least 32 bytes (current: " + bytes.length + ")");
        }
        if (DEFAULT_SECRET.equals(secret)) {
            log.warn("Using the DEFAULT JWT secret — set the JWT_SECRET environment variable before production!");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMillis = Math.max(1, expireMinutes) * 60_000L;
        log.info("JWT configured: HMAC-SHA256, expiry {} minute(s)", Math.max(1, expireMinutes));
    }

    /**
     * Issue a token for an authenticated user.
     *
     * <p>【中文】为通过认证的用户签发令牌：subject 存用户名，附加 role 声明，
     * 带上签发时间与过期时间后做 HMAC-SHA256 签名。
     */
    public String issue(String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expireMillis)))
                .signWith(key)
                .compact();
    }

    /**
     * Verify a token's signature and expiry.
     *
     * <p>【中文】校验令牌：签名不对、已过期、格式非法都返回空 Optional（不抛异常）。
     * 调用方（HTTP 过滤器、WebSocket 握手拦截器）只需判断有没有值，
     * 不需要处理各种 JWT 异常类型——把"校验失败"统一收敛成一种结果。
     */
    public Optional<JwtPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new JwtPrincipal(claims.getSubject(), claims.get(CLAIM_ROLE, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            // 只记 debug：伪造/过期令牌是常规噪音，记 error 会把日志刷满
            log.debug("Rejected JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // 令牌里携带的身份信息；role 可能为 null（旧令牌或无角色用户）
    public record JwtPrincipal(String username, String role) {
    }
}
