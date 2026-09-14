package com.kms.collector.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kms.collector.model.CollectedMetrics;
import com.kms.database.entity.MetricLatest;
import com.kms.database.mapper.MetricLatestMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Updates the current values in DM8 {@code t_metric_latest} after each cycle
 * (spec 4.2: "optional, for fast display of latest values").
 *
 * <p>【中文】每个采集周期结束后刷新达梦 {@code t_metric_latest} 中的当前值
 * （规格 4.2：“可选，便于快速展示最新值”）。页面查最新值直接读达梦，
 * 不必每次扫描 TDengine 时序表。
 */
@Component
public class MetricLatestUpdater {

    private final MetricLatestMapper metricLatestMapper;

    public MetricLatestUpdater(MetricLatestMapper metricLatestMapper) {
        this.metricLatestMapper = metricLatestMapper;
    }

    // 六个指标编码与 t_metric_latest 主键一一对应，逐个更新当前值
    public void update(CollectedMetrics m) {
        LocalDateTime now = m.getCollectedAt();
        update("cpu_usage", m.getCpuUsage(), now);
        update("mem_usage", m.getMemUsage(), now);
        update("disk_free", m.getDiskFreeGb(), now);
        update("net_send", m.getNetSendKbps(), now);
        update("net_recv", m.getNetRecvKbps(), now);
        // 温度可能为 null：null 会被显式写入，用于清空传感器失效前的旧值
        update("cpu_temp", m.getCpuTemp(), now);
    }

    /**
     * Explicit set() so a null value (unsupported temperature) clears the column.
     *
     * <p>【中文】用显式的 set() 而不是 updateById(entity)：MyBatis-Plus 默认字段策略是
     * NOT_NULL，entity 中的 null 字段不会参与 SET 语句，那样温度传感器失效后旧值会永远残留。
     */
    private void update(String metricCode, Double value, LocalDateTime now) {
        LambdaUpdateWrapper<MetricLatest> wrapper = Wrappers.<MetricLatest>lambdaUpdate()
                .eq(MetricLatest::getMetricCode, metricCode)
                .set(MetricLatest::getCurrentValue, value)
                .set(MetricLatest::getUpdateTime, now);
        // 第一个参数传 null 是 MyBatis-Plus 的约定写法：条件与赋值全部由 wrapper 提供
        metricLatestMapper.update(null, wrapper);
    }
}
