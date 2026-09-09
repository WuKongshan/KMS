package com.kms.web.controller;

import com.kms.web.dto.CollectorStatus;
import com.kms.web.service.CollectorProxyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops management API (spec 4.4, item 1): collector status, collection period
 * and enable/disable. Delegates to the collector's REST API on port 8081.
 */
@RestController
@RequestMapping("/api/ops/collector")
public class OpsController {

    private final CollectorProxyService proxyService;

    public OpsController(CollectorProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @GetMapping("/status")
    public CollectorStatus status() {
        return proxyService.getStatus();
    }

    @PostMapping("/period")
    public CollectorStatus updatePeriod(@RequestParam int period) {
        return proxyService.updatePeriod(period);
    }

    @PostMapping("/enable")
    public CollectorStatus enable() {
        return proxyService.setEnabled(true);
    }

    @PostMapping("/disable")
    public CollectorStatus disable() {
        return proxyService.setEnabled(false);
    }
}
