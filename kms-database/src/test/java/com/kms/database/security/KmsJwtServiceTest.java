package com.kms.database.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the JWT issuer/verifier (no Spring context needed).
 *
 * <p>【中文】JWT 签发与校验的单元测试（不需要 Spring 容器）。
 * 重点覆盖四类"必须拒绝"的情况：密钥不一致、令牌被篡改、令牌过期、密钥过短；
 * 以及两件正常路径：签发后能校验出正确的用户名与角色。
 */
class KmsJwtServiceTest {

    // 测试用密钥，长度 ≥ 32 字节（HS256 的硬性要求）
    private static final String SECRET = "unit-test-secret-0123456789-0123456789-abcdef";
    private static final String OTHER_SECRET = "another-secret-9876543210-9876543210-abcdefg";

    @Test
    void issuedTokenVerifiesWithUsernameAndRole() {
        KmsJwtService service = new KmsJwtService(SECRET, 120);

        String token = service.issue("admin", "ADMIN");
        Optional<KmsJwtService.JwtPrincipal> principal = service.verify(token);

        assertTrue(principal.isPresent());
        assertEquals("admin", principal.get().username());
        assertEquals("ADMIN", principal.get().role());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        // 模拟"签发的模块与校验的模块密钥不一致"——这是多进程部署最容易踩的坑
        String token = new KmsJwtService(OTHER_SECRET, 120).issue("admin", "ADMIN");

        assertTrue(new KmsJwtService(SECRET, 120).verify(token).isEmpty());
    }

    @Test
    void tamperedTokenIsRejected() {
        KmsJwtService service = new KmsJwtService(SECRET, 120);
        String token = service.issue("viewer", "VIEWER");

        // 改掉签名部分的最后一个字符：签名不再匹配，必须拒绝（否则等于可自行提权）
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertTrue(service.verify(tampered).isEmpty());
    }

    @Test
    void expiredTokenIsRejected() {
        KmsJwtService service = new KmsJwtService(SECRET, 120);
        // 用同一密钥手工构造一个"1 小时前就已过期"的令牌：
        // 本服务只签发未来有效的令牌，因此过期场景只能在测试里自己造。
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("admin")
                .claim("role", "ADMIN")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(key)
                .compact();

        assertTrue(service.verify(expired).isEmpty());
    }

    @Test
    void freshlyIssuedTokenIsAcceptedWithinItsValidityWindow() {
        KmsJwtService service = new KmsJwtService(SECRET, 1);
        String token = service.issue("admin", "ADMIN");

        assertTrue(service.verify(token).isPresent());
    }

    @Test
    void nullEmptyAndGarbageTokensAreRejected() {
        KmsJwtService service = new KmsJwtService(SECRET, 120);

        assertTrue(service.verify(null).isEmpty());
        assertTrue(service.verify("").isEmpty());
        assertTrue(service.verify("   ").isEmpty());
        assertTrue(service.verify("not-a-jwt").isEmpty());
        assertTrue(service.verify("a.b.c").isEmpty());
    }

    @Test
    void shortSecretIsRejectedAtStartup() {
        // 密钥过短必须 fail-fast：带病启动的后果是签名可被暴力破解
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new KmsJwtService("too-short", 120));
        assertTrue(ex.getMessage().contains("32 bytes"));
    }

    @Test
    void defaultSecretIsAcceptedButWarned() {
        // 默认密钥必须可用（本地开箱即跑），同时构造器会打印 WARN 提醒生产替换
        KmsJwtService service = new KmsJwtService(KmsJwtService.DEFAULT_SECRET, 120);
        String token = service.issue("admin", "ADMIN");

        assertFalse(service.verify(token).isEmpty());
    }
}
