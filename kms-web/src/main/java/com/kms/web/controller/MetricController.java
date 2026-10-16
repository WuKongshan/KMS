package com.kms.web.controller;

import com.kms.database.entity.MetricLatest;
import com.kms.web.service.MetricQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Metric read APIs (spec 4.4): latest values (DM8), real-time window and
 * history (TDengine).
 *
 * <p>【中文】指标读取 API（规格 4.4）：最新值查达梦，实时窗口与历史查询查 TDengine。
 * <p>【中文】本层只负责参数默认值与格式转换，参数钳制与 SQL 白名单校验都在
 * {@code MetricQueryService} 中完成。
 */
@RestController
@RequestMapping("/api/metrics")
@Tag(name = "指标查询", description = "指标最新值（达梦）、实时窗口与历史查询（TDengine）。")
@SecurityRequirement(name = "bearerAuth")
public class MetricController {

    // 指标查询逻辑全部在 Service 层，Controller 只做参数绑定与默认值处理。
    private final MetricQueryService metricQueryService;

    public MetricController(MetricQueryService metricQueryService) {
        this.metricQueryService = metricQueryService;
    }

    /**
     * Current value per metric (dashboard tiles / pie chart).
     *
     * <p>【中文】每项指标的当前值（大屏磁贴 / 饼图使用）。响应体是 6 条记录的小数组，
     * 数据来自达梦的 {@code t_metric_latest} 缓存表。
     */
    @GetMapping("/latest")
    @Operation(summary = "各指标当前值",
            description = "返回 6 项指标的最新值，数据来自达梦缓存表 t_metric_latest（采集器每周期更新）。"
                    + "用于页面磁贴与饼图。")
    public List<MetricLatest> latest() {
        return metricQueryService.latest();
    }

    /**
     * All metrics over the last N minutes (real-time chart, 5 s refresh).
     *
     * <p>【中文】最近 N 分钟内全部指标的时序数据（实时监控折线图，前端每 5 秒刷新一次）。
     * <p>【中文】minutes 的具体钳制（1 分钟 ~ 24 小时）在 {@code MetricQueryService.realtime} 中完成，
     * 此处只提供默认值 30 分钟。
     */
    @GetMapping("/realtime")
    @Operation(summary = "最近 N 分钟全部指标",
            description = "时序数据来自 TDengine 超级表 t_perf_realtime，前端每 5 秒刷新。"
                    + "minutes 服务端钳制在 1~1440 分钟，默认 30。")
    public List<Map<String, Object>> realtime(@RequestParam(defaultValue = "30") int minutes) {
        return metricQueryService.realtime(minutes);
    }

    /**
     * One metric within a time range (history page); defaults to the last hour.
     *
     * <p>【中文】单项指标在指定时间范围内的历史数据（历史查询页），默认查询最近一小时。
     * <p>【中文】时间范围默认值在方法体内计算，而不使用 {@code @RequestParam(defaultValue = ...)}，
     * 原因有二：① defaultValue 只能是编译期常量字符串，而“一小时前”必须在运行期才能算出；
     * ② beginTime 依赖 endTime —— 用户只传 end 时，期望的是“end 之前的一小时”，
     * 而不是“现在之前的一小时”。
     */
    @GetMapping("/history")
    @Operation(summary = "单项指标历史查询",
            description = "默认查询最近 1 小时；只传 end 时窗口紧贴 end 而非当前时间。"
                    + "metricCode 走白名单校验（拼列名前过滤），非法值返回 400。")
    public List<Map<String, Object>> history(
            @RequestParam String metricCode,
            // @DateTimeFormat(iso = ISO.DATE_TIME) 让前端可以直接传 2025-01-15T10:00:00
            // （Element Plus 的 value-format="YYYY-MM-DDTHH:mm:ss" 正是这种输出格式）。
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime begin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        // 先确定 endTime，再据此推导 beginTime：这个计算顺序保证了“只传 end”时窗口紧贴 end 而非 now。
        LocalDateTime endTime = end == null ? LocalDateTime.now() : end;
        LocalDateTime beginTime = begin == null ? endTime.minusHours(1) : begin;
        // 指标代码的白名单校验在 MetricQueryService 内完成（列名拼接前的 SQL 注入防护）。
        return metricQueryService.history(metricCode, beginTime, endTime);
    }
}
