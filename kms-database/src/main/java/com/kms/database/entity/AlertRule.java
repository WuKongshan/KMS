package com.kms.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Alert rule with a condition expression evaluated against the latest
 * collected value (spec 4.3). Expressions reference the variables value,
 * upper_threshold, lower_threshold, volatility and volatility_threshold,
 * e.g. "value > upper_threshold".
 */
@Data
@TableName("t_alert_rule")
public class AlertRule {

    /** The id value is always supplied (seeded), never auto-generated. */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    private String ruleCode;

    private String ruleName;

    /** Metric this rule applies to (t_metric_def.metric_code). */
    private String metricCode;

    /** Condition expression, e.g. "value > upper_threshold". */
    private String expression;

    /** 1 = enabled, 0 = disabled. */
    private Integer enabled;

    private LocalDateTime updateTime;
}
