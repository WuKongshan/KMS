package com.kms.analyser.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kms.analyser.rule.RuleExpression;
import com.kms.database.entity.AlertRecord;
import com.kms.database.entity.AlertRule;
import com.kms.database.entity.MetricDef;
import com.kms.database.mapper.AlertRecordMapper;
import com.kms.database.mapper.AlertRuleMapper;
import com.kms.database.mapper.MetricDefMapper;
import com.kms.database.metric.KmsMetrics;
import com.kms.database.tdengine.TdengineTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The analysis loop (spec 4.3): reads the last two TDengine points of every
 * metric, matches the {@code t_alert_rule} condition expression against the
 * latest value (rule engine), computes the adjacent-point volatility, and
 * derives the graded alert level:
 *
 * <ul>
 *   <li>level 1 — fixed-threshold breach only</li>
 *   <li>level 2 — volatility above the configured percent while the value is
 *       still within the normal range</li>
 *   <li>level 3 — threshold breach and volatility breach at the same time</li>
 * </ul>
 *
 * <p>Alerts are inserted into {@code t_alert_record} (with a cooldown per
 * metric + level to avoid flooding) and pushed to the frontend over the
 * {@code /ws/alerts} WebSocket. Every evaluation is logged.
 *
 * <p>【中文】分析循环（规格 4.3）：以固定延迟 30 秒一轮（fixedDelay，不是固定频率），
 * 先读达梦里的指标定义（t_metric_def 全部）与启用中的规则（t_alert_rule 里 enabled = 1），
 * 再按指标逐个去 TDengine 取最近两个数据点，做阈值判定 + 波动率判定，
 * 推导出分级预警级别：
 *
 * <p>【中文】1 级 —— 仅固定阈值超限。
 *
 * <p>【中文】2 级 —— 波动率超过配置的百分比，而当前值仍在正常范围内。
 *
 * <p>【中文】3 级 —— 阈值超限与波动率超限同时发生。
 *
 * <p>【中文】预警记录写入达梦 t_alert_record（同一"指标 + 级别"受冷却窗口约束，避免刷屏），
 * 随后经 {@code /ws/alerts} WebSocket 推送给前端。每一轮、每一项指标的判定依据都会记日志，
 * 方便事后复盘"为什么报了 / 为什么没报"。
 *
 * <p>【中文】异常粒度分两级：读配置（达梦）失败时整轮 return，等下一个 30 秒周期重试；
 * 单个指标分析失败只跳过它自己，不影响同一轮的其他指标。
 */
@Component
public class AnalysisLoop {

    private static final Logger log = LoggerFactory.getLogger(AnalysisLoop.class);

    // 达梦侧的三张表：指标定义（阈值）、预警规则（表达式）、预警记录（结果）
    private final MetricDefMapper metricDefMapper;
    private final AlertRuleMapper alertRuleMapper;
    private final AlertRecordMapper alertRecordMapper;
    // TDengine 模板：读取每个指标的最近两个数据点（时序库走 TAOS-WS 协议）
    private final JdbcTemplate taosJdbcTemplate;
    // 达梦模板：冷却判定要用它查 MAX(create_time)，用 MyBatis-Plus 表达聚合不太方便
    private final JdbcTemplate dmJdbcTemplate;
    // 预警落库后由它广播给所有在线前端
    private final AlertWebSocketHandler webSocketHandler;
    // TDengine 子表名（t_perf_<主机名>）：构造时算一次，之后每轮复用
    private final String subTable;
    // 同指标 + 同级别的预警冷却窗口（分钟），来自 kms.analyser.alert-cooldown-minutes
    private final long cooldownMinutes;

    // 构造注入：两个 JdbcTemplate 靠 @Qualifier 区分数据源
    // （taosJdbcTemplate 指向 TDengine 时序库，dmJdbcTemplate 指向达梦主库）
    public AnalysisLoop(MetricDefMapper metricDefMapper,
                        AlertRuleMapper alertRuleMapper,
                        AlertRecordMapper alertRecordMapper,
                        @Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate,
                        @Qualifier("dmJdbcTemplate") JdbcTemplate dmJdbcTemplate,
                        AlertWebSocketHandler webSocketHandler,
                        @Value("${kms.analyser.alert-cooldown-minutes:5}") long cooldownMinutes) {
        this.metricDefMapper = metricDefMapper;
        this.alertRuleMapper = alertRuleMapper;
        this.alertRecordMapper = alertRecordMapper;
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.dmJdbcTemplate = dmJdbcTemplate;
        this.webSocketHandler = webSocketHandler;
        this.subTable = TdengineTableNames.subTable(TdengineTableNames.localHostName());
        this.cooldownMinutes = cooldownMinutes;
    }

