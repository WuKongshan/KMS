package com.kms.web.dto;

import lombok.Data;

/**
 * Login request body: {@code {"username": "...", "password": "..."}}.
 *
 * <p>【中文】登录请求体。密码只在请求体里传递（POST + JSON），不放在 URL 查询串中，
 * 避免被网关/代理日志记录下来。
 */
@Data
public class LoginRequest {

    private String username;

    private String password;
}
