package com.kms.web.controller;

import com.kms.web.annotation.OperLog;
import com.kms.web.dto.CollectorStatus;
import com.kms.web.service.CollectorProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * <p>【中文】权限：查看状态对所有登录用户开放，但"改周期 / 启停采集"会直接影响数据采集，
 * 因此限制为 ADMIN 角色（方法级 {@code @PreAuthorize}）。
 */
@RestController
@RequestMapping("/api/ops/collector")
@Tag(name = "运维管理", description = "采集器状态查询与采集控制（改周期、启停）。写操作仅 ADMIN 可用。")
@SecurityRequirement(name = "bearerAuth")
public class OpsController {

    // 所有操作都委托给代理服务，本类不含任何业务逻辑。
    private final CollectorProxyService proxyService;

    public OpsController(CollectorProxyService proxyService) {
        this.proxyService = proxyService;
    }

    // 查询采集器状态：即使采集器不可达也返回 200 + available=false，保证运维页面能正常渲染。
    @GetMapping("/status")
    @Operation(summary = "查询采集器状态",
            description = "返回启停标志、采集周期、最近一次采集时间与指标。采集器不可达时仍返回 200 且 "
                    + "available=false（保证运维页面能正常渲染）。")
    public CollectorStatus status() {
        return proxyService.getStatus();
    }

    // 修改采集周期（秒）：周期下限校验在 CollectorProxyService 中完成，失败时返回 400 / 502。
    @PostMapping("/period")
    @PreAuthorize("hasRole('ADMIN')")
    @OperLog(module = "运维", type = "PERIOD", desc = "修改采集周期为 {0} 秒")
    @Operation(summary = "修改采集周期（秒）",
            description = "最小 60 秒，低于下限返回 400。配置写入数据库后立即唤醒采集循环，"
                    + "**无需重启即生效**（响应体直接返回生效后的周期）。仅 ADMIN，会写审计日志。")
    public CollectorStatus updatePeriod(@RequestParam int period) {
        return proxyService.updatePeriod(period);
    }

    // 启用采集：转发到采集器的 /api/collector/enable。
    @PostMapping("/enable")
    @PreAuthorize("hasRole('ADMIN')")
    @OperLog(module = "运维", type = "ENABLE", desc = "启用采集")
    @Operation(summary = "启用采集",
            description = "写库并立即唤醒采集循环开始采集（不必等停用期的 10 秒轮询）。仅 ADMIN。")
    public CollectorStatus enable() {
        return proxyService.setEnabled(true);
    }

    // 禁用采集：转发到采集器的 /api/collector/disable。
    @PostMapping("/disable")
    @PreAuthorize("hasRole('ADMIN')")
    @OperLog(module = "运维", type = "DISABLE", desc = "停用采集")
    @Operation(summary = "停用采集",
            description = "循环转为每 10 秒轮询配置、不再采集（便于停采维护）。仅 ADMIN。")
    public CollectorStatus disable() {
        return proxyService.setEnabled(false);
    }
}
