import request from './request'

// Ops management: collector status + control via the web backend proxy.
// 运维接口：采集器的状态查询与周期/启停控制。前端只调 web 后端（8080），
// 由后端代理转发到采集器（8081），所以浏览器无需直连 8081，也就没有跨域问题。
// 查询采集器状态：{ available, enabled, periodSeconds, lastCollectTime, collectCount, lastError, lastMetrics }。
export const getCollectorStatus = () => request.get('/api/ops/collector/status')
// 修改采集周期（单位秒，约束 ≥ 60）：period 走 query 参数，请求体传 null；返回更新后的状态。
export const updatePeriod = (period) => request.post('/api/ops/collector/period', null, { params: { period } })
// 启用采集：采集器下一轮循环即恢复采集，无需重启进程。
export const enableCollector = () => request.post('/api/ops/collector/enable')
// 停用采集：停止写入 TDengine 与达梦最新值表，但采集器进程仍在运行，可随时再启用。
export const disableCollector = () => request.post('/api/ops/collector/disable')
