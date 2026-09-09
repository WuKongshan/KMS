package com.kms.web.service;

import com.kms.web.dto.CollectorStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proxy to the collector's REST control API on port 8081 (spec 4.4, item 1:
 * ops management reads collector status and changes its period / enabled
 * state). Every call degrades gracefully when the collector process is down.
 */
@Service
public class CollectorProxyService {

    private static final Logger log = LoggerFactory.getLogger(CollectorProxyService.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CollectorProxyService(RestTemplate collectorRestTemplate,
                                 @Value("${kms.web.collector-base-url:http://localhost:8081}") String baseUrl) {
        this.restTemplate = collectorRestTemplate;
        this.baseUrl = baseUrl;
    }

    public CollectorStatus getStatus() {
        try {
            return restTemplate.getForObject(baseUrl + "/api/collector/status", CollectorStatus.class);
        } catch (Exception e) {
            log.warn("Collector unreachable at {}: {}", baseUrl, e.getMessage());
            CollectorStatus unavailable = new CollectorStatus();
            unavailable.setAvailable(false);
            unavailable.setLastError("采集器不可达: " + e.getMessage());
            return unavailable;
        }
    }

    public CollectorStatus updatePeriod(int seconds) {
        if (seconds < 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "采集周期不能小于 60 秒");
        }
        try {
            return restTemplate.postForObject(baseUrl + "/api/collector/period?period=" + seconds,
                    null, CollectorStatus.class);
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "采集器拒绝了周期修改: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "采集器不可达: " + e.getMessage());
        }
    }

    public CollectorStatus setEnabled(boolean enabled) {
        String path = enabled ? "/api/collector/enable" : "/api/collector/disable";
        try {
            return restTemplate.postForObject(baseUrl + path, null, CollectorStatus.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "采集器不可达: " + e.getMessage());
        }
    }
}
