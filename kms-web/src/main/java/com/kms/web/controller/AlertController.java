package com.kms.web.controller;

import com.kms.database.entity.AlertRecord;
import com.kms.web.service.AlertService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Alert management API (spec 4.4, item 4): list with filters, mark handled,
 * recent alerts for the dashboard.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /** Paged alert list; filters: level (1/2/3), status (0/1), time range. */
    @GetMapping
    public Map<String, Object> page(@RequestParam(defaultValue = "1") long pageNum,
                                    @RequestParam(defaultValue = "10") long pageSize,
                                    @RequestParam(required = false) Integer level,
                                    @RequestParam(required = false) Integer status,
                                    @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime begin,
                                    @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return alertService.page(pageNum, pageSize, level, status, begin, end);
    }

    /** Newest N alerts (dashboard scrolling bar). */
    @GetMapping("/recent")
    public List<AlertRecord> recent(@RequestParam(defaultValue = "10") int limit) {
        return alertService.recent(Math.max(1, Math.min(limit, 100)));
    }

    /** Mark an alert as handled (1) / unhandled (0). */
    @PutMapping("/{id}/status")
    public AlertRecord updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        try {
            AlertRecord record = alertService.updateStatus(id, body.get("status"));
            if (record == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预警记录不存在: " + id);
            }
            return record;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
