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
 *
 * <p>【中文】分析模块（analyser）产生的预警记录（规格 4.3），对应达梦表 t_alert_record。
 * 级别含义：1 = 仅固定阈值超限；2 = 仅相邻点波动率超限（当前值仍在正常范围内）；3 = 两者同时满足。
 * status：0 = 未处理，1 = 已处理（由 Web 预警管理页面修改）。
 * 写入方为 AnalysisLoop（每个分析周期插入一条）；读取方为 Web 预警管理、数据大屏，
 * 5 分钟冷却去重查询也读本表。
 */
@Data
@TableName("t_alert_record")
public class AlertRecord {

    /**
     * DM8 IDENTITY column.
     *
     * <p>【中文】达梦自增主键（BIGINT IDENTITY(1,1)），由数据库生成。
     * 本表是唯一使用 IdType.AUTO 的表：MyBatis-Plus 在 insert 后通过 getGeneratedKeys
     * 把生成的 id 回填进实体，而 AnalysisLoop 插入后立刻用这个 id 做 WebSocket 广播，
     * 所以自增回填不能少。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    // 指标代码（cpu_usage / mem_usage / disk_free / net_send / net_recv / cpu_temp 之一），
    // 由分析模块写入；它既是 5 分钟冷却去重查询的 WHERE 条件，也是前端反查指标单位的依据。
    private String metricCode;

    // 指标名称快照：插入时从 t_metric_def 复制一份，用于历史保真
    // （即使指标定义后来改名，历史预警仍显示当时的名称）；由 AnalysisLoop 写入，
    // WebSocket 推送载荷与预警列表都会读取它。
    private String metricName;

    /**
     * Alert level: 1 / 2 / 3 (spec 4.3).
     *
     * <p>【中文】预警级别：1 = 仅阈值超限；2 = 仅波动异常（值仍在正常范围）；
     * 3 = 阈值与波动同时异常。由分析模块写入，前端按级别着色并支持筛选。
     */
    private Integer alertLevel;

    /**
     * THRESHOLD / VOLATILITY / THRESHOLD_VOLATILITY.
     *
     * <p>【中文】预警类型枚举：THRESHOLD = 阈值超限，VOLATILITY = 波动率异常，
     * THRESHOLD_VOLATILITY = 两者同时；信息与 alertLevel 冗余，但让 DBA 直接读表时
     * 不必再把级别换算成类型。
     */
    private String alertType;

    /**
     * Human-readable description.
     *
     * <p>【中文】预警文案，对应达梦列 message VARCHAR(512)，由分析模块拼装，
     * 可直接展示在预警列表与推送弹窗上。
     */
    private String message;

    /**
     * Metric value that triggered the alert.
     *
     * <p>【中文】触发本次预警的指标值，取 TDengine 最近一个采样点的值。
     */
    private Double currentValue;

    /**
     * The threshold that was breached (upper, lower or volatility).
     *
     * <p>【中文】被突破的阈值（上阈值、下阈值或波动率阈值）；可为 NULL——
     * 纯波动类预警没有对应的阈值列。
     */
    private Double thresholdValue;

    /**
     * Observed adjacent-point volatility in percent (null for pure threshold alerts).
     *
     * <p>【中文】实测相邻点波动率（百分比）；级别 1（仅阈值超限）时为 NULL。
     */
    private Double volatility;

    /**
     * 0 = unhandled, 1 = handled.
     *
     * <p>【中文】处理状态：0 = 未处理，1 = 已处理（Web 预警管理页标记）；
     * 该字段不参与冷却判定，只影响页面展示。
     */
    private Integer status;

    // 预警产生时间，由分析模块写入；用于预警列表排序、时间范围筛选，
    // 同时也是 5 分钟冷却判定的基准时间。
    private LocalDateTime createTime;

    // 只写不读：仅数据库层在标记处理时写入，业务代码不读取，纯粹用于审计。
    private LocalDateTime updateTime;
}
