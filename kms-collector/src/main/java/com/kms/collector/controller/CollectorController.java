package com.kms.collector.controller;

import com.kms.collector.service.CollectorConfigService;
import com.kms.collector.state.CollectorState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 *
 * <p>【中文】供 Web 模块（规格中的模块 D）调用的 REST 控制 API（规格 4.2）：
 * 查询采集器状态、修改采集周期、启用/停用采集。状态对象中包含最后一次失败信息，
 * 调用方据此即可感知采集异常。
 *
 * <p>【中文】所有写操作都返回最新的完整状态对象，前端一次调用就能拿到结果，
 * 不需要“改完再查一次”。
 */
@RestController
@RequestMapping("/api/collector")
@Tag(name = "采集器控制（内部 API，无鉴权）",
        description = "Web 模块转发调用的内部接口：查询状态、改采集周期、启停采集。生产环境应限制来源 IP。")
public class CollectorController {

    private final CollectorConfigService configService;

    // 只依赖业务服务，Controller 不直接访问数据库
    public CollectorController(CollectorConfigService configService) {
        this.configService = configService;
    }

    // 查询采集器当前状态：启停标志、周期、最近一次采集时间/指标/错误消息
    @GetMapping("/status")
    @Operation(summary = "查询采集器状态", description = "返回启停标志、采集周期、最近一次采集时间/指标/错误信息。")
    public CollectorState status() {
        return configService.getStatus();
    }

    // 修改采集周期（秒），周期小于 60 由 Service 抛 IllegalArgumentException
    @PostMapping("/period")
    @Operation(summary = "修改采集周期（秒）",
            description = "下限 60 秒，低于下限抛业务异常并翻译成 HTTP 400。写库后立即唤醒采集循环，无需重启。")
    public CollectorState updatePeriod(@RequestParam int period) {
        try {
            configService.updatePeriod(period);
            // 返回最新状态，前端一次调用即可刷新界面
            return configService.getStatus();
        } catch (IllegalArgumentException e) {
            // 把业务层参数异常翻译成 HTTP 400，并保留原始错误消息
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // 启用采集并返回最新状态（循环会被唤醒，立即开始采集）
    @PostMapping("/enable")
    @Operation(summary = "启用采集", description = "写库并唤醒采集循环立即开始采集。返回最新状态。")
    public CollectorState enable() {
        configService.setEnabled(true);
        return configService.getStatus();
    }

    // 停用采集并返回最新状态（停用后循环只轮询配置，不再采集）
    @PostMapping("/disable")
    @Operation(summary = "停用采集", description = "停止采集，循环转为每 10 秒轮询配置。返回最新状态。")
    public CollectorState disable() {
        configService.setEnabled(false);
        return configService.getStatus();
    }
}
