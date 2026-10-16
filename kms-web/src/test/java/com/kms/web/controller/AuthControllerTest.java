package com.kms.web.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kms.database.entity.SysUser;
import com.kms.database.mapper.SysUserMapper;
import com.kms.database.security.KmsJwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for sign-in. Standalone MockMvc (no Spring context, no
 * database), so the login rules are verified without any infrastructure.
 *
 * <p>【中文】登录与注册接口的控制器测试。用 standalone MockMvc（不启动 Spring 容器、不连数据库），
 * 在没有环境的情况下也能验证认证规则：
 * 正确密码签发令牌、密码错误 401、账号停用 403、参数缺失 400；
 * 注册成功且角色固定为 VIEWER、用户名重复/过短密码/非法用户名 400、注册关闭 403。
 */
class AuthControllerTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789-abcdef";
    private static final String RAW_PASSWORD = "admin123";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private SysUserMapper mapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mapper = mock(SysUserMapper.class);
        KmsJwtService jwtService = new KmsJwtService(SECRET, 120);
        // 最后一个参数是"注册开关"（true = 允许开放注册）
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(mapper, encoder, jwtService, true))
                .build();
    }

    // 造一个指定启用状态的用户；密码统一哈希成 RAW_PASSWORD
    private SysUser user(int enabled) {
        SysUser u = new SysUser();
        u.setId(1L);
        u.setUsername("admin");
        u.setPassword(encoder.encode(RAW_PASSWORD));
        u.setNickname("系统管理员");
        u.setRole("ADMIN");
        u.setEnabled(enabled);
        u.setCreateTime(LocalDateTime.now());
        return u;
    }

    @Test
    void correctCredentialsReturnTokenAndProfile() throws Exception {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(user(1));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                // 响应体绝不能带上密码哈希
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(user(1));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserIsUnauthorized() throws Exception {
        // 用户不存在与密码错误返回同一个状态码与文案，避免被用来枚举用户名
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserIsForbidden() throws Exception {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(user(0));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankCredentialsAreBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------- 注册 ----------------

    @Test
    void registerCreatesViewerAccountAndReturnsToken() throws Exception {
        // selectCount 未被 stub → Mockito 默认返回 null，走"用户名可用"分支
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zhangsan\",\"password\":\"user123456\",\"nickname\":\"张三\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("zhangsan"))
                .andExpect(jsonPath("$.nickname").value("张三"))
                // 角色必须固定为 VIEWER：注册接口绝不接受客户端提权
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void registerIgnoresClientSuppliedRole() throws Exception {
        // 请求体里塞 role=ADMIN 也应被忽略（DTO 里根本没有 role 字段）
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hacker\",\"password\":\"user123456\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void registerWithDuplicateUsernameIsBadRequest() throws Exception {
        when(mapper.selectCount(any())).thenReturn(1L);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"user123456\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerWithShortPasswordIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lisi\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerWithInvalidUsernameIsBadRequest() throws Exception {
        // 用户名含空格：不符合 3~20 位字母/数字/下划线/中文的规则
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bad name\",\"password\":\"user123456\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerIsForbiddenWhenDisabled() throws Exception {
        // 关闭注册后的实例：应返回 403 而不是 401（功能不可用 ≠ 未认证）
        MockMvc disabled = MockMvcBuilders
                .standaloneSetup(new AuthController(mapper, encoder,
                        new KmsJwtService(SECRET, 120), false))
                .build();

        disabled.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wangwu\",\"password\":\"user123456\"}"))
                .andExpect(status().isForbidden());
    }
}
