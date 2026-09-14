package com.kms.collector.service;

import com.kms.collector.model.CollectedMetrics;
import com.kms.database.tdengine.TdengineTableNames;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Writes each collection cycle as one row into the host's t_perf_realtime
 * subtable (spec 4.2). Uses TDengine's {@code now} keyword so the server-side
 * clock (connection timezone Asia/Shanghai) applies.
 *
 * <p>Values are inlined as literals instead of a PreparedStatement: the
 * TDengine stmt API rejects {@code now} ("Invalid operation"), and all values
 * are locally computed doubles, so this is safe.
 *
 * <p>【中文】把每个采集周期作为一行写入本机子表 t_perf_realtime（规格 4.2）。
 * 时间戳使用 TDengine 的 {@code now} 关键字，以数据库服务端时间为准
 * （连接串已指定时区 Asia/Shanghai，避免容器与宿主机时区不一致）。
 *
 * <p>【中文】这里用字符串拼接而不是 PreparedStatement：{@code now} 是 TDengine 关键字，
 * 不能放在 {@code ?} 占位符位置（会报 "Invalid operation"）。安全性论证：拼进 SQL 的
 * 6 个值全部来自本地计算的 double，没有任何外部输入；表名也已由
 * {@link TdengineTableNames} 做过 sanitize，因此不存在注入风险。
 */
@Component
public class TdengineMetricWriter {

    private final JdbcTemplate taosJdbcTemplate;
    private final String subTable;

    // 启动时即确定本机子表名并缓存，避免每个周期重复计算（主机名变更需重启生效）
    public TdengineMetricWriter(@Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate) {
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.subTable = TdengineTableNames.subTable(TdengineTableNames.localHostName());
    }

    // 供日志/排查展示当前写入的 TDengine 子表名
    public String getSubTable() {
        return subTable;
    }

    // 写入一行时序数据：列顺序必须与超级表定义严格一致，否则不会报错而是静默串位
    public void write(CollectedMetrics m) {
        // 温度为 null 时拼 SQL 字面量 NULL（不加引号），保持该列类型仍是 DOUBLE
        String temp = m.getCpuTemp() == null
                ? "NULL"
                : String.format(Locale.ROOT, "%f", m.getCpuTemp());
        // Locale.ROOT 是必须的：某些区域设置小数点是逗号，拼进 SQL 会造成语法错误
        String sql = String.format(Locale.ROOT,
                "INSERT INTO %s VALUES (now, %f, %f, %f, %f, %f, %s)",
                subTable,
                m.getCpuUsage(), m.getMemUsage(), m.getDiskFreeGb(),
                m.getNetSendKbps(), m.getNetRecvKbps(), temp);
        // 列顺序固定为 ts, cpu_usage, mem_usage, disk_free, net_send, net_recv, cpu_temp，与 CREATE STABLE 一致
        taosJdbcTemplate.update(sql);
    }
}
