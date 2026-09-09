import request from './request'

// Ops management: collector status + control via the web backend proxy.
export const getCollectorStatus = () => request.get('/api/ops/collector/status')
export const updatePeriod = (period) => request.post('/api/ops/collector/period', null, { params: { period } })
export const enableCollector = () => request.post('/api/ops/collector/enable')
export const disableCollector = () => request.post('/api/ops/collector/disable')
