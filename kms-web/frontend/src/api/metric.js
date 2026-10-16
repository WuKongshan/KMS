import request from './request'

// Metric queries: latest values (DM8), realtime window and history (TDengine).
// 指标查询接口（每个函数一行）：最新值查达梦（DM8 的 t_metric_latest，采集器每轮覆盖写入），
// 实时窗口与历史区间查 TDengine 时序库；指标代码先经后端 KmsMetrics 白名单校验。
// 取全部指标的最新值（达梦当前值快照），无参数；首屏卡片与 5 秒刷新都靠它。
export const getLatest = () => request.get('/api/metrics/latest')
// 取最近 N 分钟的时序数据（TDengine），默认 30 分钟，供实时监控折线图使用。
export const getRealtime = (minutes = 30) => request.get('/api/metrics/realtime', { params: { minutes } })
// 按条件查历史数据（TDengine）：params 形如 { metricCode, begin, end }，时间用 ISO 字符串。
export const getHistory = (params) => request.get('/api/metrics/history', { params })

// 指标阈值配置（仅 ADMIN，其他角色请求会返回 403）
// 读取全部指标定义：含上下限、波动率阈值、动态阈值开关与最后修改时间。
export const getMetricDefs = () => request.get('/api/metrics/defs')
// 修改单个指标的阈值配置：data 形如 { upperThreshold, lowerThreshold, volatilityThreshold, dynamicThreshold }
// 注意语义是"整体替换"——未传的阈字段会被清空，因此编辑弹窗始终提交全部四个字段。
export const updateMetricDef = (metricCode, data) => request.put(`/api/metrics/defs/${metricCode}`, data)
