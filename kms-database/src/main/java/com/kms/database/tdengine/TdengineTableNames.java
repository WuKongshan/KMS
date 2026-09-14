package com.kms.database.tdengine;

import java.net.InetAddress;

/**
 * TDengine table names for KMS. The supertable is {@code t_perf_realtime};
 * each monitored host gets one subtable named {@code t_perf_<sanitized-host>}
 * (spec 4.2: "t_perf_realtime 子表"). Shared by the collector (writes), the
 * analyser (reads for analysis) and the web module (reads for charts).
 *
 * <p>【中文】KMS 的 TDengine 表名工具：超级表为 {@code t_perf_realtime}，
 * 每台被监控主机对应一张子表 {@code t_perf_<清理后的主机名>}（规格 4.2 中的"t_perf_realtime 子表"）。
 * 由采集模块（写入）、分析模块（读取分析）和 Web 模块（读取画图）共用；
 * 三个可执行模块都要用，所以放在 kms-database 中共享。
 */
public final class TdengineTableNames {

    // 超级表名：所有主机的时序数据都写入这张表的子表，查询时按标签过滤/聚合。
    public static final String SUPER_TABLE = "t_perf_realtime";

    /**
     * "host" is a TDengine keyword, so the tag column is named host_name.
     *
     * <p>【中文】因为 {@code host} 是 TDengine 的保留关键字，标签列不能叫 host，
     * 因此命名为 {@code host_name}。
     */
    public static final String HOST_TAG = "host_name";

    // 工具类禁止实例化：构造函数私有，类本身为 final。
    private TdengineTableNames() {
    }

    // 由主机名拼出子表名；主机名必须先经过 sanitize 清理，避免非法字符导致建表/SQL 语法错误。
    public static String subTable(String host) {
        return "t_perf_" + sanitize(host);
    }

    /**
     * TDengine table names only allow letters, digits and underscores.
     *
     * <p>【中文】TDengine 的表名只允许字母、数字和下划线三种字符，
     * 因此把主机名里其它所有字符（点、横线、中文等）统一替换成下划线。
     */
    public static String sanitize(String host) {
        // 用下划线替换所有不属于 [A-Za-z0-9_] 的字符：replaceAll 第二参数 "_" 是字面量，没有 $ 转义问题。
        return host.replaceAll("[^A-Za-z0-9_]", "_");
    }

    // 取本机主机名，采集器、分析器、Web 三个进程各自调用该方法，
    // 三方必须得到同一个名字，才能读写到同一张子表。
    public static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // DNS 反查失败（如离线或主机名未在 hosts 中登记）时兜底返回 "localhost"，
            // 保证启动不中断；此时三个进程若都失败仍会落到同名表上，数据依旧连得上。
            return "localhost";
        }
    }
}
