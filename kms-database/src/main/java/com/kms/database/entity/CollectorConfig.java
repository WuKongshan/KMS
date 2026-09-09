package com.kms.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Collector configuration (single row, id = 1). Changes take effect without
 * restarting the collector: the collection loop re-reads this row every cycle
 * (spec 4.2).
 */
@Data
@TableName("t_collector_config")
public class CollectorConfig {

    /** Fixed id = 1; the id value is always supplied, never auto-generated. */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    /** Collection period in seconds, minimum 60 (spec 4.2). */
    private Integer periodSeconds;

    /** 1 = enabled, 0 = disabled. */
    private Integer enabled;

    private LocalDateTime updateTime;
}
