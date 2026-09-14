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
 *
 * <p>【中文】运维管理 API（规格 4.4 第 1 条）：查询采集器状态、修改采集周期、启用/禁用采集。
 * <p>【中文】本类只做纯粹的请求转发，把 {@code /api/ops/collector/**} 映射到
 * {@code CollectorProxyService}，不包含任何业务逻辑。
 */
@RestController
@RequestMapping("/api/ops/collector")
public class OpsController {

    // 所有操作都委托给代理服务，本类不含任何业务逻辑。
    private final CollectorProxyService proxyService;

    public OpsController(CollectorProxyService proxyService) {
        this.proxyService = proxyService;
    }

    // 查询采集器状态：即使采集器不可达也返回 200 + available=false，保证运维页面能正常渲染。
    @GetMapping("/status")
    public CollectorStatus status() {
        return proxyService.getStatus();
    }

    // 修改采集周期（秒）：周期下限校验在 CollectorProxyService 中完成，失败时返回 400 / 502。
    @PostMapping("/period")
    public CollectorStatus updatePeriod(@RequestParam int period) {
        return proxyService.updatePeriod(period);
    }

    // 启用采集：转发到采集器的 /api/collector/enable。
    @PostMapping("/enable")
    public CollectorStatus enable() {
        return proxyService.setEnabled(true);
    }

    // 禁用采集：转发到采集器的 /api/collector/disable。
    @PostMapping("/disable")
    public CollectorStatus disable() {
        return proxyService.setEnabled(false);
    }
}
