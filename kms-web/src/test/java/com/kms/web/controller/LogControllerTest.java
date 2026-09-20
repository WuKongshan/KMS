package com.kms.web.controller;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.kms.database.security.KmsJwtService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for the log viewer endpoint. Web slice only: no database and
 * no Docker containers required, which keeps the test runnable in CI without
 * infrastructure.
 *
 * <p>【中文】日志接口的 HTTP 层测试。只加载 Web 切片（MockMvc），不依赖数据库与 Docker 容器。
 * 验证四件事：业务路由可用、参数默认值生效、未知服务名翻译成 HTTP 400、
 * 返回体字段名与前端约定一致。
 *
 * <p>【中文】为什么 import KmsJwtService 并关掉过滤器（addFilters = false）：
 * 引入 Spring Security 后，{@link com.kms.web.security.JwtAuthFilter} 属于 Web 切片会被装配，
 * 而它依赖的共享库 Bean（无 @Component）不在切片里，不补上会导致上下文加载失败；
 * 鉴权行为本身由 {@code JwtAuthFilterTest} 与 {@code AuthControllerTest} 单独覆盖，
 * 这里只聚焦日志接口本身的契约。
 */
@WebMvcTest(controllers = LogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({com.kms.web.service.LogQueryService.class, KmsJwtService.class})
class LogControllerTest {

    // 静态临时目录：作为被测服务的唯一候选日志目录（静态才能被下面的属性源读取）
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
                List.of("INFO  Analysis cycle started",
                        "WARN  ALERT generated (level 3)",
                        "INFO  done"),
                StandardCharsets.UTF_8);
    }

    @Test
    void queryReturnsTailWithDefaults() throws Exception {
        // 不传 lines 时默认 200；不传 service 时默认 analyser
        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("analyser"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.requestedLines").value(200))
                .andExpect(jsonPath("$.returnedLines").value(3))
                .andExpect(jsonPath("$.lines.length()").value(3));
    }

    @Test
    void keywordFiltersLines() throws Exception {
        mockMvc.perform(get("/api/logs").param("keyword", "alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedLines").value(1))
                .andExpect(jsonPath("$.lines[0]").value(org.hamcrest.Matchers.containsString("ALERT generated")));
    }

    @Test
    void unknownServiceIsRejectedWithBadRequest() throws Exception {
        // 白名单之外的任何输入（含路径穿越样式）都必须 400，且不会去读任何文件
        mockMvc.perform(get("/api/logs").param("service", "../../etc/passwd"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void servicesEndpointListsThreeServices() throws Exception {
        mockMvc.perform(get("/api/logs/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].service").value("analyser"))
                .andExpect(jsonPath("$[1].available").value(true));
    }
}
