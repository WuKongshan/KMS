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
 * <p>【中文】登录接口的控制器测试。用 standalone MockMvc（不启动 Spring 容器、不连数据库），
 * 在没有环境的情况下也能验证登录规则：
 * 正确密码签发令牌、密码错误 401、账号停用 403、参数缺失 400。
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
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(mapper, encoder, jwtService))
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
}
