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
 */
@Data
@TableName("t_metric_def")
public class MetricDef {

    /** Metric code: cpu_usage, mem_usage, disk_free, net_send, net_recv, cpu_temp. */
    @TableId(value = "metric_code", type = IdType.INPUT)
    private String metricCode;

    private String metricName;

    private String unit;

    /** Upper threshold (alert when value exceeds it). */
    private Double upperThreshold;

    /** Lower threshold (alert when value drops below it, e.g. free disk space). */
    private Double lowerThreshold;

    /** Adjacent-point volatility threshold in percent, e.g. 10.0 = 10% (spec 4.3). */
    private Double volatilityThreshold;

    /** 1 = thresholds are recomputed dynamically every 5 minutes (bonus feature). */
    private Integer dynamicThreshold;

    private LocalDateTime updateTime;
}
