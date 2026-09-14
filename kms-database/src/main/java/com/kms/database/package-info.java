/**
 * Data-access layer for KMS (module B).
 *
 * <p>【中文】KMS 的数据访问层（规格 4.1 中的模块 B），由采集、分析、Web 三个模块共享。
 *
 * Dual datasources: DM8 (relational metadata, alert records, MyBatis-Plus)
 * and TDengine (real-time time-series metrics, JDBC). Configuration classes
 * live in com.kms.database.config; applications pick them up via
 * {@code @SpringBootApplication(scanBasePackages = "com.kms")}.
 *
 * <p>【中文】双数据源：达梦 DM8（关系型元数据、预警记录，走 MyBatis-Plus）+
 * TDengine（实时时序指标，走 JDBC 直连）。配置类位于 com.kms.database.config；
 * 各应用通过 {@code @SpringBootApplication(scanBasePackages = "com.kms")} 扫描装配，
 * 因此业务模块无需重复声明数据源。
 */
package com.kms.database;
