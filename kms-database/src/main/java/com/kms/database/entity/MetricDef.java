package com.kms.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Metric definition with alert thresholds (spec 4.3). Fixed thresholds are
 * seeded; when {@link #dynamicThreshold} = 1 the analyser recomputes
 * upper/lower as mean ± 2×std over the last hour every 5 minutes.
 *
 * <p>【中文】指标定义与预警阈值（规格 4.3），对应达梦表 t_metric_def。
 * 固定阈值由种子数据写入；当 {@link #dynamicThreshold} = 1 时，分析模块的 DynamicThresholdJob
 * 每 5 分钟用最近 1 小时的 mean ± 2×std 覆盖重算 upper/lower。
 * 写入方为建表种子数据与 DynamicThresholdJob；读取方为 AnalysisLoop（把阈值绑定成规则表达式的变量）。
 */
@Data
@TableName("t_metric_def")
public class MetricDef {

    /**
     * Metric code: cpu_usage, mem_usage, disk_free, net_send, net_recv, cpu_temp.
     *
     * <p>【中文】指标代码（cpu_usage / mem_usage / disk_free / net_send / net_recv / cpu_temp），
     * 由种子数据插入并直接充当主键；用 IdType.INPUT 而非自增，因为主键就是指标代码本身，
     * AnalysisLoop 依赖它与 t_alert_rule.metric_code 一一对应。
     */
    @TableId(value = "metric_code", type = IdType.INPUT)
    private String metricCode;

    // 指标名称，由种子数据写入；分析模块生成预警时会把它复制进
    // t_alert_record.metric_name 作为快照，保证历史预警的名称不随定义改动而变化。
    private String metricName;

    // 计量单位（%、GB、KB/s、C），由种子数据写入；分析模块拼装预警文案时读取它。
    private String unit;

    /**
     * Upper threshold (alert when value exceeds it).
     *
     * <p>【中文】上阈值：指标值超过它即判为越限；可为 NULL（例如 disk_free 只关心下限）；
     * 当 dynamic_threshold = 1 时，会被 DynamicThresholdJob 用 mean + 2×std 覆盖写。
     */
    private Double upperThreshold;

    /**
     * Lower threshold (alert when value drops below it, e.g. free disk space).
     *
     * <p>【中文】下阈值：指标值低于它即判为越限，例如剩余磁盘空间；可为 NULL（只关心上限的指标）；
     * 当 dynamic_threshold = 1 时同样会被覆盖写，取值为 max(0, mean - 2×std)。
     */
    private Double lowerThreshold;

    /**
     * Adjacent-point volatility threshold in percent, e.g. 10.0 = 10% (spec 4.3).
     *
     * <p>【中文】相邻点波动率上限（百分比），例如 10.0 表示 10%；只由种子数据设定，
     * 动态阈值任务不会修改它。
     */
    private Double volatilityThreshold;

    /**
     * 1 = thresholds are recomputed dynamically every 5 minutes (bonus feature).
     *
     * <p>【中文】1 = 该指标的阈值每 5 分钟动态重算（加分项），0 = 使用固定阈值；
     * 种子数据中只有 cpu_usage 为 1。
     */
    private Integer dynamicThreshold;

    // 只写不读，但它是判断动态阈值任务是否在正常工作的唯一线索——
    // 观察这一列是否每 5 分钟被刷新即可。
    private LocalDateTime updateTime;
}
