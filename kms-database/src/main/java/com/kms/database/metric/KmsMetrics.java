package com.kms.database.metric;

import java.util.List;

/**
 * The six KMS metric codes collected by module A (spec 4.2). Shared by the
 * analyser (column whitelist when building TDengine SQL) and the web module
 * (request validation) — metric codes are interpolated into SQL column
 * positions, so they must always be checked against this whitelist.
 *
 * <p>【中文】模块 A（规格 4.2）采集的六个 KMS 指标代码。由分析模块（拼装 TDengine SQL 时的
 * 列名白名单）与 Web 模块（请求参数校验）共用。之所以要有这份白名单，根本原因是<b>防 SQL 注入</b>：
 * 这些代码会被直接拼接进 SQL 的<b>列名位置</b>（如 {@code SELECT ts, cpu_usage FROM ...}），
 * 而 JDBC 的 {@code ?} 占位符只能绑定<b>值</b>、不能绑定标识符，所以列名只能字符串拼接，
 * 拼接前就必须校验来源合法性。校验共出现在三处：{@code MetricQueryService.history()}（Web 请求参数）、
 * {@code AnalysisLoop.runAnalysis()}、{@code DynamicThresholdJob.recompute()}。
 * 这三个类（连同 {@code TdengineTableNames}）被三个可执行模块共享，因此放在 kms-database 而非任一业务模块。
 */
public final class KmsMetrics {

    // 六个指标代码的白名单：CPU 使用率、内存使用率、磁盘剩余空间、网络发送、网络接收、CPU 温度。
    // 保持为不可变 List（List.of），避免任何模块在执行期往里增删元素而绕过校验。
    public static final List<String> CODES = List.of(
            "cpu_usage", "mem_usage", "disk_free", "net_send", "net_recv", "cpu_temp");

    // 工具类禁止实例化：构造函数私有，类本身为 final。
    private KmsMetrics() {
    }

    // 校验给定指标代码是否在白名单内；拼接 SQL 列名、或遍历指标前必须先调用本方法，
    // 返回 false 时调用方必须直接拒绝/跳过，绝不能带着未经校验的代码继续拼 SQL。
    public static boolean isKnownCode(String code) {
        return CODES.contains(code);
    }
}
