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
 */
@Component
public class TdengineMetricWriter {

    private final JdbcTemplate taosJdbcTemplate;
    private final String subTable;

    public TdengineMetricWriter(@Qualifier("taosJdbcTemplate") JdbcTemplate taosJdbcTemplate) {
        this.taosJdbcTemplate = taosJdbcTemplate;
        this.subTable = TdengineTableNames.subTable(TdengineTableNames.localHostName());
    }

    public String getSubTable() {
        return subTable;
    }

    public void write(CollectedMetrics m) {
        String temp = m.getCpuTemp() == null
                ? "NULL"
                : String.format(Locale.ROOT, "%f", m.getCpuTemp());
        String sql = String.format(Locale.ROOT,
                "INSERT INTO %s VALUES (now, %f, %f, %f, %f, %f, %s)",
                subTable,
                m.getCpuUsage(), m.getMemUsage(), m.getDiskFreeGb(),
                m.getNetSendKbps(), m.getNetRecvKbps(), temp);
        taosJdbcTemplate.update(sql);
    }
}
