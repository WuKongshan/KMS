package com.kms.analyser.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kms.database.entity.MetricDef;
import com.kms.database.mapper.MetricDefMapper;
import com.kms.database.metric.KmsMetrics;
import com.kms.database.tdengine.TdengineTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic threshold recomputation (spec 4.3, bonus feature): every 5 minutes,
 * for every metric flagged dynamic in {@code t_metric_def}, compute the mean
 * and standard deviation of the last hour of TDengine data and adjust the
 * thresholds to mean ± 2×std.
 *
 * <p>【中文】动态阈值任务（规格 4.3 里的加分项）：每 5 分钟跑一次，对 {@code t_metric_def}
 * 中 {@code dynamic_threshold = 1} 的指标（种子数据里只有 cpu_usage），用最近 1 小时
 * TDengine 数据的均值与标准差重算阈值：上限 = mean + 2×std，下限 = mean - 2×std。
 *
 * <p>【中文】它同时覆盖上限与下限，也就是说人工写入的种子阈值会被统计值替换掉；
 * 未被标记为动态的指标完全不碰，仍然使用人工设定的固定阈值。
 */
@Component
public class DynamicThresholdJob {

    private static final Logger log = LoggerFactory.getLogger(DynamicThresholdJob.class);

    /**
     * Minimum number of samples before dynamic thresholds are trusted.
     *
     * <p>【中文】样本数下限：最近 1 小时里的点数少于 5 个时，标准差没有统计意义
     * （极端情况下 2 个点的 std 会把两个采样值直接当成上下限），此时保持原阈值不变，
     * 等样本攒够下一轮再算。
     */
    private static final long MIN_SAMPLES = 5;

    // 达梦 Mapper：查出待调整的指标定义，并把新阈值写回 t_metric_def
    private final MetricDefMapper metricDefMapper;
    // TDengine 模板：统计最近 1 小时的 AVG / STDDEV / COUNT
    private final JdbcTemplate taosJdbcTemplate;
    // TDengine 子表名（t_perf_<主机名>），与 AnalysisLoop 用的是同一张表
    private final String subTable;

    // 构造注入：taosJdbcTemplate 由 @Qualifier 指定为 TDengine 时序库数据源；
    // 子表名在这里算一次，后续每轮复用
    public DynamicThresholdJob(MetricDefMapper metricDefMapper,
                               @Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate) {
        this.metricDefMapper = metricDefMapper;
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.subTable = TdengineTableNames.subTable(TdengineTableNames.localHostName());
    }

    // 每 5 分钟一轮（fixedDelay：上一轮结束后再等 5 分钟），首轮延迟 120 秒，
    // 留给采集器攒够"最近 1 小时"的样本；两个参数都可被 application.yml 覆盖
    @Scheduled(fixedDelayString = "${kms.analyser.dynamic-threshold-interval-ms:300000}",
            initialDelayString = "${kms.analyser.dynamic-threshold-initial-delay-ms:120000}")
    public void recompute() {
        log.info("Dynamic threshold recomputation started");
        // 只挑 dynamic_threshold = 1 的指标；用固定阈值的指标一行都不会被改
        List<MetricDef> dynamicDefs = metricDefMapper.selectList(
                new LambdaQueryWrapper<MetricDef>().eq(MetricDef::getDynamicThreshold, 1));
        for (MetricDef def : dynamicDefs) {
            if (!KmsMetrics.isKnownCode(def.getMetricCode())) {
                // 指标代码不在白名单里：下面的 SQL 会把 code 拼进语句，必须先拦住
                log.warn("Metric {} is not a known metric code, skipped", def.getMetricCode());
                continue;
            }
            try {
                // 逐指标隔离异常：某一项统计或更新失败，不影响其他动态指标
                recomputeMetric(def);
            } catch (Exception e) {
                log.error("Dynamic threshold recomputation failed for metric {}", def.getMetricCode(), e);
            }
        }
    }

    // 单项指标的阈值重算：一次聚合查询取回统计量 → 样本量校验 → 算 ±2σ → 定点更新
    private void recomputeMetric(MetricDef def) {
        String code = def.getMetricCode();
        // 把三个统计量放到同一条 SQL 里，避免为同一时间段跑三趟数据库
        // Mean / stddev / count of the last hour from TDengine (server clock,
        // Asia/Shanghai connection timezone).
        // 最近 1 小时的均值 / 标准差 / 样本数（用 TDengine 服务端时钟，连接时区 Asia/Shanghai）
        List<Double[]> stats = taosJdbcTemplate.query(
                "SELECT AVG(" + code + "), STDDEV(" + code + "), COUNT(" + code + ") "
                        + "FROM " + subTable + " WHERE ts >= NOW - 1h",
                (rs, i) -> {
                    // rs.wasNull() 是 JDBC 的"事后"检测手段，必须在 getXxx() 之后
                    // 立刻调用才有效：getDouble() 对 SQL NULL 只会返回 0.0，
                    // 单看返回值无法区分"真的是 0"和"是 NULL"
                    double avg = rs.getDouble(1);
                    Double avgVal = rs.wasNull() ? null : avg;
                    double std = rs.getDouble(2);
                    Double stdVal = rs.wasNull() ? null : std;
                    // COUNT 不会为 NULL，转成 Double 只是为了与数组类型统一；
                    // 查询没有 GROUP BY，所以结果恒为一行（下面的 stats.get(0)）
                    return new Double[]{avgVal, stdVal, (double) rs.getLong(3)};
                });
        // 无 GROUP BY 的聚合查询恒返回一行；样本数缺失时按 0 处理
        Double[] s = stats.get(0);
        long samples = s[2] == null ? 0 : s[2].longValue();
        if (samples < MIN_SAMPLES || s[0] == null) {
            log.info("Metric {}: only {} sample(s) in the last hour (need {}), "
                    + "thresholds left unchanged", code, samples, MIN_SAMPLES);
            // 样本不足或均值缺失 → 保持原阈值不动（不写任何值），下一轮再看
            return;
        }
        double mean = s[0];
        // std 为 NULL（例如只有 1 个样本时 STDDEV 可能返回空）按 0 处理，上下限都等于均值
        double std = s[1] == null ? 0.0 : s[1];
        // ±2σ 覆盖约 95% 的正常样本，越界即视为异常；
        // 下限用 Math.max(0.0, ...) 夹住：CPU/内存使用率出现负数没有物理意义
        double upper = mean + 2 * std;
        double lower = Math.max(0.0, mean - 2 * std);

        // 按 metric_code 定点更新上下限与 update_time（只动这三列，其余字段不受影响）
        LambdaUpdateWrapper<MetricDef> wrapper = Wrappers.<MetricDef>lambdaUpdate()
                .eq(MetricDef::getMetricCode, code)
                .set(MetricDef::getUpperThreshold, upper)
                .set(MetricDef::getLowerThreshold, lower)
                .set(MetricDef::getUpdateTime, LocalDateTime.now());
        metricDefMapper.update(null, wrapper);
        // 日志把新区间与统计依据一起打出来（样本数、均值、标准差），便于核对
        // "阈值为什么变成了这个值"，也便于判断动态阈值是否被异常数据带偏
        log.info("Metric {}: dynamic thresholds adjusted to [{}, {}] "
                        + "(mean {} ± 2×std {} over {} samples, last hour)",
                code, String.format(Locale.ROOT, "%.2f", lower), String.format(Locale.ROOT, "%.2f", upper),
                String.format(Locale.ROOT, "%.2f", mean), String.format(Locale.ROOT, "%.2f", std), samples);
    }
}
