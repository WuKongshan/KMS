package com.kms.collector.controller;

import com.kms.collector.service.CollectorConfigService;
import com.kms.collector.state.CollectorState;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST control API for module D (spec 4.2): query the collector status, change
 * the collection period, enable/disable collection. The status includes the
 * last failure message so callers can see collection errors.
 */
@RestController
@RequestMapping("/api/collector")
public class CollectorController {

    private final CollectorConfigService configService;

    public CollectorController(CollectorConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/status")
    public CollectorState status() {
        return configService.getStatus();
    }

    @PostMapping("/period")
    public CollectorState updatePeriod(@RequestParam int period) {
        try {
            configService.updatePeriod(period);
            return configService.getStatus();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/enable")
    public CollectorState enable() {
        configService.setEnabled(true);
        return configService.getStatus();
    }

    @PostMapping("/disable")
    public CollectorState disable() {
        configService.setEnabled(false);
        return configService.getStatus();
    }
}
