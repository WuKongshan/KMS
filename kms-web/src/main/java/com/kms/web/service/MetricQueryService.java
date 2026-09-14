package com.kms.web.service;

import com.kms.database.entity.MetricLatest;
import com.kms.database.mapper.MetricLatestMapper;
import com.kms.database.metric.KmsMetrics;
import com.kms.database.tdengine.TdengineTableNames;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metric queries for the web UI: current values from DM8 {@code t_metric_latest}
 * and time series from TDengine {@code t_perf_realtime} (spec 4.4, items 2-3).
 * Metric codes are interpolated into SQL column positions, so they are always
 * validated against the {@link KmsMetrics} whitelist first.
 *
 * <p>【中文】Web 界面使用的指标查询服务：当前值取自达梦 {@code t_metric_latest}，
 * 时序数据取自 TDengine {@code t_perf_realtime}（规格 4.4 第 2-3 条）。
 * <p>【中文】指标代码会被拼接进 SQL 的列名位置，因此任何拼接之前都必须先用
 * {@link KmsMetrics} 白名单校验来源合法性。
 */
@Service
public class MetricQueryService {

    // 时间戳统一格式化为 "yyyy-MM-dd HH:mm:ss"：Jackson 默认会输出带 T 和时区的 ISO 格式，
    // ECharts 的 X 轴标签会非常难看，因此在服务端就格式化好再返回给前端。
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // TDengine 时序库专用的 JdbcTemplate（由 @Qualifier 指定次数据源），负责 realtime/history 查询。
    private final JdbcTemplate taosJdbcTemplate;
    // 达梦主库 t_metric_latest 的 Mapper，负责读取 6 行最新值小表。
    private final MetricLatestMapper metricLatestMapper;
    private final String subTable;

    public MetricQueryService(@Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate,
                              MetricLatestMapper metricLatestMapper) {
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.metricLatestMapper = metricLatestMapper;
        // 子表名 = t_perf_<清理后的主机名>，与采集器写入时使用的规则完全一致（双方共用 TdengineTableNames）。
        this.subTable = TdengineTableNames.subTable(TdengineTableNames.localHostName());
    }

    /**
     * Current value per metric from DM8 (updated by the collector every cycle).
     *
     * <p>【中文】每项指标的当前值取自达梦 {@code t_metric_latest}（6 行小表，由采集器每个周期 UPDATE），
     * 而不是去 TDengine 取最新点。
     * <p>【中文】这张表是一层“物化缓存表”：用写入侧的小代价，换取所有读取方（大屏磁贴、饼图）
     * 的极低成本。大屏每 5 秒刷新一次，10 个客户端就意味着每 5 秒 60 次时序库查询，
     * 直接查 TDengine 是不可接受的。
     */
    public List<MetricLatest> latest() {
        return metricLatestMapper.selectList(null);
    }

    /**
     * Recent time series of all metrics (real-time chart, default last 30 min).
     * Rows: {@code {ts: "yyyy-MM-dd HH:mm:ss", cpu_usage: .., mem_usage: .., ...}}.
     *
     * <p>【中文】全部指标在最近 N 分钟内的时序数据（实时监控折线图，前端默认取最近 30 分钟）。
     * 返回行结构为 {@code {ts: "yyyy-MM-dd HH:mm:ss", cpu_usage: .., mem_usage: .., ...}}。
     */
    public List<Map<String, Object>> realtime(int minutes) {
        // 参数钳制：下限 1 分钟、上限 24 小时。若不钳制，minutes=10000000 会拉回几百万行数据，
        // 把 TDengine 和应用内存一起拖垮；负值则会使 ts >= 未来时间，查询结果恒为空。
        int window = Math.max(1, Math.min(minutes, 24 * 60));
        Timestamp start = new Timestamp(System.currentTimeMillis() - window * 60_000L);
        // 一条 SQL 取回全部 6 条序列：超级表把 6 个指标放在同一行，无需按指标分多次查询。
        List<Map<String, Object>> rows = taosJdbcTemplate.queryForList(
                "SELECT ts, cpu_usage, mem_usage, disk_free, net_send, net_recv, cpu_temp "
                        + "FROM " + subTable + " WHERE ts >= ? ORDER BY ts", start);
        return rows.stream().map(this::formatPoint).toList();
    }

    /**
     * Raw history of one metric within a time range (history query page).
     * Rows: {@code {ts: "yyyy-MM-dd HH:mm:ss", value: ..}}.
     *
     * <p>【中文】单项指标在指定时间范围内的原始历史数据（历史查询页）。
     * 返回行结构为 {@code {ts: "yyyy-MM-dd HH:mm:ss", value: ..}}。
     */
    public List<Map<String, Object>> history(String metricCode, LocalDateTime begin, LocalDateTime end) {
        // 第一步就是白名单校验：metricCode 会被拼进 SQL 的列名位置，而 JDBC 的 ? 占位符
        // 只能绑定值、不能绑定标识符（列名），所以列名只能字符串拼接，必须校验来源。
        // 这是一个真实的 SQL 注入防护点，不能以“内部调用”为由省略。
        if (!KmsMetrics.isKnownCode(metricCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知指标: " + metricCode);
        }
        List<Map<String, Object>> rows = taosJdbcTemplate.queryForList(
                "SELECT ts, " + metricCode + " FROM " + subTable
                        + " WHERE ts >= ? AND ts <= ? ORDER BY ts",
                Timestamp.valueOf(begin), Timestamp.valueOf(end));
        return rows.stream().map(row -> {
            // LinkedHashMap：保证 JSON 字段顺序固定为 ts、value，前端取值不依赖偶然的字段顺序。
            Map<String, Object> point = new LinkedHashMap<>();
            Object ts = row.get("ts");
            // instanceof Timestamp 判断是因为不同 JDBC 驱动返回的时间戳类型可能不同；
            // 兜底 String.valueOf(ts) 保证任何类型都不会抛异常。
            point.put("ts", ts instanceof Timestamp t ? t.toLocalDateTime().format(TS_FMT) : String.valueOf(ts));
            // 数值统一转换为 Double，避免驱动返回 Integer/BigDecimal/Float 时前端类型不一致。
            point.put("value", toDouble(row.get(metricCode)));
            return point;
        }).toList();
    }

    // 把 TDengine 返回的一行原始记录整理成前端可直接消费的 JSON 点（时间戳 + 6 个指标）。
    private Map<String, Object> formatPoint(Map<String, Object> row) {
        Map<String, Object> point = new LinkedHashMap<>();
        Object ts = row.get("ts");
        point.put("ts", ts instanceof Timestamp t ? t.toLocalDateTime().format(TS_FMT) : String.valueOf(ts));
        // 遍历 KmsMetrics.CODES 而不是 row.keySet()：保证即使库中缺少某个指标列，
        // 输出字段的集合与顺序也完全固定，前端图例不会时有时无。
        for (String code : KmsMetrics.CODES) {
            point.put(code, toDouble(row.get(code)));
        }
        return point;
    }

    // 统一把任意数值对象转换为 Double；用 instanceof Number 判断而不是强制类型转换，更宽容。
    private static Double toDouble(Object value) {
        // 非数值类型（含 null）统一降级为 null，交由前端按空点处理，不抛异常。
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
