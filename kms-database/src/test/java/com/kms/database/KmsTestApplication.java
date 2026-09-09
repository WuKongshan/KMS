package com.kms.database;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal Spring Boot configuration for the kms-database tests: mirrors what
 * {@code @SpringBootApplication} does in the app modules by enabling
 * auto-configuration (MyBatis-Plus, transaction manager). Top-level on purpose —
 * a nested variant broke test-context annotation resolution in Spring 6.2.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class KmsTestApplication {
}
