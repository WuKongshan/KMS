package com.kms.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kms.database.entity.AlertRule;

/**
 * DM8 mapper for {@code t_alert_rule}.
 *
 * <p>【中文】达梦预警规则表 {@code t_alert_rule} 的 Mapper。空的 {@code BaseMapper} 子接口：
 * 零 XML、零 {@code @Select} 注解，所有查询都用 Lambda Wrapper 拼装（字段名有编译期检查）。
 * 由 {@code DmDataSourceConfig} 上的 {@code @MapperScan("com.kms.database.mapper")} 扫描注册。
 * 使用方：分析模块 AnalysisLoop（每轮 selectList 出 enabled = 1 的规则，故改规则无需重启）、
 * kms-database 的 AlertSchemaInitializer（幂等写入种子规则）。
 */
public interface AlertRuleMapper extends BaseMapper<AlertRule> {
}
