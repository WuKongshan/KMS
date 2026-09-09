<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { getLatest, getRealtime } from '@/api/metric'
import { formatValue, METRIC_CODES, METRIC_META } from '@/utils/metrics'

// Real-time monitoring (spec 4.4, item 2): line chart of the last 30 minutes
// from TDengine, refreshed every 5 s, plus latest-value tiles from DM8.
const selected = ref(['cpu_usage', 'mem_usage', 'net_send', 'net_recv'])
const latest = ref({})
const seriesData = ref([])
const chartEl = ref(null)
let chart
let timer

function buildOption() {
  const codes = selected.value
  return {
    // fixed categorical palette; color follows the metric, not its position
    color: codes.map((c) => METRIC_META[c].color),
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => (v === null || v === undefined ? '—' : Number(v).toFixed(2))
    },
    legend:
      codes.length >= 2
        ? { data: codes.map((c) => METRIC_META[c].label), top: 4, icon: 'roundRect' }
        : undefined,
    grid: { left: 56, right: 24, top: codes.length >= 2 ? 44 : 20, bottom: 32 },
    xAxis: {
      type: 'category',
      data: seriesData.value.map((p) => p.ts),
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#c3c2b7' } },
      axisLabel: { color: '#898781', hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#e1e0d9' } },
      axisLabel: { color: '#898781' }
    },
    series: codes.map((c) => ({
      name: METRIC_META[c].label,
      type: 'line',
      smooth: true,
      showSymbol: false,
      connectNulls: true,
      lineStyle: { width: 2 },
      emphasis: { focus: 'series' },
      data: seriesData.value.map((p) => p[c])
    }))
  }
}

function render() {
  if (chart) chart.setOption(buildOption(), true)
}

async function refresh() {
  try {
    const [latestRows, points] = await Promise.all([getLatest(), getRealtime(30)])
    latest.value = Object.fromEntries(latestRows.map((r) => [r.metricCode, r.currentValue]))
    seriesData.value = points
    render()
  } catch (e) {
    // interceptor shows the error; keep the last chart content
  }
}

function resize() {
  chart && chart.resize()
}

onMounted(() => {
  chart = echarts.init(chartEl.value)
  refresh()
  timer = setInterval(refresh, 5000)
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  window.removeEventListener('resize', resize)
  chart && chart.dispose()
})
</script>

<template>
  <div>
    <div class="page-card">
      <div class="filter-row">
        <span class="filter-label">显示指标:</span>
        <el-checkbox-group v-model="selected">
          <el-checkbox v-for="code in METRIC_CODES" :key="code" :value="code">
            {{ METRIC_META[code].label }}
          </el-checkbox>
        </el-checkbox-group>
      </div>
      <div ref="chartEl" class="chart" />
      <div class="chart-note">最近 30 分钟 · 每 5 秒自动刷新 · 数据来自 TDengine</div>
    </div>

    <el-row :gutter="12">
      <el-col v-for="code in METRIC_CODES" :key="code" :xs="12" :sm="8" :md="4">
        <div class="stat-tile">
          <div class="stat-value">{{ formatValue(code, latest[code]) }}</div>
          <div class="stat-label">{{ METRIC_META[code].label }}</div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.filter-label {
  font-size: 13px;
  color: #52514e;
}
.chart {
  height: 380px;
}
.chart-note {
  font-size: 12px;
  color: #898781;
  text-align: right;
}
.stat-tile {
  border: 1px solid rgba(11, 11, 11, 0.1);
  border-radius: 6px;
  padding: 12px;
  text-align: center;
  margin-bottom: 12px;
  background: #fcfcfb;
}
.stat-value {
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12px;
  color: #898781;
  margin-top: 4px;
}
</style>
