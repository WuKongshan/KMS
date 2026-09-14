package com.kms.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Collector module entry point (module A).
 *
 * Since Step 2 this is a Spring Boot application wired to the kms-database
 * dual-datasource configuration via {@code scanBasePackages = "com.kms"}.
 * OSHI collection logic lands in Step 3.
 *
 * <p>【中文】采集模块（规格中的“模块 A”）启动入口。自 Step 2 起它就是一个
 * Spring Boot 应用，通过 {@code scanBasePackages = "com.kms"} 扫描到
 * kms-database 中的双数据源配置与 Mapper（达梦为主库，TDengine 为时序库）；
 * OSHI 采集逻辑在 Step 3 引入。本模块必须运行在宿主机上（不能容器化），
 * 否则 OSHI 读不到宿主机的真实硬件指标。
 */
// 扫描包上提到 com.kms（而不是默认的本包），以便装配 kms-database 的数据源与 Mapper
@SpringBootApplication(scanBasePackages = "com.kms")
public class CollectorApplication {

    public static void main(String[] args) {
        // 启动 Spring 容器；采集循环作为 ApplicationRunner 随后按 @Order 顺序执行
        SpringApplication.run(CollectorApplication.class, args);
    }
}
