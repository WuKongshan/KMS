import request from './request'

// Alert management: paged list with filters, recent N, mark handled.
export const getAlertPage = (params) => request.get('/api/alerts', { params })
export const getRecentAlerts = (limit = 10) => request.get('/api/alerts/recent', { params: { limit } })
export const updateAlertStatus = (id, status) => request.put(`/api/alerts/${id}/status`, { status })
