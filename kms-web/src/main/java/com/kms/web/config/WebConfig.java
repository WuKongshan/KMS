package com.kms.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web module infrastructure: a short-timeout {@link RestTemplate} for calling
 * the collector's REST control API (spec 4.4, item 1) and permissive CORS for
 * {@code /api/**} so the Vite dev server can call the backend during
 * development (in production the frontend is served from the same origin).
 *
 * <p>【中文】Web 模块的基础设施配置，只做两件事：一是为调用采集器 REST 控制 API 的
 * {@link RestTemplate} 设置短超时（规格 4.4 第 1 条），二是为 {@code /api/**} 放开 CORS，
 * 使 Vite 开发服务器在开发期能够直连后端（生产环境前端与后端同源，本来不需要 CORS）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * The collector may be stopped; fail fast instead of hanging requests.
     *
     * <p>【中文】采集器随时可能处于停止状态，因此宁可快速失败，也不要让请求一直挂起。
     * <p>【中文】{@link RestTemplate} 默认没有设置任何超时（即无限等待），必须显式指定：
     * 连接超时 2 秒、读取超时 5 秒。否则一旦采集器进程卡死（GC 停顿或线程死锁），
     * 每个转发请求都会长期占用一个 Tomcat 工作线程；线程被占满后，整个 Web 应用将失去响应。
     */
    @Bean
    public RestTemplate collectorRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时 2 秒：采集器端口不通时快速失败，不拖住运维页面的状态轮询。
        factory.setConnectTimeout(2000);
        // 读取超时 5 秒：覆盖采集器处理请求并返回状态的最大可容忍时间。
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    // CORS 是为 Vite 开发服务器（3000 端口）跨域调用准备的；生产环境前端由 Spring Boot 同源托管，
    // 并不需要 CORS，但配上是双保险：用 curl/Postman 直连、或把 VITE_API_BASE 指向完整 URL 时就是真跨域。
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 用 allowedOriginPatterns("*") 而不是 allowedOrigins("*")：前者在携带凭据时仍可使用通配符。
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }
}
