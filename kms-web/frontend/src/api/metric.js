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
