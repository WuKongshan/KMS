package com.kms.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kms.database.entity.MetricDef;

/**
 * DM8 mapper for {@code t_metric_def}.
 *
 * <p>【中文】达梦指标定义表 {@code t_metric_def} 的 Mapper。空的 {@code BaseMapper} 子接口：
 * 零 XML、零 {@code @Select} 注解，所有查询都用 Lambda Wrapper 拼装（字段名有编译期检查）。
 * 由 {@code DmDataSourceConfig} 上的 {@code @MapperScan("com.kms.database.mapper")} 扫描注册。
 * 使用方：分析模块 AnalysisLoop（读取阈值并绑定为规则表达式的变量）、DynamicThresholdJob
 * （用 LambdaUpdateWrapper 覆盖写动态阈值），以及 kms-database 的 AlertSchemaInitializer（种子数据）。
 */
public interface MetricDefMapper extends BaseMapper<MetricDef> {
}
