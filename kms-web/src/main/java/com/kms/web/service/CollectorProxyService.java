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
 *
 * <p>【中文】对采集器 8081 端口 REST 控制 API 的 HTTP 代理（规格 4.4 第 1 条：运维管理读取
 * 采集器状态、修改采集周期与启停开关）。
 * <p>【中文】核心设计是“读操作容错、写操作明确失败”：读状态在采集器不可达时降级为
 * {@code available=false} 的对象，写操作失败则抛 {@code ResponseStatusException}，
 * 让用户明确知道这次修改没有生效。
 */
@Service
public class CollectorProxyService {

    // 采集器不可达属于预期内的情况（会被降级处理），因此只记 WARN 级别，不刷 ERROR 干扰告警。
    private static final Logger log = LoggerFactory.getLogger(CollectorProxyService.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CollectorProxyService(RestTemplate collectorRestTemplate,
                                 @Value("${kms.web.collector-base-url:http://localhost:8081}") String baseUrl) {
        // 注入的 RestTemplate 已由 WebConfig 配好 2 秒连接 / 5 秒读取超时，此处不会无限等待。
        this.restTemplate = collectorRestTemplate;
        // 采集器地址可配置：默认 http://localhost:8081，可用 COLLECTOR_BASE_URL 环境变量覆盖。
        this.baseUrl = baseUrl;
    }

    public CollectorStatus getStatus() {
        // 读操作容错：采集器挂掉时，运维页面恰恰最需要能看到“采集器不可达”这一状态；
        // 如果读操作也抛异常，页面只会白屏，因此这里吞掉异常并返回 available=false 的对象。
        try {
            return restTemplate.getForObject(baseUrl + "/api/collector/status", CollectorStatus.class);
        } catch (Exception e) {
            log.warn("Collector unreachable at {}: {}", baseUrl, e.getMessage());
            CollectorStatus unavailable = new CollectorStatus();
            // 显式标记不可达，前端据此在运维页显示“采集器不可达”而不是空白。
            unavailable.setAvailable(false);
            unavailable.setLastError("采集器不可达: " + e.getMessage());
            return unavailable;
        }
    }

    public CollectorStatus updatePeriod(int seconds) {
        // 在发请求之前就校验下限：快速失败，避免一次无谓的网络往返。
        // 该下限与前端输入框限制、采集器侧校验、采集循环的 Math.max 兜底共同构成四道防线。
        if (seconds < 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "采集周期不能小于 60 秒");
        }
        try {
            return restTemplate.postForObject(baseUrl + "/api/collector/period?period=" + seconds,
                    null, CollectorStatus.class);
        } catch (HttpClientErrorException e) {
            // 采集器返回 4xx：说明请求本身被拒绝，翻译为 400 并原样带上采集器的错误响应体。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "采集器拒绝了周期修改: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            // 其余异常（连接被拒/超时/5xx）：统一翻译为 502，让用户明确知道本次修改没有生效。
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "采集器不可达: " + e.getMessage());
        }
    }

    public CollectorStatus setEnabled(boolean enabled) {
        // 启用与禁用共用一个方法：按布尔值选择采集器对应的 enable / disable 端点。
        String path = enabled ? "/api/collector/enable" : "/api/collector/disable";
        try {
            return restTemplate.postForObject(baseUrl + path, null, CollectorStatus.class);
        } catch (Exception e) {
            // 写操作必须明确失败：不能像 getStatus 那样降级返回，否则用户会误以为启停已经生效。
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "采集器不可达: " + e.getMessage());
        }
    }
}
