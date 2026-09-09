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
 */
@Service
public class MetricQueryService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate taosJdbcTemplate;
    private final MetricLatestMapper metricLatestMapper;
    private final String subTable;

    public MetricQueryService(@Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate,
                              MetricLatestMapper metricLatestMapper) {
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.metricLatestMapper = metricLatestMapper;
        this.subTable = TdengineTableNames.subTable(TdengineTableNames.localHostName());
    }

    /** Current value per metric from DM8 (updated by the collector every cycle). */
    public List<MetricLatest> latest() {
        return metricLatestMapper.selectList(null);
    }

    /**
     * Recent time series of all metrics (real-time chart, default last 30 min).
     * Rows: {@code {ts: "yyyy-MM-dd HH:mm:ss", cpu_usage: .., mem_usage: .., ...}}.
     */
    public List<Map<String, Object>> realtime(int minutes) {
        int window = Math.max(1, Math.min(minutes, 24 * 60));
        Timestamp start = new Timestamp(System.currentTimeMillis() - window * 60_000L);
        List<Map<String, Object>> rows = taosJdbcTemplate.queryForList(
                "SELECT ts, cpu_usage, mem_usage, disk_free, net_send, net_recv, cpu_temp "
                        + "FROM " + subTable + " WHERE ts >= ? ORDER BY ts", start);
        return rows.stream().map(this::formatPoint).toList();
    }

    /**
     * Raw history of one metric within a time range (history query page).
     * Rows: {@code {ts: "yyyy-MM-dd HH:mm:ss", value: ..}}.
     */
    public List<Map<String, Object>> history(String metricCode, LocalDateTime begin, LocalDateTime end) {
        if (!KmsMetrics.isKnownCode(metricCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知指标: " + metricCode);
        }
        List<Map<String, Object>> rows = taosJdbcTemplate.queryForList(
                "SELECT ts, " + metricCode + " FROM " + subTable
                        + " WHERE ts >= ? AND ts <= ? ORDER BY ts",
                Timestamp.valueOf(begin), Timestamp.valueOf(end));
        return rows.stream().map(row -> {
            Map<String, Object> point = new LinkedHashMap<>();
            Object ts = row.get("ts");
            point.put("ts", ts instanceof Timestamp t ? t.toLocalDateTime().format(TS_FMT) : String.valueOf(ts));
            point.put("value", toDouble(row.get(metricCode)));
            return point;
        }).toList();
    }

    private Map<String, Object> formatPoint(Map<String, Object> row) {
        Map<String, Object> point = new LinkedHashMap<>();
        Object ts = row.get("ts");
        point.put("ts", ts instanceof Timestamp t ? t.toLocalDateTime().format(TS_FMT) : String.valueOf(ts));
        for (String code : KmsMetrics.CODES) {
            point.put(code, toDouble(row.get(code)));
        }
        return point;
    }

    private static Double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
