package com.kms.database.tdengine;

import java.net.InetAddress;

/**
 * TDengine table names for KMS. The supertable is {@code t_perf_realtime};
 * each monitored host gets one subtable named {@code t_perf_<sanitized-host>}
 * (spec 4.2: "t_perf_realtime 子表"). Shared by the collector (writes), the
 * analyser (reads for analysis) and the web module (reads for charts).
 */
public final class TdengineTableNames {

    public static final String SUPER_TABLE = "t_perf_realtime";

    /** "host" is a TDengine keyword, so the tag column is named host_name. */
    public static final String HOST_TAG = "host_name";

    private TdengineTableNames() {
    }

    public static String subTable(String host) {
        return "t_perf_" + sanitize(host);
    }

    /** TDengine table names only allow letters, digits and underscores. */
    public static String sanitize(String host) {
        return host.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
