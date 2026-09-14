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
 *
 * <p>【中文】预警管理 API（规格 4.4 第 4 条）：带筛选条件的分页列表、标记已处理、
 * 大屏所需的最新预警。
 * <p>【中文】本层的职责是把 Service 的异常语义翻译成 HTTP 状态码：参数非法
 * （{@code IllegalArgumentException}）对应 400，记录不存在（返回 {@code null}）对应 404。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    // 预警查询逻辑位于 Service 层，Controller 只做参数绑定与异常到 HTTP 状态码的翻译。
    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * Paged alert list; filters: level (1/2/3), status (0/1), time range.
     *
     * <p>【中文】预警分页列表；筛选条件：级别 level（1/2/3）、状态 status（0/1）、时间范围。
     * <p>【中文】所有筛选条件都是可选的：未传时对应条件不参与 SQL 拼接（由 Service 的
     * LambdaQueryWrapper condition 参数实现），pageNum/pageSize 有默认值。
     */
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

    /**
     * Newest N alerts (dashboard scrolling bar).
     *
     * <p>【中文】最新 N 条预警（大屏顶部滚动跑马灯使用）。
     */
    @GetMapping("/recent")
    public List<AlertRecord> recent(@RequestParam(defaultValue = "10") int limit) {
        // 参数钳制：限制在 1 ~ 100 之间，防止前端传入 limit=100000 导致预警表被整表扫描。
        return alertService.recent(Math.max(1, Math.min(limit, 100)));
    }

    /**
     * Mark an alert as handled (1) / unhandled (0).
     *
     * <p>【中文】标记预警为已处理（1）/ 未处理（0）。
     * <p>【中文】状态值放在请求体中传入（{@code {"status": 1}}），记录 id 走路径变量。
     */
    @PutMapping("/{id}/status")
    public AlertRecord updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        try {
            AlertRecord record = alertService.updateStatus(id, body.get("status"));
            // Service 返回 null 表示记录不存在：这是正常业务结果而非异常，翻译成 HTTP 404。
            if (record == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预警记录不存在: " + id);
            }
            return record;
        } catch (IllegalArgumentException e) {
            // status 值域非法等参数问题：翻译成 HTTP 400，并把原始提示信息返回给前端。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
