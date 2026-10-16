package com.kms.web.controller;

import com.kms.database.entity.MetricDef;
import com.kms.web.annotation.OperLog;
import com.kms.web.dto.MetricDefUpdateRequest;
import com.kms.web.service.MetricDefConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Metric threshold configuration API (admin only).
 *
 * <p>【中文】指标阈值配置 API（仅 ADMIN）。补齐了"阈值只能改代码或直接改库"的缺口：
 * 读取全部指标定义、修改单个指标的上下限 / 波动率阈值 / 动态阈值开关。
 *
 * <p>【中文】本层只做参数绑定与异常到 HTTP 状态码的翻译：参数非法
 * （{@code IllegalArgumentException}）对应 400，记录不存在（Service 返回 {@code null}）对应 404。
 *
 * <p>【中文】与查询接口 {@code /api/metrics/**} 的区别：读接口对所有登录用户开放，
 * 本控制器整类限制为 ADMIN——改阈值会直接影响预警是否触发，属高风险配置。
 */
@RestController
@RequestMapping("/api/metrics/defs")
@Tag(name = "指标配置（仅 ADMIN）",
        description = "阈值配置读写：上下限、波动率阈值、动态阈值开关。修改会写入操作审计。")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class MetricDefController {

    private final MetricDefConfigService metricDefConfigService;

    public MetricDefController(MetricDefConfigService metricDefConfigService) {
        this.metricDefConfigService = metricDefConfigService;
    }

    /**
     * All metric definitions with their threshold configuration.
     *
     * <p>【中文】全部指标定义及其阈值配置，按白名单固定顺序返回（页面表格数据源）。
     */
    @GetMapping
    @Operation(summary = "查询全部指标阈值配置",
            description = "返回 6 项指标的上下限、波动率阈值、动态阈值开关与最后修改时间。仅 ADMIN。")
    public List<MetricDef> list() {
        return metricDefConfigService.list();
    }

    /**
     * Update one metric's threshold configuration.
     *
     * <p>【中文】修改单个指标的阈值配置，返回更新后的最新定义。
     */
    @PutMapping("/{metricCode}")
    @OperLog(module = "运维", type = "METRIC_DEF", desc = "修改指标 {0} 的阈值配置")
    @Operation(summary = "修改指标阈值配置", description = "修改上下限、波动率阈值与动态阈值开关")
    public MetricDef update(@PathVariable String metricCode,
                           @RequestBody MetricDefUpdateRequest request) {
        try {
            MetricDef updated = metricDefConfigService.update(metricCode, request);
            // Service 返回 null 表示记录不存在：属正常业务结果，翻译成 404 而非异常
            if (updated == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指标定义不存在: " + metricCode);
            }
            return updated;
        } catch (IllegalArgumentException e) {
            // 参数值域/组合非法：翻译成 400，并把可读原因返回给前端
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
