package com.kms.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web module entry point (module D).
 *
 * Since Step 2 this is a Spring Boot application wired to the kms-database
 * dual-datasource configuration via {@code scanBasePackages = "com.kms"}.
 * The RuoYi-style UI lands in Step 5 and the dashboard in Step 6.
 *
 * <p>【中文】Web 模块（规格中的“模块 D”）的启动类：本模块监听 3000 端口，同时扮演两个角色，
 * 一是托管前端构建产物（{@code classpath:/static/}，由 Vite 从 {@code kms-web/frontend}
 * 构建后拷入），二是对外提供 REST API。
 * <p>【中文】{@code scanBasePackages = "com.kms"} 让本模块能扫描到达梦 + TDengine 共享的
 * 双数据源配置、实体与 Mapper（它们都在 kms-database 中），从而运行在同一个 Spring 容器内。
 * <p>【中文】本模块不直接修改采集配置：所有采集器操作都通过 HTTP 代理到采集器的 8081 端口
 * （见 {@code CollectorProxyService}），以保持采集器进程的独立性。
 */
@SpringBootApplication(scanBasePackages = "com.kms")
public class WebApplication {

    // 标准 Spring Boot 启动入口：由 SpringApplication.run 完成容器刷新与内嵌 Tomcat 的启动。
    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
