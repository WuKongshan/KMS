/**
 * MyBatis-Plus mappers for the DM8 tables ({@code t_collector_config},
 * {@code t_metric_def}, {@code t_metric_latest}, {@code t_alert_rule},
 * {@code t_alert_record}). Scanned by
 * {@link com.kms.database.config.DmDataSourceConfig}; the first mappers land
 * in Step 3 (collector).
 *
 * <p>【中文】达梦各表的 MyBatis-Plus Mapper 接口（{@code t_collector_config}、
 * {@code t_metric_def}、{@code t_metric_latest}、{@code t_alert_rule}、
 * {@code t_alert_record}）。由 {@link com.kms.database.config.DmDataSourceConfig}
 * 上的 {@code @MapperScan} 扫描注册（该扫描仅覆盖本包，且绑定达梦主数据源；
 * 访问 TDengine 请改用 {@code taosJdbcTemplate}）；最早的 Mapper 在 Step 3（采集模块）落地。
 */
package com.kms.database.mapper;
