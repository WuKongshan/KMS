package com.kms.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Collector module entry point (module A).
 *
 * Since Step 2 this is a Spring Boot application wired to the kms-database
 * dual-datasource configuration via {@code scanBasePackages = "com.kms"}.
 * OSHI collection logic lands in Step 3.
 */
@SpringBootApplication(scanBasePackages = "com.kms")
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
