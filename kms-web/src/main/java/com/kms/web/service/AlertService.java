package com.kms.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kms.database.entity.AlertRecord;
import com.kms.database.mapper.AlertRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Alert management queries against DM8 {@code t_alert_record} (spec 4.4,
 * item 4): paged list with level/status/time filters, mark-handled updates
 * and the newest records for the dashboard marquee.
 *
 * <p>【中文】针对达梦 {@code t_alert_record} 的预警管理查询（规格 4.4 第 4 条）：
 * 支持按级别/状态/时间范围筛选的分页列表、标记处理，以及大屏跑马灯所需的最新记录。
 */
@Service
public class AlertService {

    // 达梦 t_alert_record 的 Mapper（MyBatis-Plus 提供 CRUD 与分页插件支持）。
    private final AlertRecordMapper alertRecordMapper;

    public AlertService(AlertRecordMapper alertRecordMapper) {
        this.alertRecordMapper = alertRecordMapper;
    }

    /**
     * Paged alerts; RuoYi-style response {@code {total, rows}}.
     *
     * <p>【中文】预警分页查询，返回若依（RuoYi）框架 {@code TableDataInfo} 的标准结构
     * {@code {total, rows}}：前端的 el-pagination 直接取 total、el-table 直接取 rows，无需额外适配。
     */
    public Map<String, Object> page(long pageNum, long pageSize, Integer level, Integer status,
                                    LocalDateTime begin, LocalDateTime end) {
        // eq(boolean condition, ...) / ge / le 等重载实现动态条件拼接：condition 为 false 时，
        // 该条件会被完全忽略；等价于传统的一串 if (x != null) wrapper.eq(...)，但写法更简洁。
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<AlertRecord>()
                .eq(level != null, AlertRecord::getAlertLevel, level)
                .eq(status != null, AlertRecord::getStatus, status)
                // create_time 的闭区间过滤，对应预警管理页的时间范围筛选。
                .ge(begin != null, AlertRecord::getCreateTime, begin)  // >= 下限
                .le(end != null, AlertRecord::getCreateTime, end)  // <= 上限
                .orderByDesc(AlertRecord::getCreateTime);
        Page<AlertRecord> page = alertRecordMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        // Map.of 生成不可变 Map，键名固定为 total/rows，正好与若依前端表格组件的约定一致。
        return Map.of("total", page.getTotal(), "rows", page.getRecords());
    }

    /**
     * Newest N alerts (dashboard scrolling bar).
     *
     * <p>【中文】最新 N 条预警（大屏顶部滚动条使用）。
     * <p>【中文】借助 MyBatis-Plus 分页插件实现 LIMIT：只取第 1 页的 limit 条并按创建时间倒序，
     * 避免把整张预警表拉回内存。limit 的取值钳制在 Controller 层完成。
     */
    public List<AlertRecord> recent(int limit) {
        Page<AlertRecord> page = alertRecordMapper.selectPage(new Page<>(1, limit),
                new LambdaQueryWrapper<AlertRecord>().orderByDesc(AlertRecord::getCreateTime));
        return page.getRecords();
    }

    /**
     * Mark an alert as handled (1) or unhandled (0).
     *
     * <p>【中文】把预警标记为已处理（1）或未处理（0）。
     * <p>【中文】值域校验不通过时抛 {@link IllegalArgumentException}，由 Controller 翻译成 HTTP 400；
     * 记录不存在时返回 {@code null} 而不是抛异常——“没找到”是正常结果而不是错误，
     * 由 Controller 决定它对应 HTTP 404。
     */
    public AlertRecord updateStatus(Long id, Integer status) {
        // 值域校验：status 只允许 0（未处理）或 1（已处理）。
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("status 必须为 0 或 1");
        }
        AlertRecord record = alertRecordMapper.selectById(id);
        if (record == null) {
            // 记录不存在属于正常业务结果：返回 null 交由 Controller 转换为 404，不抛异常。
            return null;
        }
        record.setStatus(status);
        // 手工维护更新时间（该实体未使用 MyBatis-Plus 自动填充），保证前端能看到处理时刻。
        record.setUpdateTime(LocalDateTime.now());
        alertRecordMapper.updateById(record);
        return record;
    }
}
