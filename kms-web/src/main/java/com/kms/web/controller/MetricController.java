package com.kms.web.controller;

import com.kms.database.entity.MetricLatest;
import com.kms.web.service.MetricQueryService;
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
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricController {

    private final MetricQueryService metricQueryService;

    public MetricController(MetricQueryService metricQueryService) {
        this.metricQueryService = metricQueryService;
    }

    /** Current value per metric (dashboard tiles / pie chart). */
    @GetMapping("/latest")
    public List<MetricLatest> latest() {
        return metricQueryService.latest();
    }

    /** All metrics over the last N minutes (real-time chart, 5 s refresh). */
    @GetMapping("/realtime")
    public List<Map<String, Object>> realtime(@RequestParam(defaultValue = "30") int minutes) {
        return metricQueryService.realtime(minutes);
    }

    /** One metric within a time range (history page); defaults to the last hour. */
    @GetMapping("/history")
    public List<Map<String, Object>> history(
            @RequestParam String metricCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime begin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        LocalDateTime endTime = end == null ? LocalDateTime.now() : end;
        LocalDateTime beginTime = begin == null ? endTime.minusHours(1) : begin;
        return metricQueryService.history(metricCode, beginTime, endTime);
    }
}
