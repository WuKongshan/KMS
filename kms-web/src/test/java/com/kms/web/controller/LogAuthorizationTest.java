package com.kms.web.controller;

import com.kms.web.config.SecurityConfig;
import com.kms.web.service.LogQueryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization tests for the log endpoint: the role carried in the JWT must
 * actually gate access — being authenticated is not enough.
 *
 * <p>【中文】日志接口的授权测试：令牌里的角色必须真正起作用——"已登录"不等于"有权访问"。
 * 这里是安全配置（SecurityConfig + @PreAuthorize）的回归保护：
 * 一旦有人误删方法级注解或把 URL 规则放宽，本测试会立即失败。
 *
 * <p>【中文】只加载 Web 切片并显式引入 SecurityConfig，因此不需要数据库与 Docker：
 * 鉴权与授权是纯 Web 层行为，可以完全离线验证。
 * <p>【中文】注意不要在这里再 {@code @Import(KmsJwtService.class)}——SecurityConfig 已经
 * 通过 @Bean 提供了它，重复注册会出现两个同类型 Bean，导致 JwtAuthFilter 注入时歧义启动失败。
 */
@WebMvcTest(controllers = LogController.class)
@Import({LogQueryService.class, SecurityConfig.class})
class LogAuthorizationTest {

    @TempDir
    static Path logDir;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void logDirProperty(DynamicPropertyRegistry registry) {
        registry.add("kms.web.log-dirs", () -> logDir.toString());
    }

    @BeforeAll
    static void prepareLogFile() throws IOException {
        Files.write(logDir.resolve("kms-analyser.log"),
                List.of("INFO  Analysis cycle started"), StandardCharsets.UTF_8);
    }

    @Test
    void anonymousIsRejected() throws Exception {
        // 未登录：401（而不是 302 跳登录页，前端按 401 处理）
        mockMvc.perform(get("/api/logs")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void viewerIsForbidden() throws Exception {
        // 已登录但角色不够：403（日志属运维敏感数据，仅管理员可看）
        mockMvc.perform(get("/api/logs")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminIsAllowed() throws Exception {
        mockMvc.perform(get("/api/logs")).andExpect(status().isOk());
    }
}
