package com.kms.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kms.database.entity.MetricLatest;

/**
 * DM8 mapper for {@code t_metric_latest}.
 *
 * <p>【中文】达梦各指标当前值表 {@code t_metric_latest} 的 Mapper。空的 {@code BaseMapper} 子接口：
 * 零 XML、零 {@code @Select} 注解，所有查询都用 Lambda Wrapper 拼装（字段名有编译期检查）。
 * 由 {@code DmDataSourceConfig} 上的 {@code @MapperScan("com.kms.database.mapper")} 扫描注册。
 * 使用方：采集模块的 MetricLatestUpdater（每轮用 LambdaUpdateWrapper 更新各指标当前值）、
 * SchemaInitializer（建表并写入种子行）与 Web 模块的 MetricQueryService（最新值接口）。
 */
public interface MetricLatestMapper extends BaseMapper<MetricLatest> {
}
