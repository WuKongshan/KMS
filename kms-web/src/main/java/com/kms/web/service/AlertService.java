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
 */
@Service
public class AlertService {

    private final AlertRecordMapper alertRecordMapper;

    public AlertService(AlertRecordMapper alertRecordMapper) {
        this.alertRecordMapper = alertRecordMapper;
    }

    /** Paged alerts; RuoYi-style response {@code {total, rows}}. */
    public Map<String, Object> page(long pageNum, long pageSize, Integer level, Integer status,
                                    LocalDateTime begin, LocalDateTime end) {
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<AlertRecord>()
                .eq(level != null, AlertRecord::getAlertLevel, level)
                .eq(status != null, AlertRecord::getStatus, status)
                .ge(begin != null, AlertRecord::getCreateTime, begin)
                .le(end != null, AlertRecord::getCreateTime, end)
                .orderByDesc(AlertRecord::getCreateTime);
        Page<AlertRecord> page = alertRecordMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Map.of("total", page.getTotal(), "rows", page.getRecords());
    }

    /** Newest N alerts (dashboard scrolling bar). */
    public List<AlertRecord> recent(int limit) {
        Page<AlertRecord> page = alertRecordMapper.selectPage(new Page<>(1, limit),
                new LambdaQueryWrapper<AlertRecord>().orderByDesc(AlertRecord::getCreateTime));
        return page.getRecords();
    }

    /** Mark an alert as handled (1) or unhandled (0). */
    public AlertRecord updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("status 必须为 0 或 1");
        }
        AlertRecord record = alertRecordMapper.selectById(id);
        if (record == null) {
            return null;
        }
        record.setStatus(status);
        record.setUpdateTime(LocalDateTime.now());
        alertRecordMapper.updateById(record);
        return record;
    }
}
