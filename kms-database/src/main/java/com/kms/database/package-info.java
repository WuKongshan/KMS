/**
 * Data-access layer for KMS (module B).
 *
 * Dual datasources: DM8 (relational metadata, alert records, MyBatis-Plus)
 * and TDengine (real-time time-series metrics, JDBC). Configuration classes
 * live in com.kms.database.config; applications pick them up via
 * {@code @SpringBootApplication(scanBasePackages = "com.kms")}.
 */
package com.kms.database;
