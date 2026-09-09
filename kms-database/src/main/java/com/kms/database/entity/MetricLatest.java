package com.kms.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Latest value per metric, updated by the collector after every cycle — used
 * for quick display without querying the time-series database (spec 4.2).
 */
@Data
@TableName("t_metric_latest")
public class MetricLatest {

    /** Metric code: cpu_usage, mem_usage, disk_free, net_send, net_recv, cpu_temp. */
    @TableId(value = "metric_code", type = IdType.INPUT)
    private String metricCode;

    private String metricName;

    private Double currentValue;

    private String unit;

    private LocalDateTime updateTime;
}
