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
 *
 * <p>【中文】预警规则（规格 4.3）：条件表达式针对最新采集值求值，对应达梦表 t_alert_rule。
 * 表达式由自研的 RuleExpression 递归下降解析器解析，可引用变量 value、upper_threshold、
 * lower_threshold、volatility、volatility_threshold，例如 "value > upper_threshold"。
 * AnalysisLoop 每轮（默认 30 秒）重新读取本表，因此改完规则最多 30 秒生效，无需重启分析模块。
 */
@Data
@TableName("t_alert_rule")
public class AlertRule {

    /**
     * The id value is always supplied (seeded), never auto-generated.
     *
     * <p>【中文】主键由业务手工指定（种子数据固定为 1~6），所以用 IdType.INPUT 而非自增：
     * 插入时必须显式给出 id，数据库不会生成。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    // 规则编码（如 RULE_CPU），由种子数据写入；分析模块在运行日志、异常日志和
    // 1 级预警文案中引用它，便于排查是哪条规则触发的。
    private String ruleCode;

    // 规则名称，只写不读，为将来的规则管理页面预留的展示字段。
    private String ruleName;

    /**
     * Metric this rule applies to (t_metric_def.metric_code).
     *
     * <p>【中文】该规则作用的指标代码（对应 t_metric_def.metric_code）；此列必须唯一——
     * AnalysisLoop 用 Collectors.toMap(AlertRule::getMetricCode, ...) 建索引，
     * 出现重复会抛异常并中断本轮分析。
     */
    private String metricCode;

    /**
     * Condition expression, e.g. "value > upper_threshold".
     *
     * <p>【中文】条件表达式，如 "value > upper_threshold"：由 RuleExpression 解析求值，
     * 支持 > >= < <= == != && || ! 以及 and / or 和括号；修改后最多 30 秒生效，无需重启。
     */
    private String expression;

    /**
     * 1 = enabled, 0 = disabled.
     *
     * <p>【中文】1 = 启用，0 = 停用（Integer 而非 boolean）；AnalysisLoop 只 select 出
     * enabled = 1 的规则，被停用的指标在分析时直接跳过。
     */
    private Integer enabled;

    // 规则最后修改时间，只写不读，仅作数据库层审计。
    private LocalDateTime updateTime;
}
