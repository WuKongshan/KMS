package com.kms.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3 document metadata for Swagger UI.
 *
 * <p>【中文】Swagger UI 所用的 OpenAPI 3 文档元信息配置。
 * 访问地址：{@code /swagger-ui/index.html}（JSON 文档在 {@code /v3/api-docs}）。
 *
 * <p>【中文】这里只声明"文档本身的元信息"与"鉴权方案"，接口清单由 springdoc 扫描
 * {@code @RestController} 自动生成——**接口改了文档自动跟着改**，不会再出现
 * 手写接口文档与实际实现不一致的问题。
 *
 * <p>【中文】关键配置是 bearerAuth 安全方案：Swagger UI 右上角会出现 Authorize 按钮，
 * 贴上 {@code /api/auth/login} 返回的 JWT 后，所有请求自动携带
 * {@code Authorization: Bearer xxx}，可以直接在页面上点"试一试"完成联调。
 */
@Configuration
public class OpenApiConfig {

    // 安全方案名：控制器上用 @SecurityRequirement(name = "bearerAuth") 引用它
    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI kmsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("KMS 关键指标监控系统 · 接口文档")
                        .version("1.0.0")
                        .description("""
                                单机关键指标监控系统的 REST 接口文档，由 springdoc 从代码自动生成。

                                **功能模块**：认证鉴权、指标查询（达梦 + TDengine）、预警管理、运维控制、
                                日志查看、操作审计。

                                **如何使用**：
                                1. 调用 `POST /api/auth/login`（admin / admin123）拿到 `token` 字段；
                                2. 点击右上角 **Authorize**，粘贴该 token（**不要**手动加 `Bearer ` 前缀）；
                                3. 之后所有需要鉴权的接口可直接点 **Try it out** 执行。

                                **权限说明**：所有 `/api/**` 接口都需要令牌；其中日志查看、采集控制、
                                预警状态修改、操作审计仅 **ADMIN** 角色可用，VIEWER 角色调用会返回 403。
                                """)
                        .contact(new Contact().name("KMS 项目组"))
                        .license(new License().name("仅供课程/内部使用")))
                // 声明服务器地址：Swagger UI 的 "Try it out" 会以它作为请求前缀
                .servers(List.of(new Server().url("/").description("当前部署（同源，跟随页面地址）")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                // HTTP + bearer 是 JWT 的标准声明方式：OpenAPI 工具链（Postman 导入、
                                // 代码生成器）能据此自动生成"带 Bearer 前缀"的鉴权头
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .description("登录接口返回的 JWT。填令牌本身即可，Swagger 会自动加 Bearer 前缀。")));
    }
}
