package com.kms.database.metric;

import java.util.List;

/**
 * The six KMS metric codes collected by module A (spec 4.2). Shared by the
 * analyser (column whitelist when building TDengine SQL) and the web module
 * (request validation) — metric codes are interpolated into SQL column
 * positions, so they must always be checked against this whitelist.
 */
public final class KmsMetrics {

    public static final List<String> CODES = List.of(
            "cpu_usage", "mem_usage", "disk_free", "net_send", "net_recv", "cpu_temp");

    private KmsMetrics() {
    }

    public static boolean isKnownCode(String code) {
        return CODES.contains(code);
    }
}
