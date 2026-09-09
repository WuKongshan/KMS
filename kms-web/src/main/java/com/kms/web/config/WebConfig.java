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
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** The collector may be stopped; fail fast instead of hanging requests. */
    @Bean
    public RestTemplate collectorRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }
}
