package com.kms.analyser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Analyser module entry point (module C).
 *
 * Runs the analysis loop (rule engine + 3-level alerts over the latest
 * TDengine points), the dynamic threshold job (mean ± 2×std over the last
 * hour, every 5 minutes) and the {@code /ws/alerts} WebSocket endpoint that
 * pushes new alerts to the frontend (spec 4.3). Shares the kms-database
 * dual-datasource configuration via {@code scanBasePackages = "com.kms"}.
 */
@SpringBootApplication(scanBasePackages = "com.kms")
@EnableScheduling
public class AnalyserApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyserApplication.class, args);
    }
}
