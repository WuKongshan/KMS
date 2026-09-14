/**
 * Metric metadata shared by all chart pages. Series colors are the validated
 * categorical palette (fixed hue per metric code — color follows the entity,
 * never its position in the selection), dark steps for the 大屏.
 *
 * 【中文】所有图表页面共用的指标元数据表：label 中文名、unit 单位、
 * color 亮色主题用色（后台页面）、darkColor 深色主题用色（数据大屏，深色背景下原亮色会刺眼，
 * 需要提亮或压暗）。颜色跟着指标代码走，而不是跟着系列在图表里的位置走——
 * 若依赖 ECharts 默认调色板（按 series 下标取色），取消勾选某个指标后其余曲线的颜色
 * 会整体前移，用户就会把曲线认错。
 */
export const METRIC_META = {
  cpu_usage: { label: 'CPU 使用率', unit: '%', color: '#2a78d6', darkColor: '#3987e5' },
  mem_usage: { label: '内存使用率', unit: '%', color: '#eb6834', darkColor: '#d95926' },
  net_send: { label: '网络发送速率', unit: 'KB/s', color: '#1baf7a', darkColor: '#199e70' },
  net_recv: { label: '网络接收速率', unit: 'KB/s', color: '#eda100', darkColor: '#c98500' },
  disk_free: { label: '磁盘剩余空间', unit: 'GB', color: '#e87ba4', darkColor: '#d55181' },
  cpu_temp: { label: 'CPU 温度', unit: '°C', color: '#008300', darkColor: '#008300' }
}

// 指标代码列表：顺序与元数据定义顺序一致，图表与下拉框都按这个顺序渲染。
export const METRIC_CODES = Object.keys(METRIC_META)

// 取指标中文名；未登记的代码原样返回，避免界面上出现 undefined。
export function metricLabel(code) {
  return METRIC_META[code]?.label || code
}

// 取指标单位；未登记时返回空串，保证格式化时不会拼出 "undefined"。
export function metricUnit(code) {
  return METRIC_META[code]?.unit || ''
}

// 格式化指标展示值：null/undefined 返回 "—" 而不是 "0.00"，
// 用于区分"没采到数据"（温度传感器不支持、采集器刚停用）与"值真的是 0"；
// 正常值固定保留两位小数并拼上单位。
export function formatValue(code, value) {
  if (value === null || value === undefined) return '—'
  return `${Number(value).toFixed(2)} ${metricUnit(code)}`
}
