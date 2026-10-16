package com.kms.collector.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the collector's control API.
 *
 * <p>【中文】采集器控制 API 的 OpenAPI 文档配置，Swagger UI 地址：
 * {@code http://localhost:8081/swagger-ui/index.html}。
 *
 * <p>【中文】采集器是<b>内网服务</b>：Web 模块通过它改采集周期、启停采集。
 * 该端口没有接入鉴权（采集器不带 Spring Security），因此生产部署必须限制来源
 * （例如防火墙只允许 Web 模块所在主机访问 8081）。
 *
 * <p>【中文】这里不声明 bearerAuth 安全方案——采集器不校验令牌，
 * 若在文档里声明会误导调用方以为需要鉴权。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI collectorOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("KMS 采集器 · 控制接口文档")
                        .version("1.0.0")
                        .description("""
                                采集模块（模块 A）的控制 API：查询采集状态、修改采集周期、启用/停用采集。

                                **调用方**：Web 模块（`kms-web`）的 `/api/ops/collector/**` 会转发到这里，
                                前端不直连本端口。运维排障时也可直接调用本接口。

                                **鉴权**：无。本接口是内网内部接口，生产环境请用防火墙限制来源 IP
                                （仅允许 Web 模块所在主机访问）。

                                **热生效**：修改周期与启停都会唤醒采集循环，无需重启采集器。
                                """));
    }
}
