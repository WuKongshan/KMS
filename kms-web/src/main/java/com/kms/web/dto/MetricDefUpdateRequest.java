package com.kms.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Threshold-config update request for one metric definition.
 *
 * <p>【中文】单个指标阈值配置的修改请求体。
 *
 * <p>【中文】三个阈值字段允许传 {@code null} 表示"清除该阈值"——例如 disk_free 只关心下限，
 * 上限本就应该是 NULL。服务端用显式 {@code .set()} 更新（而不是 {@code updateById}），
 * 否则 MyBatis-Plus 的默认字段策略会跳过 null，旧值永远清不掉。
 *
 * <p>【中文】协议约定：当 {@code dynamicThreshold = 1} 时，上下限由分析器每 5 分钟
 * 按 mean±2σ 自动重算，因此此时不允许同时提交上下限——否则管理员刚设的固定值会在
 * 5 分钟内被覆盖掉，表现为"改了没用"。这种组合会被服务端明确拒绝（400）。
 */
@Data
@Schema(description = "指标阈值配置修改请求")
public class MetricDefUpdateRequest {

    @Schema(description = "上限阈值：指标值高于它即越限；传 null 表示清除（如 disk_free 只设下限）",
            example = "90.0")
    private Double upperThreshold;

    @Schema(description = "下限阈值：指标值低于它即越限；传 null 表示清除", example = "0.0")
    private Double lowerThreshold;

    @Schema(description = "相邻点波动率上限（百分比），≥ 0；动态阈值任务不会修改它", example = "10.0")
    private Double volatilityThreshold;

    @Schema(description = "动态阈值开关：1 = 每 5 分钟按最近 1 小时 mean±2σ 自动重算上下限；0 = 使用固定阈值",
            example = "0", allowableValues = {"0", "1"})
    private Integer dynamicThreshold;
}
