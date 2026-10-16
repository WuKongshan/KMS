package com.kms.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kms.database.entity.SysUser;
import com.kms.database.mapper.SysUserMapper;
import com.kms.database.security.KmsJwtService;
import com.kms.web.annotation.OperLog;
import com.kms.web.dto.LoginRequest;
import com.kms.web.dto.LoginResponse;
import com.kms.web.dto.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Login endpoints: sign in, self-register, read the current user, and a no-op logout.
 *
 * <p>【中文】认证相关接口：登录、注册、查询当前用户、退出登录。
 * <p>【中文】除登录与注册接口外，本控制器其余接口都要求已认证（由 SecurityConfig 统一保护）。
 * 退出登录在无状态令牌下没有服务端动作——令牌无法"作废"，由前端丢弃即可；
 * 之所以仍提供接口，是为了给前端一个统一调用点，将来若要加黑名单/审计，改这里即可。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证鉴权", description = "登录、注册、查询当前用户、退出登录。除登录与注册外均需 Bearer 令牌。")
@SecurityRequirement(name = "bearerAuth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    // 用户名规则：3~20 位字母/数字/下划线/中文（中文用户名在 UTF-8 库中可正常存储与检索）
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]{3,20}$");
    // 密码最小长度：演示环境取 6；生产建议 8 位以上并叠加复杂度校验
    private static final int MIN_PASSWORD_LENGTH = 6;
    // 注册账号的角色固定为只读：绝不接受客户端指定角色（否则注册即可提权为 ADMIN）
    private static final String DEFAULT_REGISTER_ROLE = "VIEWER";

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final KmsJwtService jwtService;
    // 注册开关：生产环境可在 application.yml 里置 false，只允许管理员后台开号
    private final boolean registrationEnabled;

    public AuthController(SysUserMapper sysUserMapper,
                          PasswordEncoder passwordEncoder,
                          KmsJwtService jwtService,
                          @Value("${kms.security.registration-enabled:true}") boolean registrationEnabled) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.registrationEnabled = registrationEnabled;
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
    @Operation(summary = "登录获取令牌",
            description = "校验用户名与密码（BCrypt 比对）后签发 JWT。参数为空返回 400；"
                    + "用户名或密码错误返回 401（不区分二者，避免账号枚举）；账号停用返回 403。")
    @SecurityRequirements
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
     * Self-registration: create a read-only account and sign in immediately.
     *
     * <p>【中文】开放注册：创建只读账号并直接签发令牌（注册即登录，前端无需再调一次登录接口）。
     * <p>【中文】三条安全约束：
     * ① <b>角色强制 VIEWER</b>——请求体里的 role 字段会被忽略，防止注册时自选管理员提权；
     * ② 用户名唯一由"先查重 + 数据库唯一约束"双重保证（并发撞车时把约束异常翻译成 400 而非 500）；
     * ③ 可由 {@code kms.security.registration-enabled=false} 一键关闭注册（关闭后返回 403）。
     * <p>【中文】审计描述刻意写成静态文本：{@code @OperLog} 的 {@code {0}} 占位符会把参数对象
     * 转成字符串，而注册请求体里含明文密码，写进审计描述等于泄露凭证；
     * 参数明细走 request_params 列，那里的 password 已被切面正则打码为 ***。
     */
    @PostMapping("/register")
    @OperLog(module = "认证", type = "REGISTER", desc = "注册新账号（角色固定为 VIEWER）")
    @Operation(summary = "注册新账号（角色固定为只读）",
            description = "开放注册：创建账号并**直接签发令牌**（注册即登录，无需再调登录接口）。\n\n")
    @SecurityRequirements
    public LoginResponse register(@RequestBody RegisterRequest request) {
        if (!registrationEnabled) {
            // 关闭注册是"功能不可用"而非"未认证"，因此用 403 而不是 401
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "注册功能已关闭，请联系管理员开通账号");
        }
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();

        // 1) 本地校验：格式、强度都在查库之前完成，避免用脏数据打扰数据库
        if (username.isEmpty() || password.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名与密码不能为空");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名需为 3~20 位字母、数字、下划线或中文");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }

        // 2) 查重：给出友好提示；数据库唯一约束才是并发下的最终防线
        Long existing = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null && existing > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名已存在: " + username);
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        // 只存 BCrypt 哈希，永不落明文
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                ? username : request.getNickname().trim());
        user.setRole(DEFAULT_REGISTER_ROLE);
        user.setEnabled(1);
        LocalDateTime now = LocalDateTime.now();
        user.setCreateTime(now);
        user.setUpdateTime(now);
        try {
            sysUserMapper.insert(user);
        } catch (DataIntegrityViolationException e) {
            // 两个请求同时注册同名账号时，先查重会双双通过，最终由唯一约束拦下：
            // 这里把约束冲突翻译成 400（客户端输入问题），而不是让它冒成 500
            log.warn("Concurrent registration collision for username '{}'", username);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名已存在: " + username);
        }
        log.info("Registered new account: {} (role {})", username, DEFAULT_REGISTER_ROLE);

        // 3) 注册即登录：直接签发令牌，前端拿到的响应结构与登录接口完全一致
        LoginResponse response = new LoginResponse();
        response.setToken(jwtService.issue(user.getUsername(), DEFAULT_REGISTER_ROLE));
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setRole(DEFAULT_REGISTER_ROLE);
        return response;
    }

    /**
     * Current user info (the frontend uses it to restore the session after a refresh).
     *
     * <p>【中文】当前登录用户信息：前端刷新页面后用它恢复会话状态
     * （用户名、角色），避免把敏感信息长期存在 localStorage 里。
     */
    @GetMapping("/me")
    @Operation(summary = "当前登录用户",
            description = "返回用户名与角色，前端刷新页面后用它恢复登录态，避免把角色长期存在浏览器里。")
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
    @Operation(summary = "退出登录",
            description = "无状态令牌下服务端不维护会话，此接口只写审计日志、不作废令牌；"
                    + "前端需自行清除本地令牌（将来若要加黑名单，改这一处即可）。")
    public Map<String, Object> logout() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }
}
