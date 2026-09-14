package com.kms.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kms.database.entity.CollectorConfig;

/**
 * DM8 mapper for {@code t_collector_config}.
 *
 * <p>【中文】达梦采集配置表 {@code t_collector_config} 的 Mapper（单行表，id 恒为 1）。
 * 空的 {@code BaseMapper} 子接口：零 XML、零 {@code @Select} 注解，所有查询都用 Lambda Wrapper
 * 拼装（字段名有编译期检查）。由 {@code DmDataSourceConfig} 上的
 * {@code @MapperScan("com.kms.database.mapper")} 扫描注册。
 * 使用方：采集模块的 CollectionLoop（每轮读取 id = 1 这一行，从而支持免重启修改周期/启停）、
 * CollectorConfigService（控制 API 修改配置）与 SchemaInitializer（建表并写入种子行）。
 */
public interface CollectorConfigMapper extends BaseMapper<CollectorConfig> {
}
