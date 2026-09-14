package com.kms.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Latest value per metric, updated by the collector after every cycle — used
 * for quick display without querying the time-series database (spec 4.2).
 *
 * <p>【中文】各指标的当前值，采集器每轮采集结束后更新，供前端快速展示而无需查询时序库（规格 4.2）。
 * 写入方为采集模块的 MetricLatestUpdater；读取方为 Web 的 MetricQueryService / MetricController 最新值接口。
 */
@Data
@TableName("t_metric_latest")
public class MetricLatest {

    /**
     * Metric code: cpu_usage, mem_usage, disk_free, net_send, net_recv, cpu_temp.
     *
     * <p>【中文】指标代码（cpu_usage / mem_usage / disk_free / net_send / net_recv / cpu_temp），
     * 由种子数据插入并直接充当主键；用 IdType.INPUT 而非自增，因为主键本身就是业务标识，
     * 采集模块按它来定位要更新的那一行。
     */
    @TableId(value = "metric_code", type = IdType.INPUT)
    private String metricCode;

    // 指标名称，由建表种子数据写入；只写不读——前端展示用的是前端常量 METRIC_META，
    // 保留这一列是为了 DBA 直接读表时的可读性。
    private String metricName;

    // 指标当前值，由采集模块每轮写入，前端"实时指标"卡片读取它；
    // 可为 NULL（例如 cpu_temp 在不支持温度传感器的主机上）；
    // 写入方必须用 LambdaUpdateWrapper 显式 .set()，因为 updateById 默认跳过 null 字段，旧值清不掉。
    private Double currentValue;

    // 计量单位（%、GB、KB/s、C），由种子数据写入；只写不读，
    // 前端单位同样取自前端常量 METRIC_META。
    private String unit;

    // 本行最后更新时间（等于该批次的采集时间），由采集模块写入；
    // 业务代码不读取，主要作为判断数据是否新鲜的审计线索。
    private LocalDateTime updateTime;
}
