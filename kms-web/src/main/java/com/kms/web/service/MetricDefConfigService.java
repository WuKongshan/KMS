package com.kms.web.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kms.database.entity.MetricDef;
import com.kms.database.mapper.MetricDefMapper;
import com.kms.database.metric.KmsMetrics;
import com.kms.web.dto.MetricDefUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read/write access to metric threshold definitions ({@code t_metric_def}).
 *
 * <p>【中文】指标阈值定义的读写服务（达梦表 {@code t_metric_def}）。
 *
 * <p>【中文】这是管理端唯一能改阈值的入口。此前阈值的写入方只有两个：
 * 建表种子数据（只在行不存在时插入）与分析器的 {@code DynamicThresholdJob}（自动重算），
 * 因此想调阈值只能改代码或直接改库——本服务补上了这个缺口。
 *
 * <p>【中文】校验策略：能静态判断的问题（未知指标代码、区间不合法、开关取值非法、
 * 动态阈值与手工阈值冲突）都在这里抛 {@link IllegalArgumentException}，
 * 由控制器翻译成 HTTP 400；记录不存在这种"业务结果"返回 {@code null} 由控制器翻译成 404。
 */
@Service
public class MetricDefConfigService {

    private static final Logger log = LoggerFactory.getLogger(MetricDefConfigService.class);

    // 动态阈值开关的合法取值
    private static final int DYNAMIC_ON = 1;
    private static final int DYNAMIC_OFF = 0;

    private final MetricDefMapper metricDefMapper;

    public MetricDefConfigService(MetricDefMapper metricDefMapper) {
        this.metricDefMapper = metricDefMapper;
    }

    /**
     * All metric definitions in the canonical display order.
     *
     * <p>【中文】返回全部指标定义，按 {@link KmsMetrics#CODES} 的固定顺序排列。
     * 表里没有"显示顺序"列，若按主键排序会得到字母序（cpu_temp 跑最前），
     * 与页面/规格文档的指标顺序不一致；故用白名单列表的顺序做显式排序
     * （不在白名单里的脏数据排到最后，不会丢失）。
     */
    public List<MetricDef> list() {
        List<MetricDef> defs = metricDefMapper.selectList(null);
        List<MetricDef> ordered = new ArrayList<>(defs);
        ordered.sort(Comparator.comparingInt(def -> {
            int idx = KmsMetrics.CODES.indexOf(def.getMetricCode());
            return idx < 0 ? Integer.MAX_VALUE : idx;
        }));
        return ordered;
    }

    /**
     * Update one metric's threshold configuration.
     *
     * <p>【中文】修改单个指标的阈值配置，返回更新后的最新定义；记录不存在时返回 {@code null}。
     */
    public MetricDef update(String metricCode, MetricDefUpdateRequest request) {
        // 1) 指标代码必须走白名单：既防脏数据，也与查询侧/分析侧保持一致的口径
        if (metricCode == null || !KmsMetrics.isKnownCode(metricCode)) {
            throw new IllegalArgumentException("未知指标代码: " + metricCode);
        }
        MetricDef existing = metricDefMapper.selectById(metricCode);
        if (existing == null) {
            return null;
        }

        Integer dynamic = request.getDynamicThreshold();
        if (dynamic != null && dynamic != DYNAMIC_ON && dynamic != DYNAMIC_OFF) {
            throw new IllegalArgumentException("动态阈值开关只能为 0 或 1");
        }
        Double volatility = request.getVolatilityThreshold();
        if (volatility != null && volatility < 0) {
            throw new IllegalArgumentException("波动率阈值不能为负数");
        }
        Double upper = request.getUpperThreshold();
        Double lower = request.getLowerThreshold();
        // 开关列保持 0/1 两态：请求里没带该字段视为"关闭"，不留 NULL（避免出现第三种状态）
        boolean dynamicOn = dynamic != null && dynamic == DYNAMIC_ON;

        // 【动态指标的上下限归分析器所有】开启动态阈值后，上下限由 DynamicThresholdJob
        // 每 5 分钟按 mean±2σ 覆盖写。若此时还允许手工写入，管理员会看到"刚设的值 5 分钟内变了"，
        // 表现为改了没用。因此这里**忽略**请求中的上下限、保持库中现值（而不是报错）：
        // 报错会导致动态指标连波动率阈值都改不了——那是更糟的可用性问题。
        // 响应体返回落库后的真实值，调用方据此即可知道上下限未被采纳。
        Double upperToWrite = upper;
        Double lowerToWrite = lower;
        if (dynamicOn) {
            upperToWrite = existing.getUpperThreshold();
            lowerToWrite = existing.getLowerThreshold();
            if (upper != null || lower != null) {
                log.warn("Metric {} has dynamic threshold enabled: manual upper/lower ({}, {}) ignored, "
                        + "kept [{}, {}]", metricCode, upper, lower, upperToWrite, lowerToWrite);
            }
        } else if (upperToWrite == null && lowerToWrite == null) {
            // 【防静默失效】非动态指标若上下限同时为空，分析器对 NULL 阈值按"未知"处理（不触发预警），
            // 该指标再也不会产生阈值类预警，而页面上看不出任何异常。单边为空合法（disk_free 只关心下限）
            throw new IllegalArgumentException("上限与下限不能同时为空，否则该指标将不再产生阈值预警");
        }

        // 区间校验针对"最终要落库的值"（含未修改的一侧），因此"把下限提到上限之上"这类
        // 只改一侧的输入同样会被拦住；编辑弹窗始终同时提交两侧，不会因此产生使用障碍
        if (upperToWrite != null && lowerToWrite != null && upperToWrite <= lowerToWrite) {
            throw new IllegalArgumentException("上限阈值必须大于下限阈值");
        }

        // 定点更新：显式 .set() 四列 + update_time。
        // 必须用显式 set 而不是 updateById(entity)，因为 MyBatis-Plus 默认跳过 null 字段，
        // 那样"清除阈值"（把某列置 NULL）永远无法生效。
        // 【语义】PUT = 整体替换：未传的阈值字段会被清空，故编辑弹窗始终提交全部四个字段
        metricDefMapper.update(null, Wrappers.<MetricDef>lambdaUpdate()
                .eq(MetricDef::getMetricCode, metricCode)
                .set(MetricDef::getUpperThreshold, upperToWrite)
                .set(MetricDef::getLowerThreshold, lowerToWrite)
                .set(MetricDef::getVolatilityThreshold, volatility)
                .set(MetricDef::getDynamicThreshold, dynamicOn ? DYNAMIC_ON : DYNAMIC_OFF)
                .set(MetricDef::getUpdateTime, LocalDateTime.now()));

        log.info("Metric def updated: {} -> upper={}, lower={}, volatility={}, dynamic={}",
                metricCode, upperToWrite, lowerToWrite, volatility, dynamicOn ? DYNAMIC_ON : DYNAMIC_OFF);
        // 回到库里读一次，返回服务端真实落库的值（而不是把请求体回显给前端）
        return metricDefMapper.selectById(metricCode);
    }
}
