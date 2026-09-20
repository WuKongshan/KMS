package com.kms.web.security;

import com.kms.database.security.KmsJwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the bearer token from the {@code Authorization} header and, when valid,
 * puts the authenticated user into the security context.
 *
 * <p>【中文】从 {@code Authorization: Bearer <token>} 头里取出令牌，
 * 校验通过后把用户身份放进 Spring Security 的上下文，后续的授权规则（{@code .authenticated()}）
 * 就能根据上下文判断这次请求是否放行。
 *
 * <p>【中文】校验失败时<b>不直接返回 401、也不打断请求</b>，而是"什么都不做"继续走过滤器链：
 * 让 Security 的授权规则去决定放行还是拒绝。这样白名单里的接口（如登录接口）
 * 即使带着一个过期令牌也照样能访问，错误处理只有一处（SecurityConfig 的 entry point），
 * 不会出现"过滤器返回 401、又被 Security 覆盖成另一个状态码"的混乱。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    // 标准的 Bearer 前缀，大小写不敏感（按 RFC 6750 实现）
    private static final String BEARER_PREFIX = "Bearer ";
    // 令牌里没有角色时的兜底角色：给最小权限，而不是默认给管理员
    private static final String DEFAULT_ROLE = "VIEWER";

    private final KmsJwtService jwtService;

    public JwtAuthFilter(KmsJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            // 校验通过才设置认证信息；失败则保持匿名，由授权规则拒绝
            jwtService.verify(token).ifPresent(principal -> {
                String role = principal.role() == null ? DEFAULT_ROLE : principal.role();
                // Spring Security 约定：角色权限需带 ROLE_ 前缀，@PreAuthorize("hasRole('ADMIN')") 才能匹配
                List<SimpleGrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_" + role));
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal.username(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }

    // 取 Authorization 头里的 Bearer 令牌；缺失或格式不符返回 null
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
