package com.kms.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} so audit-log writes happen off the request thread.
 *
 * <p>【中文】开启 {@code @Async}，让审计日志的落库在请求线程之外执行。
 * 用的是 Spring Boot 自动配置的 applicationTaskExecutor（有界线程池），
 * 因此不需要自己再声明一个 Executor。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
