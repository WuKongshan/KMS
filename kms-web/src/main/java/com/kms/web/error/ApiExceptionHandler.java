package com.kms.web.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders {@link ResponseStatusException} into a JSON error body that keeps the
 * readable reason, so the front-end can show it to the user.
 *
 * <p>【中文】把 {@code ResponseStatusException} 渲染成带可读原因的 JSON 错误体。
 *
 * <p>【中文】为什么需要它：Spring Boot 默认 {@code server.error.include-message=never}，
 * 默认错误体里<b>只有 status/error/path，没有 message</b>。于是控制器里精心写的中文校验提示
 * （如"上限与下限不能同时为空…"）会被丢掉，前端拦截器只能退化成"请求失败"这类通用文案，
 * 用户根本不知道错在哪。
 *
 * <p>【中文】为什么不用 {@code server.error.include-message=always} 一行搞定：
 * 那个开关对<b>所有</b>错误生效，包括未预期的 500 —— 异常消息里可能带 SQL 片段、文件路径等
 * 内部信息。这里只处理 {@code ResponseStatusException}（即我们主动抛出的、带业务语义的错误），
 * 意外异常仍走 Spring 默认处理，不会泄露细节。
 *
 * <p>【中文】注意：401 由 Spring Security 的 entry point 直接发响应（不走本处理器），
 * 前端对 401 已有固定的"登录状态已失效"文案，因此不受影响。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            ResponseStatusException e, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        // 字段顺序与 Spring 默认错误体保持一致，前端/脚本按名取值即可
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", e.getStatusCode().value());
        body.put("error", e.getStatusCode().toString());
        // getReason() 就是 throw 时写的业务原因；为 null 时不放该字段（避免前端显示 "null"）
        if (e.getReason() != null) {
            body.put("message", e.getReason());
        }
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }
}
