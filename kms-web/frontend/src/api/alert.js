import request from './request'

// Alert management: paged list with filters, recent N, mark handled.
// 预警接口（每个函数一行）：分页筛选、取最近 N 条（大屏跑马灯用）、标记处理状态。
// 分页查询：params 形如 { pageNum, pageSize, level, status, begin, end }，返回 { rows, total }。
export const getAlertPage = (params) => request.get('/api/alerts', { params })
// 取最近 N 条预警（默认 10 条），供大屏跑马灯与"最新预警"列表使用。
export const getRecentAlerts = (limit = 10) => request.get('/api/alerts/recent', { params: { limit } })
// 更新预警处理状态：status 为 1 表示已处理、0 表示未处理。
export const updateAlertStatus = (id, status) => request.put(`/api/alerts/${id}/status`, { status })
