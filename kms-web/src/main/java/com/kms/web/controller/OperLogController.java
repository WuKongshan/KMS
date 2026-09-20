package com.kms.web.controller;

import com.kms.web.service.OperLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Audit-log query API for the "操作日志" page: who did what, when, from where.
 *
 * <p>【中文】"操作日志"页面的查询接口：谁在什么时候、从哪、做了什么、结果如何。
 * <p>【中文】整个控制器限制为 <b>ADMIN</b>：审计日志包含所有人的操作轨迹与来源 IP，
 * 属于管理信息，普通查看者无权访问。
 */
@RestController
@RequestMapping("/api/oper-logs")
@PreAuthorize("hasRole('ADMIN')")
public class OperLogController {

    private final OperLogService operLogService;

    public OperLogController(OperLogService operLogService) {
        this.operLogService = operLogService;
    }

    /**
     * Paged audit log with optional filters.
     *
     * <p>【中文】审计日志分页查询：支持按用户（模糊）、操作类型、模块、时间范围筛选；
     * 全部条件可选，未传则不加该条件。返回若依表格约定的 {@code {total, rows}}。
     */
    @GetMapping
    public Map<String, Object> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String operType,
            @RequestParam(required = false) String module,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime begin,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return operLogService.page(pageNum, pageSize, username, operType, module, begin, end);
    }
}
