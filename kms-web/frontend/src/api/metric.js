import request from './request'

// Metric queries: latest values (DM8), realtime window and history (TDengine).
export const getLatest = () => request.get('/api/metrics/latest')
export const getRealtime = (minutes = 30) => request.get('/api/metrics/realtime', { params: { minutes } })
export const getHistory = (params) => request.get('/api/metrics/history', { params })
