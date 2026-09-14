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
 *
 * <p>【中文】分析模块（规格里的"模块 C"）启动类：跑在 8082 端口，读 TDengine 的
 * 最近两个数据点与达梦里的阈值/规则，做三级预警判定，把预警记录写入达梦，
 * 并通过 {@code /ws/alerts} WebSocket 实时推送给前端。
 *
 * <p>【中文】这里同时开启三件后台工作：分析循环（默认 30 秒一轮）、动态阈值任务
 * （默认 5 分钟一轮）以及 WebSocket 推送端点；通过 {@code scanBasePackages = "com.kms"}
 * 复用 kms-database 里的双数据源（达梦主库 + TDengine 时序库）配置。
 */
// 扫描 com.kms 整个包，从而装载 kms-database 中的双数据源、实体与 Mapper
@SpringBootApplication(scanBasePackages = "com.kms")
// 开启定时任务支持：AnalysisLoop 与 DynamicThresholdJob 都靠 @Scheduled 驱动
@EnableScheduling
public class AnalyserApplication {

    public static void main(String[] args) {
        // 标准 Spring Boot 启动入口；分析模块同时内嵌 Tomcat，
        // 因为 /ws/alerts 这个 WebSocket 端点要靠它来承载
        SpringApplication.run(AnalyserApplication.class, args);
    }
}
