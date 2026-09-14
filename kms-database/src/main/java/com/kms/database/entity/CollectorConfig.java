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
 *
 * <p>【中文】采集配置（单行表，id 恒为 1），对应达梦表 t_collector_config。
 * 修改后无需重启采集器即生效：采集循环每一轮都会重新读取这一行（规格 4.2）。
 * 写入方为采集器控制 API（Web 运维管理页经代理调用），读取方为 CollectionLoop 与 CollectorConfigService。
 */
@Data
@TableName("t_collector_config")
public class CollectorConfig {

    /**
     * Fixed id = 1; the id value is always supplied, never auto-generated.
     *
     * <p>【中文】主键恒为 1（单例配置行），由业务指定，故用 IdType.INPUT 而非自增：
     * 插入时必须显式给出 id，数据库不会生成。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    /**
     * Collection period in seconds, minimum 60 (spec 4.2).
     *
     * <p>【中文】采集周期秒数，最小 60。共四道防线保证这个下限：前端 el-input-number 的 :min、
     * CollectorProxyService 校验、CollectorConfigService 校验，以及 CollectionLoop 中
     * Math.max(periodSeconds, 60) 的兜底。
     */
    private Integer periodSeconds;

    /**
     * 1 = enabled, 0 = disabled.
     *
     * <p>【中文】1 = 启用采集，0 = 停用采集；注意它是 Integer 而不是 boolean，
     * 代码统一用 Integer.valueOf(1).equals(...) 判断，避免拆箱时的 NPE。
     */
    private Integer enabled;

    // 配置最后修改时间，只写不读，仅作数据库层审计。
    private LocalDateTime updateTime;
}
