package com.kms.web.dto;

import lombok.Data;

/**
 * Login response: the JWT plus the identity info the UI needs to render the header.
 *
 * <p>【中文】登录响应：JWT 令牌 + 前端顶栏所需的身份信息。
 * <p>【中文】不返回密码哈希、也不返回用户 id 等无关字段——
 * 响应体里只放前端真正要用的东西。
 */
@Data
public class LoginResponse {

    // JWT：前端存 localStorage，之后每次请求放在 Authorization 头里
    private String token;

    private String username;

    // 显示名，可为空（前端回退显示 username）
    private String nickname;

    // 角色：ADMIN / VIEWER
    private String role;
}
