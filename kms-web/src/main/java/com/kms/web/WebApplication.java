package com.kms.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web module entry point (module D).
 *
 * Since Step 2 this is a Spring Boot application wired to the kms-database
 * dual-datasource configuration via {@code scanBasePackages = "com.kms"}.
 * The RuoYi-style UI lands in Step 5 and the dashboard in Step 6.
 */
@SpringBootApplication(scanBasePackages = "com.kms")
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
