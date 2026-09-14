package com.kms.database;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal Spring Boot configuration for the kms-database tests: mirrors what
 * {@code @SpringBootApplication} does in the app modules by enabling
 * auto-configuration (MyBatis-Plus, transaction manager). Top-level on purpose —
 * a nested variant broke test-context annotation resolution in Spring 6.2.
 *
 * <p>【中文】kms-database 测试用的最小 Spring Boot 启动类：通过开启自动配置
 * （MyBatis-Plus、事务管理器）复刻各业务模块中 {@code @SpringBootApplication} 的行为，
 * 供测试上下文使用。之所以刻意写成顶级类而非测试类内部类——
 * 嵌套写法在 Spring 6.2 中会破坏测试上下文的注解解析。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class KmsTestApplication {
}