    // fixedDelay（而不是 fixedRate）：上一轮"结束"之后再等 30 秒才开下一轮，
    // 避免慢查询把轮次叠加成雪崩；首轮延迟 15 秒，留给双数据源与建表流程完成初始化
    @Scheduled(fixedDelayString = "${kms.analyser.interval-ms:30000}",
            initialDelayString = "${kms.analyser.initial-delay-ms:15000}")
    public void runAnalysis() {
        log.info("Analysis cycle started");
        // 两张索引表：metric_code -> 指标定义，metric_code -> 启用中的规则
        Map<String, MetricDef> defs;
        Map<String, AlertRule> rules;
        // 一次性把配置读进内存，用 Collectors.toMap 以 metric_code 为 key 建索引：
        // 后面按 code 配对就是 O(1) 查找，整体 O(n+m)，而不是双层遍历的 O(n×m)。
        // 注意 toMap 遇到重复 key 会直接抛异常——所以一个指标只能有一条启用规则。
        try {
            defs = metricDefMapper.selectList(null).stream()
                    .collect(Collectors.toMap(MetricDef::getMetricCode, Function.identity()));
            rules = alertRuleMapper.selectList(
                            new LambdaQueryWrapper<AlertRule>().eq(AlertRule::getEnabled, 1)).stream()
                    .collect(Collectors.toMap(AlertRule::getMetricCode, Function.identity()));
        } catch (Exception e) {
            log.error("Analysis cycle aborted: cannot read DM8 (database unavailable?)", e);
            // 读配置失败属于"环境级"故障：整轮直接 return，等下一个 30 秒周期自然重试；
            // 连阈值和规则都读不到就没有任何可做的判断，因此不做局部降级
            return;
        }
        // 逐指标分析；单指标的异常在循环体内部单独捕获（异常粒度分级：
        // 配置读取失败 → 整轮退出；单指标失败 → 只跳过它自己）
        for (MetricDef def : defs.values()) {
            // 用 metric_code 在规则索引里配对：配不上就是"这个指标没配启用规则"
            AlertRule rule = rules.get(def.getMetricCode());
            if (rule == null) {
                // 该指标没有 enabled = 1 的规则，属于正常配置状态，debug 级别跳过即可
                log.debug("No enabled rule for metric {}, skipped", def.getMetricCode());
                continue;
            }
            if (!KmsMetrics.isKnownCode(def.getMetricCode())) {
                // 指标代码不在 KmsMetrics 白名单内：既可能是库里的脏数据，也可能是注入面
                // （下面的 SQL 直接把 code 拼进语句），必须先拦住再谈分析
                log.warn("Metric {} is not a known metric code, skipped", def.getMetricCode());
                continue;
            }
            try {
                // 单个指标的隔离边界：这里的任何异常（SQL 失败、数据格式异常等）
                // 只影响这一项指标，同一轮里的其他指标照常分析
                analyseMetric(def, rule);
            } catch (Exception e) {
                log.error("Analysis failed for metric {}", def.getMetricCode(), e);
            }
        }
    }

