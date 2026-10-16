package com.kms.web.config;

import com.kms.database.security.KmsJwtService;
import com.kms.web.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Login layer (option A: hand-rolled Spring Security + JWT; no RuoYi code).
 * Every REST call except the login endpoint requires a valid bearer token —
 * the decision was "全部需要登录".
 *
 * <p>【中文】登录与鉴权配置（方案 A：自建 Spring Security + JWT，不引入若依代码）。
 * 除登录接口外，所有 REST 调用都必须携带有效令牌——按需求"全部需要登录"。
 *
 * <p>【中文】三个关键取舍：
 * ① <b>无状态</b>——不走 Session、不用 Cookie，令牌即凭证，因此关掉 CSRF 与表单登录；
 * ② <b>静态资源放行</b>——前端产物（含登录页本身）由同一个 Spring Boot 托管，
 * 若把静态资源也拦下来，登录页都加载不出来，会形成死锁；
 * ③ <b>401 而不是 302</b>——前端的 axios 拦截器按 401 跳登录页；
 * 若返回默认的 302 重定向到登录表单，API 调用方会拿到一坨 HTML 而非明确的未认证信号。
 */
@Configuration
@EnableWebSecurity
// 开启方法级鉴权：让 @PreAuthorize("hasRole('ADMIN')") 生效，
// 把"能改配置/能看日志"这类操作限制在管理员角色（URL 级规则只能区分"登录与否"）
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * URL-level authorization rules.
     *
     * <p>【中文】URL 级授权规则：只放行登录接口与静态资源，其余 {@code /api/**} 全部要求已认证。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                // 无状态 API 不需要 CSRF 令牌：CSRF 攻击依赖浏览器自动携带 Cookie，
                // 而本系统用 Authorization 头传令牌，浏览器不会自动附带，因此天然免疫。
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 登录与注册接口必须放行，否则没人能拿到令牌（注册接口自身在
                        // 关闭注册开关时会返回 403，不需要在这里区分——URL 级只判断"是否需要登录"）
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        // Swagger / OpenAPI 文档必须放行：这些路径不属于 /api/**，但显式列出更稳妥——
                        // 若将来有人把兜底规则收紧为 denyAll，接口文档不会莫名变成 401
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 其余接口一律需要令牌（含查询接口：按"全部需要登录"的要求）
                        .requestMatchers("/api/**").authenticated()
                        // 静态资源（index.html、JS/CSS、登录页）放行；
                        // 它们只是前端代码，不含数据，真正的数据接口在上面被保护。
                        .anyRequest().permitAll())
                // JWT 过滤器挂在用户名密码认证过滤器之前：请求进来先尝试解析令牌
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // 未认证统一返回 401（而不是 302 跳登录页），让前端拿到明确状态码
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "未认证或令牌已失效")));

        return http.build();
    }

    /**
     * BCrypt for password hashing (salted, adaptive).
     *
     * <p>【中文】密码哈希器：BCrypt 自带随机盐且可调计算强度，同一密码每次哈希结果都不同，
     * 因此库里泄露哈希也无法直接反查或撞库对比。登录时用 {@code matches()} 校验。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * JWT service, registered explicitly (the class in kms-database carries no
     * {@code @Component} so the collector does not instantiate it needlessly).
     *
     * <p>【中文】JWT 服务：显式注册为 Bean（共享库里的类刻意没有 {@code @Component}，
     * 避免采集器也创建一个用不到的实例）。
     */
    @Bean
    public KmsJwtService kmsJwtService(
            @Value("${kms.security.jwt-secret:" + KmsJwtService.DEFAULT_SECRET + "}") String secret,
            @Value("${kms.security.jwt-expire-minutes:120}") long expireMinutes) {
        return new KmsJwtService(secret, expireMinutes);
    }
}
