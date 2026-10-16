package com.kms.web.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kms.database.entity.MetricDef;
import com.kms.database.mapper.MetricDefMapper;
import com.kms.web.dto.MetricDefUpdateRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the metric-threshold configuration service (mocked mapper, no
 * database): whitelist, value-domain, cross-field and "silent failure" guards.
 *
 * <p>【中文】指标阈值配置服务的单元测试（mock mapper，不连库）：
 * 白名单、取值域、跨字段组合与"防静默失效"四类校验，外加列表排序与成功路径。
 * 这些规则决定了管理端能不能把系统配成一个"看不出异常、但再也不报警"的状态，
 * 因此逐条覆盖。
 */
class MetricDefConfigServiceTest {

    private MetricDefMapper mapper;
    private MetricDefConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MetricDefMapper.class);
        service = new MetricDefConfigService(mapper);
        // 【必须】MyBatis-Plus 的 LambdaUpdateWrapper 在拼列名时要查实体的 TableInfo 缓存，
        // 而这份缓存在正常运行时由 MyBatis 启动过程注册；纯单测（不启动 Spring）里没有这一步，
        // 不手动注册就会抛 "can not find lambda cache for this entity"。
        // 注册只需实体类本身，不依赖数据源，因此单测依然保持"不连库"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MetricDef.class);
    }

    private MetricDef def(String code, Double upper, Double lower) {
        MetricDef d = new MetricDef();
        d.setMetricCode(code);
        d.setUpperThreshold(upper);
        d.setLowerThreshold(lower);
        d.setDynamicThreshold(0);
        return d;
    }

    private MetricDefUpdateRequest req(Double upper, Double lower, Double volatility, Integer dynamic) {
        MetricDefUpdateRequest r = new MetricDefUpdateRequest();
        r.setUpperThreshold(upper);
        r.setLowerThreshold(lower);
        r.setVolatilityThreshold(volatility);
        r.setDynamicThreshold(dynamic);
        return r;
    }

    @Test
    void listOrdersByWhitelistNotAlphabetically() {
        // 故意打乱顺序传入，期望按 KmsMetrics.CODES 顺序输出（cpu_usage 在前、cpu_temp 在后）
        when(mapper.selectList(any())).thenReturn(List.of(
                def("cpu_temp", 90.0, 0.0),
                def("net_recv", 51200.0, 0.0),
                def("cpu_usage", 90.0, 0.0)));

        List<MetricDef> result = service.list();

        assertEquals(3, result.size());
        assertEquals("cpu_usage", result.get(0).getMetricCode());
        assertEquals("net_recv", result.get(1).getMetricCode());
        assertEquals("cpu_temp", result.get(2).getMetricCode());
    }

    @Test
    void unknownMetricCodeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update("drop_table", req(90.0, 0.0, 10.0, 0)));
        // 白名单拦截，且不会去查库
        assertEquals("未知指标代码: drop_table", e.getMessage());
    }

    @Test
    void missingRecordReturnsNull() {
        when(mapper.selectById("cpu_usage")).thenReturn(null);
        assertNull(service.update("cpu_usage", req(90.0, 0.0, 10.0, 0)));
    }

    @Test
    void invalidDynamicFlagIsRejected() {
        when(mapper.selectById("cpu_usage")).thenReturn(def("cpu_usage", 90.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> service.update("cpu_usage", req(90.0, 0.0, 10.0, 2)));
    }

    @Test
    void negativeVolatilityIsRejected() {
        when(mapper.selectById("cpu_usage")).thenReturn(def("cpu_usage", 90.0, 0.0));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update("cpu_usage", req(90.0, 0.0, -1.0, 0)));
        assertEquals("波动率阈值不能为负数", e.getMessage());
    }

    @Test
    void upperMustBeGreaterThanLower() {
        when(mapper.selectById("cpu_usage")).thenReturn(def("cpu_usage", 90.0, 0.0));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update("cpu_usage", req(10.0, 20.0, 10.0, 0)));
        assertEquals("上限阈值必须大于下限阈值", e.getMessage());
    }

    @Test
    void bothThresholdsEmptyIsRejected() {
        // 防静默失效：两边都清空后该指标再也不会产生阈值预警，页面上却看不出异常
        when(mapper.selectById("cpu_usage")).thenReturn(def("cpu_usage", 90.0, 0.0));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update("cpu_usage", req(null, null, 10.0, 0)));
        assertEquals("上限与下限不能同时为空，否则该指标将不再产生阈值预警", e.getMessage());
    }

    @Test
    void manualThresholdsAreIgnoredForDynamicMetric() {
        // 动态指标：上下限归分析器所有 —— 请求里的手工值被忽略，库里现值保持不变（不报错），
        // 否则动态指标连波动率阈值都改不了，可用性更差
        MetricDef existing = def("cpu_usage", 90.0, 0.0);
        existing.setDynamicThreshold(1);
        MetricDef after = def("cpu_usage", 90.0, 0.0);
        after.setDynamicThreshold(1);
        after.setVolatilityThreshold(12.0);
        when(mapper.selectById("cpu_usage")).thenReturn(existing, after);

        MetricDef result = service.update("cpu_usage", req(80.0, 5.0, 12.0, 1));

        assertNotNull(result);
        // 上下限仍是库中现值（未被请求里的 80/5 覆盖）
        assertEquals(90.0, result.getUpperThreshold());
        assertEquals(0.0, result.getLowerThreshold());
        // 波动率阈值这种"与动态重算无关"的字段照常生效
        assertEquals(12.0, result.getVolatilityThreshold());
    }

    @Test
    void lowerNotBelowUpperIsRejected() {
        // 最终落库值出现"下限 ≥ 上限"：必须拦住（PUT 语义是整体替换，两侧同时提交即可复现）
        when(mapper.selectById("mem_usage")).thenReturn(def("mem_usage", 90.0, 0.0));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update("mem_usage", req(90.0, 95.0, 10.0, 0)));
        assertEquals("上限阈值必须大于下限阈值", e.getMessage());
    }

    @Test
    void singleSidedThresholdIsAllowed() {
        // disk_free 的真实形态：只设下限，上限为 NULL —— 必须放行
        MetricDef existing = def("disk_free", null, 10.0);
        MetricDef after = def("disk_free", null, 15.0);
        when(mapper.selectById("disk_free")).thenReturn(existing, after);

        MetricDef result = service.update("disk_free", req(null, 15.0, 10.0, 0));

        assertNotNull(result);
        assertNull(result.getUpperThreshold());
        assertEquals(15.0, result.getLowerThreshold());
    }

    @Test
    void dynamicFlagDefaultsToOffWhenOmitted() {
        // 请求里没带开关（null）→ 落库为 0，不留 NULL 造成"第三种状态"
        MetricDef existing = def("cpu_usage", 90.0, 0.0);
        MetricDef after = def("cpu_usage", 85.0, 5.0);
        after.setDynamicThreshold(0);
        when(mapper.selectById("cpu_usage")).thenReturn(existing, after);

        MetricDef result = service.update("cpu_usage", req(85.0, 5.0, 12.0, null));

        assertNotNull(result);
        assertEquals(0, result.getDynamicThreshold());
    }
}
