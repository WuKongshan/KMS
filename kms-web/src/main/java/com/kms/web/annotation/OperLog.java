package com.kms.web.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method whose invocation must be written to the operation
 * audit log ({@code t_oper_log}).
 *
 * <p>【中文】标注需要写入操作审计日志（{@code t_oper_log}）的接口方法。
 * 由 {@code OperLogAspect} 在方法执行前后做记录：成功与失败都会落库。
 *
 * <p>【中文】用注解而不是在业务代码里手写日志语句：审计逻辑与业务逻辑解耦，
 * 新增一个受审计的接口只需加一行注解，不会漏记、也不会把业务代码写脏。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {

    /** Module shown in the UI, e.g. 认证 / 运维 / 预警 / 日志. 【中文】所属模块（界面分组与筛选用）。 */
    String module();

    /**
     * Operation type enum, e.g. LOGIN / LOGOUT / PERIOD / ENABLE / DISABLE /
     * ALERT_STATUS / VIEW_LOG. 【中文】操作类型枚举，供界面筛选与统计。
     */
    String type();

    /**
     * Human-readable description. Supports {@code {0}} style placeholders that
     * are replaced with the corresponding method argument (scalars only), so
     * "修改采集周期" can render as "修改采集周期为 90 秒".
     *
     * <p>【中文】可读的操作描述，支持 {@code {0}} 占位符——按方法参数下标替换（仅标量参数），
     * 例如描述写"修改采集周期为 {0} 秒"、参数 period=90，落库即"修改采集周期为 90 秒"。
     */
    String desc() default "";
}
