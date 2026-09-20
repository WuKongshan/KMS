package com.kms.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kms.database.entity.SysUser;
import com.kms.database.mapper.SysUserMapper;
import com.kms.database.security.KmsJwtService;
import com.kms.web.annotation.OperLog;
import com.kms.web.dto.LoginRequest;
import com.kms.web.dto.LoginResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Login endpoints: sign in, read the current user, and a no-op logout.
 *
 * <p>【中文】登录相关接口：登录、查询当前用户、退出登录。
 * <p>【中文】除登录接口外，本控制器其余接口都要求已认证（由 SecurityConfig 统一保护）。
 * 退出登录在无状态令牌下没有服务端动作——令牌无法"作废"，由前端丢弃即可；
 * 之所以仍提供接口，是为了给前端一个统一调用点，将来若要加黑名单/审计，改这里即可。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final KmsJwtService jwtService;

    public AuthController(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder, KmsJwtService jwtService) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Sign in with username + password, returns a JWT.
     *
     * <p>【中文】登录：校验用户名与密码（BCrypt 比对），成功后签发 JWT。
     * <p>【中文】用户名不存在与密码错误返回<b>同一个</b> 401 提示：
     * 分开提示等于免费告诉攻击者"这个用户名是存在的"，便于撞库枚举。
     */
    @PostMapping("/login")
    @OperLog(module = "认证", type = "LOGIN", desc = "登录系统")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();
        if (username.isEmpty() || password.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名与密码不能为空");
        }

        // 按用户名精确查询（参数化查询，无注入风险）
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            // 只记用户名不记密码：日志里出现密码（哪怕是错的）都属违规
            log.warn("Login failed for user '{}'", username);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            log.warn("Login rejected for disabled user '{}'", username);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该账号已被停用");
        }

        String role = user.getRole() == null ? "VIEWER" : user.getRole();
        String token = jwtService.issue(user.getUsername(), role);
        log.info("Login succeeded: {} (role {})", user.getUsername(), role);

        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setRole(role);
        return response;
    }

    /**
     * Current user info (the frontend uses it to restore the session after a refresh).
     *
     * <p>【中文】当前登录用户信息：前端刷新页面后用它恢复会话状态
     * （用户名、角色），避免把敏感信息长期存在 localStorage 里。
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (authentication == null) {
            // 到得了这里说明令牌有效但上下文为空（理论上不会发生），返回 401 更诚实
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        }
        result.put("username", authentication.getName());
        result.put("role", authentication.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse("VIEWER"));
        return result;
    }

    /**
     * Logout is client-side (drop the token); kept as an endpoint for a single call point.
     *
     * <p>【中文】退出登录：无状态令牌下服务端无事可做（前端丢弃令牌即可），
     * 保留接口是为了给前端一个统一的调用点。
     */
    @PostMapping("/logout")
    @OperLog(module = "认证", type = "LOGOUT", desc = "退出登录")
    public Map<String, Object> logout() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }
}
