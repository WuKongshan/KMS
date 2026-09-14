package com.kms.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kms.database.entity.AlertRecord;

/**
 * DM8 mapper for {@code t_alert_record}.
 *
 * <p>【中文】达梦预警记录表 {@code t_alert_record} 的 Mapper。空的 {@code BaseMapper} 子接口：
 * 零 XML、零 {@code @Select} 注解，所有查询都用 Lambda Wrapper 拼装（字段名有编译期检查）。
 * 由 {@code DmDataSourceConfig} 上的 {@code @MapperScan("com.kms.database.mapper")} 扫描注册。
 * 使用方：分析模块 AnalysisLoop（insert 预警，并按 metric_code + alert_level + create_time 查冷却）
 * 与 Web 模块 AlertService（分页筛选、标记处理、取最新 N 条）。
 */
public interface AlertRecordMapper extends BaseMapper<AlertRecord> {
}
