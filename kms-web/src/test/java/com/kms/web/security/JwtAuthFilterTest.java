package com.kms.web.security;

import com.kms.database.security.KmsJwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the JWT filter (no Spring context): it must populate the
 * security context for a valid token and leave it untouched otherwise.
 *
 * <p>【中文】JWT 过滤器的单元测试（不启动 Spring 容器）：有效令牌要写入认证上下文，
 * 其余情况（无令牌、格式错误、伪造令牌）必须保持匿名——不能出现"解析失败却仍被当成已登录"。
 */
class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789-abcdef";

    private final KmsJwtService jwtService = new KmsJwtService(SECRET, 120);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService);

    @AfterEach
    void clearContext() {
        // SecurityContextHolder 默认用 ThreadLocal，测试线程复用会串数据，必须清理
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenPopulatesAuthenticationWithRole() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtService.issue("admin", "ADMIN"));

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("admin", auth.getName());
        // 权限必须是 ROLE_ 前缀，@PreAuthorize("hasRole('ADMIN')") 才能匹配
        assertEquals("ROLE_ADMIN", auth.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void missingHeaderLeavesContextAnonymous() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void malformedAndForgedTokensLeaveContextAnonymous() throws Exception {
        // 缺 Bearer 前缀
        MockHttpServletRequest noPrefix = new MockHttpServletRequest();
        noPrefix.addHeader("Authorization", jwtService.issue("admin", "ADMIN"));
        filter.doFilter(noPrefix, new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        // 伪造令牌（签名不匹配）
        MockHttpServletRequest forged = new MockHttpServletRequest();
        forged.addHeader("Authorization", "Bearer " + new KmsJwtService(
                "another-secret-9876543210-9876543210-abcdefg", 120).issue("hacker", "ADMIN"));
        filter.doFilter(forged, new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        // 空令牌
        MockHttpServletRequest empty = new MockHttpServletRequest();
        empty.addHeader("Authorization", "Bearer ");
        filter.doFilter(empty, new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenWithoutRoleFallsBackToViewer() throws Exception {
        // role 缺失（旧令牌/异常数据）时必须给最小权限，而不是默认放行管理员
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtService.issue("someone", null));

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("ROLE_VIEWER", auth.getAuthorities().iterator().next().getAuthority());
    }
}
