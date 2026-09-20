package com.kms.web.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kms.database.entity.OperLog;
import com.kms.web.service.OperLogService;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Records one audit row for every method annotated with
 * {@link com.kms.web.annotation.OperLog} — success and failure alike.
 *
 * <p>【中文】为每个标注 {@code @OperLog} 的方法写一条审计记录，成功与失败都记。
 * 记录内容：谁（当前登录用户）、什么时候、从哪个 IP、调了哪个接口、带了什么参数、
 * 耗时多少、成功还是失败（失败带原因）。
 *
 * <p>【中文】三条硬性约束：
 * ① <b>绝不影响业务</b>——本切面的任何异常都被吞掉并记 ERROR，业务照常执行；
 * ② <b>绝不落敏感值</b>——参数序列化后会把 password/token/secret 的值打码，
 *    因此登录接口即使带密码参数，库里也只会看到掩码；
 * ③ <b>写入异步</b>——交给 {@link OperLogService#saveAsync} 在其它线程落库，
 *    不给用户请求增加等待时间。
 */
@Aspect
@Component
public class OperLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperLogAspect.class);

    // 参数摘要的最大长度：与表字段 VARCHAR(1000) 对齐，避免超长导致插入失败
    private static final int MAX_PARAMS_LENGTH = 900;
    // 错误信息最大长度：与表字段 VARCHAR(512) 对齐
    private static final int MAX_ERROR_LENGTH = 450;
    // 敏感字段：JSON 里这些 key 的值统一替换为 ***
    private static final Pattern SENSITIVE_JSON = Pattern.compile(
            "(\"(?:password|passwd|pwd|token|secret|authorization)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private final OperLogService operLogService;
    private final ObjectMapper objectMapper;

    public OperLogAspect(OperLogService operLogService, ObjectMapper objectMapper) {
        this.operLogService = operLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * Wrap the annotated method: time it, record the outcome, never interfere.
     *
     * <p>【中文】环绕被注解的方法：计时 → 执行 → 记录结果。异常原样抛出（业务语义不变），
     * 只是在抛出前先记一条失败审计。
     */
    @Around("@annotation(operLogAnnotation)")
    public Object around(ProceedingJoinPoint joinPoint, com.kms.web.annotation.OperLog operLogAnnotation)
            throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            record(joinPoint, operLogAnnotation, System.currentTimeMillis() - start, 0, null);
            return result;
        } catch (Throwable t) {
            record(joinPoint, operLogAnnotation, System.currentTimeMillis() - start, 1, t.getMessage());
            // 审计已记下，异常继续向上抛：调用方看到的行为与没有审计时完全一致
            throw t;
        }
    }

    // 组装并异步落库一条审计记录；任何异常都在此吞掉（审计是旁路）
    private void record(ProceedingJoinPoint joinPoint, com.kms.web.annotation.OperLog annotation,
                        long costMs, int status, String errorMsg) {
        try {
            OperLog row = new OperLog();
            row.setUsername(currentUsername(joinPoint));
            row.setModule(annotation.module());
            row.setOperType(annotation.type());
            row.setOperDesc(renderDesc(annotation.desc(), joinPoint.getArgs()));
            row.setStatus(status);
            row.setErrorMsg(truncate(errorMsg, MAX_ERROR_LENGTH));
            row.setCostMs(costMs);

            HttpServletRequest request = currentRequest();
            if (request != null) {
                row.setRequestMethod(request.getMethod());
                row.setRequestUri(request.getRequestURI());
                row.setIp(clientIp(request));
            }
            row.setRequestParams(describeArgs(joinPoint.getArgs()));

            operLogService.saveAsync(row);
        } catch (Exception e) {
            log.error("Failed to build operation log for {}", annotation.type(), e);
        }
    }

    /**
     * Current user's login name; falls back to the {@code username} argument for
     * failed logins where nobody is authenticated yet.
     *
     * <p>【中文】当前登录用户名。登录失败时上下文里没有用户，此时回退到参数里的用户名
     * （通过反射找名为 username 的取值方法），否则审计里只会看到"匿名"，等于记了没用。
     */
    private String currentUsername(ProceedingJoinPoint joinPoint) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return usernameFromArgs(joinPoint.getArgs());
    }

    // 反射从参数对象里取 username（登录请求体）；取不到就返回"匿名"
    private String usernameFromArgs(Object[] args) {
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            try {
                Method getter = arg.getClass().getMethod("getUsername");
                Object value = getter.invoke(arg);
                if (value instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (Exception ignored) {
                // 没有 getUsername 就继续找下一个参数，这是正常的鸭子类型探测
            }
        }
        return "匿名";
    }

    // 渲染描述模板里的 {0} {1} 占位符（仅支持标量参数）
    private String renderDesc(String template, Object[] args) {
        if (template == null || template.isEmpty() || !template.contains("{")) {
            return template;
        }
        String rendered = template;
        for (int i = 0; i < args.length; i++) {
            String placeholder = "{" + i + "}";
            if (!rendered.contains(placeholder)) {
                continue;
            }
            rendered = rendered.replace(placeholder, String.valueOf(args[i]));
        }
        return rendered;
    }

    // 参数摘要：JSON 化 + 打码敏感字段 + 截断；不可序列化的参数（请求/响应/上传文件）跳过
    private String describeArgs(Object[] args) {
        List<Object> printable = new ArrayList<>();
        for (Object arg : args) {
            // 跳过无法/不该序列化的参数：请求与响应对象、上传文件、二进制数组
            boolean binaryArray = arg != null && arg.getClass().isArray() && arg instanceof byte[];
            if (arg == null || arg instanceof ServletRequest || arg instanceof ServletResponse
                    || arg instanceof MultipartFile || binaryArray) {
                continue;
            }
            // 其它数组参数（如权限列表）转成可读字符串
            if (arg.getClass().isArray()) {
                printable.add(arrayToString(arg));
                continue;
            }
            printable.add(arg);
        }
        if (printable.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(printable.size() == 1 ? printable.get(0) : printable);
            return truncate(SENSITIVE_JSON.matcher(json).replaceAll("$1\"***\""), MAX_PARAMS_LENGTH);
        } catch (Exception e) {
            // 序列化失败（参数含循环引用等）不能让审计影响业务，返回类型提示即可
            return "<unserializable:" + printable.get(0).getClass().getSimpleName() + ">";
        }
    }

    // 数组转字符串，避免把二进制内容写进审计
    private String arrayToString(Object array) {
        int length = Array.getLength(array);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Array.get(array, i));
        }
        return sb.append(']').toString();
    }

    // 取当前 HTTP 请求；非 Web 线程（如异步任务）返回 null，调用方按"无请求信息"处理
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    // 客户端 IP：反向代理场景优先取 X-Forwarded-For 的第一段（最靠近客户端的地址）
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return first.trim();
        }
        return request.getRemoteAddr();
    }

    // 统一截断到指定长度（null 安全）
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
