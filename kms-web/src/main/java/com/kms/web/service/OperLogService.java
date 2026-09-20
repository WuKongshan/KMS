package com.kms.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kms.database.entity.OperLog;
import com.kms.database.mapper.OperLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Writes and queries the operation audit log (who did what).
 *
 * <p>【中文】操作审计日志的写入与查询（谁做了什么）。
 *
 * <p>【中文】写入是<b>异步</b>的：审计落库不该拖慢用户请求，也不该因为审计表写失败
 * 而让业务操作失败（写失败只记 ERROR 日志，业务照常返回）。
 * 读取只有 ADMIN 可见（见 OperLogController）。
 */
@Service
public class OperLogService {

    private static final Logger log = LoggerFactory.getLogger(OperLogService.class);

    // 单次查询上限：防止前端传 pageSize=100000 把审计表整表拉回内存
    private static final long MAX_PAGE_SIZE = 200;

    private final OperLogMapper operLogMapper;

    public OperLogService(OperLogMapper operLogMapper) {
        this.operLogMapper = operLogMapper;
    }

    /**
     * Persist one audit record asynchronously.
     *
     * <p>【中文】异步写入一条审计记录。方法必须由<b>其它 Bean</b> 调用（切面、或是本类之外的服务），
     * 因为 @Async 依赖代理，同类内部调用不会异步。
     * 异常在这里被完全吞掉：审计失败绝不能影响业务流程。
     */
    @Async
    public void saveAsync(OperLog operLog) {
        try {
            if (operLog.getCreateTime() == null) {
                operLog.setCreateTime(LocalDateTime.now());
            }
            operLogMapper.insert(operLog);
        } catch (Exception e) {
            // 记 ERROR 但不上抛：审计是旁路，不能让它的故障扩散到业务
            log.error("Failed to persist operation log for user '{}' ({})",
                    operLog.getUsername(), operLog.getOperType(), e);
        }
    }

    /**
     * Paged audit query with optional filters.
     *
     * <p>【中文】审计日志分页查询，支持按用户、操作类型、模块、时间范围筛选（条件为空则忽略）。
     */
    public Map<String, Object> page(long pageNum, long pageSize, String username, String operType,
                                    String module, LocalDateTime begin, LocalDateTime end) {
        long size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        LambdaQueryWrapper<OperLog> wrapper = new LambdaQueryWrapper<OperLog>()
                // like 而不是 eq：审计查询常按用户名片段模糊找
                .like(username != null && !username.isBlank(), OperLog::getUsername, username)
                .eq(operType != null && !operType.isBlank(), OperLog::getOperType, operType)
                .eq(module != null && !module.isBlank(), OperLog::getModule, module)
                .ge(begin != null, OperLog::getCreateTime, begin)
                .le(end != null, OperLog::getCreateTime, end)
                // 最新的操作排在最前：审计最常看"刚刚发生了什么"
                .orderByDesc(OperLog::getCreateTime);
        Page<OperLog> page = operLogMapper.selectPage(new Page<>(Math.max(1, pageNum), size), wrapper);
        // 若依前端表格约定：{total, rows}
        return Map.of("total", page.getTotal(), "rows", page.getRecords());
    }
}
