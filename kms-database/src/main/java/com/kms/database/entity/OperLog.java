package com.kms.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Operation audit log: who did what, when, from where, and whether it worked.
 *
 * <p>【中文】操作审计日志：谁、在什么时候、从哪里、做了什么、结果如何。
 * 与技术日志（Logback 写的 kms-*.log）是两回事：
 * 技术日志面向排障（线程、SQL、异常栈），审计日志面向追责与合规（人的行为）。
 *
 * <p>【中文】写入方是 Web 模块的审计切面（{@code OperLogAspect}），
 * 记录点由 {@code @com.kms.web.annotation.OperLog} 注解标注；
 * 读取方只有"操作日志"页面（ADMIN 可见）。
 */
@Data
@TableName("t_oper_log")
public class OperLog {

    /**
     * DM8 IDENTITY column.
     *
     * <p>【中文】达梦自增主键，与 t_alert_record 同一种主键策略。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    // 操作人登录名（登录失败时记录的是"尝试登录的用户名"）
    private String username;

    // 所属模块（用于界面分组与筛选）：认证 / 运维 / 预警 / 日志
    private String module;

    // 操作类型枚举：LOGIN / LOGOUT / PERIOD / ENABLE / DISABLE / ALERT_STATUS / VIEW_LOG
    private String operType;

    // 可读的操作描述（中文），如"修改采集周期为 90 秒"
    private String operDesc;

    // HTTP 方法（GET/POST/PUT），便于区分"查询"与"变更"
    private String requestMethod;

    // 请求路径
    private String requestUri;

    // 请求参数摘要（JSON 文本，已截断）：敏感参数（密码等）绝不落库
    private String requestParams;

    // 结果：0 成功 / 1 失败
    private Integer status;

    // 失败原因（成功时为 null）
    private String errorMsg;

    // 来源 IP（优先取 X-Forwarded-For 的第一段，兼容反向代理部署）
    private String ip;

    // 本次操作耗时（毫秒），用于发现慢操作
    private Long costMs;

    private LocalDateTime createTime;
}