    // 单项指标的完整流程：取最近两点 → 算波动率 → 规则表达式求值 → 推导级别
    // → 冷却判定 → 构造并落库记录 → WebSocket 推送
    private void analyseMetric(MetricDef def, AlertRule rule) {
        String code = def.getMetricCode();
        // 取最近两个点：波动率只需要相邻两点，LIMIT 2 在时序库上是按时间倒序的
        // O(1) 读取（不用扫全表）；code 已被白名单校验、subTable 由主机名生成，无注入风险
        List<Map<String, Object>> rows = taosJdbcTemplate.queryForList(
                "SELECT ts, " + code + " FROM " + subTable + " ORDER BY ts DESC LIMIT 2");
        if (rows.size() < 2) {
            // 采到的点不足 2 个（刚启动、采集器刚恢复、或该指标根本没数据）：
            // 算不出波动率，等下一轮再看
            log.info("Metric {}: only {} point(s) collected so far, need 2, skipped",
                    code, rows.size());
            return;
        }
        // ORDER BY ts DESC：第 0 行是最新点，第 1 行是它的前一个点；
        // TDengine 可能返回 Integer/Long/Float/Double，统一由 toDouble 归一化
        Double latest = toDouble(rows.get(0).get(code));
        Double previous = toDouble(rows.get(1).get(code));
        if (latest == null) {
            // 最新点为空（例如温度传感器不支持、该列是 NULL）→ 无从判定，跳过
            log.info("Metric {}: latest value is null (unsupported sensor?), skipped", code);
            return;
        }

        // Bind rule variables: value, thresholds and observed volatility.
        // 绑定规则变量：当前值、上下限阈值、波动率阈值与实际波动率（共 5 个）
        Map<String, Double> vars = new HashMap<>();
        vars.put("value", latest);
        vars.put("upper_threshold", def.getUpperThreshold());
        vars.put("lower_threshold", def.getLowerThreshold());
        vars.put("volatility_threshold", def.getVolatilityThreshold());
        // 注意：previous == null 时不提前返回——波动率算不出来，但阈值判定依然有效
        Double volatility = null;
        if (previous != null && previous != 0.0) {
            // 波动率 = |本点 - 前点| / |前点| × 100%，双重保护：
            // previous 为 null 或为 0 都不计算——除零会得到 Infinity，
            // 而 Infinity > 10 恒为真，会让这个指标每一轮都误报
            volatility = Math.abs(latest - previous) / Math.abs(previous) * 100;
        }
        // 波动率未定义时绑定 null → 表达式里的 volatility 成为"未知"→ 不触发（三值逻辑）
        vars.put("volatility", volatility);

        // 阈值判定交给规则表达式引擎；表达式来自数据库，可能被写成非法语法
        boolean thresholdBreach;
        try {
            thresholdBreach = RuleExpression.evaluate(rule.getExpression(), vars);
        } catch (IllegalArgumentException e) {
            // 表达式语法错误：只跳过该指标并记 error（规则需人工修正），
            // 不影响同一轮其他指标的判定
            log.error("Metric {}: rule {} has an invalid expression '{}': {}",
                    code, rule.getRuleCode(), rule.getExpression(), e.getMessage());
            return;
        }
        // 波动率超限 = 波动率算得出来 且 配了波动率阈值 且 实际值严格大于阈值
        boolean volatilityBreach = volatility != null && def.getVolatilityThreshold() != null
                && volatility > def.getVolatilityThreshold();

        // 每轮都打印一行完整的判定依据（当前值/前值/波动率/阈值/两个判定结果），
        // 这是排查"为什么报了 / 为什么没报"最直接的一手材料
        log.info("Metric {}: value={} previous={} volatility={}% (limit {}%) "
                        + "thresholdBreach={} volatilityBreach={}",
                code, fmt(latest), previous == null ? "n/a" : fmt(previous),
                volatility == null ? "n/a" : fmt(volatility),
                def.getVolatilityThreshold() == null ? "n/a" : fmt(def.getVolatilityThreshold()),
                thresholdBreach, volatilityBreach);

        // 两个判定都为假 = 这个点正常，直接结束（下面才开始真正"发预警"）
        if (!thresholdBreach && !volatilityBreach) {
            return;
        }

        // Level 1 = threshold only, level 2 = volatility only (value normal),
        // level 3 = both (spec 4.3).
        // 1 级 = 仅阈值超限；2 级 = 仅波动率异常（值仍正常）；3 级 = 两者同时（规格 4.3）
        // 嵌套三元的读法：两者都真 → 3；否则仅阈值真 → 1；剩下的必然是"仅波动" → 2
        int level = thresholdBreach && volatilityBreach ? 3 : thresholdBreach ? 1 : 2;
        // 顺序：先做冷却检查，再构造记录——buildRecord 有分支与字符串拼接开销，
        // 被冷却抑制的预警没必要白构造一条
        if (!checkCooldown(code, level)) {
            log.info("Metric {}: level {} alert suppressed by cooldown ({} min)", code, level, cooldownMinutes);
            return;
        }

        AlertRecord record = buildRecord(def, rule, latest, volatility, thresholdBreach, volatilityBreach, level);
        // insert 之后达梦的 IDENTITY 主键会回填到 record.id，
        // 紧随其后的广播才能把 id 一起带给前端（前端要去重、要按 id 标记处理）
        alertRecordMapper.insert(record);
        log.warn("ALERT generated (level {}): {}", level, record.getMessage());
        // WebSocket 推送失败/无客户端都不影响入库，历史预警仍可在预警管理页查到
        webSocketHandler.broadcast(record);
    }

