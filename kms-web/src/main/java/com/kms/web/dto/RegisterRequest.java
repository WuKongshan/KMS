package com.kms.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Registration request body: {@code {"username": "...", "password": "...", "nickname": "..."}}.
 *
 * <p>【中文】注册请求体。与登录请求一样，密码只放在请求体里（POST + JSON），
 * 不进 URL 查询串，避免被网关/代理日志记录。
 *
 * <p>【中文】注意这里<b>刻意没有 role 字段</b>：角色由服务端强制赋为 VIEWER，
 * 不接受客户端传入。否则任何人注册时填 {@code "role":"ADMIN"} 就能给自己提权——
 * 这是注册接口最经典的安全漏洞。
 */
@Data
@Schema(description = "注册请求体（角色由服务端强制为 VIEWER，不可指定）")
public class RegisterRequest {

    @Schema(description = "登录名：3~20 位字母、数字、下划线或中文", example = "zhangsan", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(description = "密码：至少 6 位", example = "user123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Schema(description = "昵称（可选，为空时用登录名代替）", example = "张三")
    private String nickname;
}
