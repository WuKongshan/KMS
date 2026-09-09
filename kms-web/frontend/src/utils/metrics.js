/**
 * Metric metadata shared by all chart pages. Series colors are the validated
 * categorical palette (fixed hue per metric code — color follows the entity,
 * never its position in the selection), dark steps for the 大屏.
 */
export const METRIC_META = {
  cpu_usage: { label: 'CPU 使用率', unit: '%', color: '#2a78d6', darkColor: '#3987e5' },
  mem_usage: { label: '内存使用率', unit: '%', color: '#eb6834', darkColor: '#d95926' },
  net_send: { label: '网络发送速率', unit: 'KB/s', color: '#1baf7a', darkColor: '#199e70' },
  net_recv: { label: '网络接收速率', unit: 'KB/s', color: '#eda100', darkColor: '#c98500' },
  disk_free: { label: '磁盘剩余空间', unit: 'GB', color: '#e87ba4', darkColor: '#d55181' },
  cpu_temp: { label: 'CPU 温度', unit: '°C', color: '#008300', darkColor: '#008300' }
}

export const METRIC_CODES = Object.keys(METRIC_META)

export function metricLabel(code) {
  return METRIC_META[code]?.label || code
}

export function metricUnit(code) {
  return METRIC_META[code]?.unit || ''
}

export function formatValue(code, value) {
  if (value === null || value === undefined) return '—'
  return `${Number(value).toFixed(2)} ${metricUnit(code)}`
}