    /**
     * Suppress repeated alerts for the same metric + level within the cooldown
     * window (DM8 stores the newest t_alert_record row per key).
     *
     * <p>【中文】冷却去重：同一"指标 + 级别"在冷却窗口内只发一次。
     *
     * <p>【中文】为什么查数据库而不是用内存 Map 记时间戳：内存方案在进程重启后冷却状态
     * 全部归零，重启瞬间会把同一批预警重新推一遍；查库让冷却成为持久化的业务规则，
     * 也与预警记录本身保持同一份事实来源。
     *
     * <p>【中文】冷却粒度是"指标 + 级别"，而不是只按指标：这样从 1 级升级到 3 级时
     * 可以立即发出，不会被低级别刚用掉的窗口挡住。
     */
    private boolean checkCooldown(String metricCode, int level) {
        Timestamp last = dmJdbcTemplate.queryForObject(
                "SELECT MAX(create_time) FROM t_alert_record WHERE metric_code = ? AND alert_level = ?",
                Timestamp.class, metricCode, level);
        // 从没发过（MAX 为 NULL）或距上次同级别预警已超过窗口长度 → 允许发送
        return last == null || System.currentTimeMillis() - last.getTime() >= cooldownMinutes * 60_000L;
    }

    private AlertRecord buildRecord(MetricDef def, AlertRule rule, Double latest, Double volatility,
                                    boolean thresholdBreach, boolean volatilityBreach, int level) {
        // 单位为空时用空串兜底，避免消息文本里出现字面的 "null"
        String unit = def.getUnit() == null ? "" : def.getUnit();
        // "反推"撞的是上限还是下限：规则表达式只返回 true/false，并不告诉你是哪一侧，
        // 所以这里用同样的比较条件再判一次。两个分支都不命中时（例如表达式用了
        // 自定义的组合条件）thresholdValue 保持 NULL，消息退化为 "matched rule" 模板。
        Double thresholdValue = null;
        if (thresholdBreach && def.getUpperThreshold() != null && latest > def.getUpperThreshold()) {
            thresholdValue = def.getUpperThreshold();
        } else if (thresholdBreach && def.getLowerThreshold() != null && latest < def.getLowerThreshold()) {
            thresholdValue = def.getLowerThreshold();
        }

        AlertRecord record = new AlertRecord();
        record.setMetricCode(def.getMetricCode());
        record.setMetricName(def.getMetricName());
        record.setAlertLevel(level);
        record.setCurrentValue(latest);
        record.setThresholdValue(thresholdValue);
        // 只有真的是波动率超限才落库波动率：1 级（纯阈值）时该列存 NULL，
        // 免得把与本次预警无关的波动率混进记录里误导排查
        record.setVolatility(volatilityBreach ? volatility : null);
        // status = 0 表示"未处理"，预警管理页面据此筛选待处理项
        record.setStatus(0);
        LocalDateTime now = LocalDateTime.now();
        record.setCreateTime(now);
        record.setUpdateTime(now);
        // alert_type 是给前端做标签展示的字符串枚举，与 level 一一对应
        record.setAlertType(switch (level) {
            case 1 -> "THRESHOLD";
            case 2 -> "VOLATILITY";
            default -> "THRESHOLD_VOLATILITY";
        });

        // 消息按级别用三套模板：1 级阈值（反推出具体阈值时点名阈值，否则退化为
        // matched rule 模板）、2 级波动（强调值仍在正常范围）、3 级两者同时；
        // 所有数值都过 fmt() 统一格式化成两位小数
        String message;
        if (level == 1) {
            message = thresholdValue != null
                    ? String.format(Locale.ROOT, "%s %s%s breaches threshold %s%s (rule %s)",
                    def.getMetricName(), fmt(latest), unit, fmt(thresholdValue), unit, rule.getRuleCode())
                    : String.format(Locale.ROOT, "%s value %s%s matched rule %s",
                    def.getMetricName(), fmt(latest), unit, rule.getRuleCode());
        } else if (level == 2) {
            message = String.format(Locale.ROOT,
                    "%s volatility %s%% exceeds %s%% (value %s%s still within normal range)",
                    def.getMetricName(), fmt(volatility), fmt(def.getVolatilityThreshold()), fmt(latest), unit);
        } else {
            message = String.format(Locale.ROOT,
                    "%s %s%s breaches threshold %s%s and volatility %s%% exceeds %s%%",
                    def.getMetricName(), fmt(latest), unit,
                    thresholdValue == null ? "n/a" : fmt(thresholdValue), unit,
                    fmt(volatility), fmt(def.getVolatilityThreshold()));
        }
        record.setMessage(message);
        return record;
    }

    // 把 JDBC 返回的任意数值类型（Integer/Long/Float/Double/BigDecimal…）归一成 Double，
    // 非数值（NULL 哨兵等）返回 null，交给调用方与三值逻辑处理
    private static Double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    // 统一的数值格式化：null 显示为 "n/a"，否则固定两位小数；
    // 用 Locale.ROOT 是为了避免某些区域设置把小数点渲染成逗号，破坏日志与前端解析
    private static String fmt(Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.2f", value);
    }
}
