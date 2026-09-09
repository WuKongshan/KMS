package com.kms.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Generated alert (spec 4.3). Levels: 1 = fixed-threshold breach only,
 * 2 = adjacent-point volatility breach only (value still within range),
 * 3 = both. {@code status} 0 = unhandled, 1 = handled (web alert management).
 */
@Data
@TableName("t_alert_record")
public class AlertRecord {

    /** DM8 IDENTITY column. */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String metricCode;

    private String metricName;

    /** Alert level: 1 / 2 / 3 (spec 4.3). */
    private Integer alertLevel;

    /** THRESHOLD / VOLATILITY / THRESHOLD_VOLATILITY. */
    private String alertType;

    /** Human-readable description. */
    private String message;

    /** Metric value that triggered the alert. */
    private Double currentValue;

    /** The threshold that was breached (upper, lower or volatility). */
    private Double thresholdValue;

    /** Observed adjacent-point volatility in percent (null for pure threshold alerts). */
    private Double volatility;

    /** 0 = unhandled, 1 = handled. */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
