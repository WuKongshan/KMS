package com.kms.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Application user for the login/RBAC layer (option A: hand-rolled Spring
 * Security + JWT, no RuoYi code). Passwords are stored as BCrypt hashes —
 * never in plain text.
 *
 * <p>【中文】登录与权限体系的用户表（方案 A：自建 Spring Security + JWT，不引入若依代码）。
 * 密码以 BCrypt 哈希存储，永不落明文；{@code role} 目前是两个取值：
 * {@code ADMIN}（可改配置、标记预警、看日志）与 {@code VIEWER}（只读）。
 * <p>【中文】之所以把主要角色做成单列而不是 user/role 多对多：本系统的权限边界只有
 * "能不能改配置"这一条，两张关联表带来的复杂度换不来实际收益；将来若出现更细的权限，
 * 再拆成 t_sys_user / t_sys_role / t_sys_user_role 三张表即可。
 */
@Data
@TableName("t_sys_user")
public class SysUser {

    /**
     * DM8 IDENTITY column.
     *
     * <p>【中文】达梦自增主键（BIGINT IDENTITY(1,1)），由数据库生成，
     * 与 t_alert_record 用的是同一种主键策略。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    // 登录名：唯一，建表时加唯一约束（登录查询按它匹配）
    private String username;

    // 密码的 BCrypt 哈希（形如 $2a$10$...，含盐）；不存明文，也不可逆
    private String password;

    // 显示名：顶栏问候语使用，可为空（空则回退显示 username）
    private String nickname;

    // 角色：ADMIN / VIEWER（见类注释）；用于后续接口级鉴权
    private String role;

    // 是否启用：1 启用 / 0 停用（停用后即使密码正确也无法登录）
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
