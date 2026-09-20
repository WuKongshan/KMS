package com.kms.web.controller;

import com.kms.web.annotation.OperLog;
import com.kms.web.dto.LogTail;
import com.kms.web.service.LogQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Log viewer API for the web UI: list the services whose logs can be read and
 * tail one service's log file.
 *
 * <p>【中文】前端的"系统日志"接口：列出哪些服务的日志可读，以及读取某个服务日志文件的尾部。
 * <p>【中文】本层只做参数校验与异常翻译：服务名的白名单校验、行数钳制、关键字长度钳制
 * 都在 {@link LogQueryService} 内完成；未知服务名在这里翻译成 HTTP 400。
 * <p>【中文】注意：日志包含主机名、SQL 片段等运维信息，因此整个控制器限制为
 * <b>ADMIN 角色</b>（{@code @PreAuthorize}）——普通查看者（VIEWER）即使已登录也无权访问。
 */
@RestController
@RequestMapping("/api/logs")
@PreAuthorize("hasRole('ADMIN')")
public class LogController {

    private final LogQueryService logQueryService;

    public LogController(LogQueryService logQueryService) {
        this.logQueryService = logQueryService;
    }

    /**
     * Services whose log files were found, with the resolved path.
     *
     * <p>【中文】返回可读的日志服务列表（含解析到的文件路径），供页面下拉框展示。
     */
    @GetMapping("/services")
    public List<Map<String, Object>> services() {
        return logQueryService.services();
    }

    /**
     * Tail of one service's log file.
     *
     * <p>【中文】读取某个服务日志文件的尾部：{@code lines} 默认 200（上限 2000），
     * {@code keyword} 可选（不区分大小写，仅做包含匹配）。
     */
    @GetMapping
    @OperLog(module = "日志", type = "VIEW_LOG", desc = "查看 {0} 服务的日志")
    public LogTail query(@RequestParam(defaultValue = "analyser") String service,
                         @RequestParam(defaultValue = "200") int lines,
                         @RequestParam(required = false) String keyword) {
        // 白名单校验前置：服务名不是枚举值就直接 400，绝不带着用户输入去拼路径
        if (!logQueryService.isKnownService(service)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知服务: " + service);
        }
        return logQueryService.query(service, lines, keyword);
    }
}
