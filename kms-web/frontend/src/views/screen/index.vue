<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { getLatest, getRealtime } from '@/api/metric'
import { getRecentAlerts } from '@/api/alert'
import { formatValue, METRIC_CODES, METRIC_META } from '@/utils/metrics'
import ws from '@/utils/ws'
import AlertPopup from '@/components/AlertPopup.vue'

// 数据大屏 (spec 4.4, item 5): dark full-screen dashboard — top marquee of the
// newest alerts, pie chart of the current metric shares, real-time line chart
// and bottom live tiles. Alert popups + sound come from the analyser WS.
const now = ref(dayjs())
const latest = ref({})
const seriesData = ref([])
const alerts = ref([])
const wsConnected = ref(false)

const pieEl = ref(null)
const lineEl = ref(null)
let pieChart
let lineChart
let timer
let clockTimer
let offStatus

const DARK_SERIES = Object.fromEntries(METRIC_CODES.map((c) => [c, METRIC_META[c].darkColor]))

// ---------- charts ----------

function buildPieOption() {
  // Current values with non-null data; share of the positive sum, fixed
  // metric order and per-slice labels so identity never rests on color alone.
  const data = METRIC_CODES
    .filter((c) => latest.value[c] != null)
    .map((c) => ({
      name: METRIC_META[c].label,
      value: Number(latest.value[c].toFixed(2)),
      code: c
    }))
  return {
    color: data.map((d) => DARK_SERIES[d.code]),
    tooltip: {
      trigger: 'item',
      formatter: (p) => `${p.name}: ${p.value} ${METRIC_META[p.data.code]?.unit || ''} (${p.percent}%)`
    },
    legend: {
      bottom: 0,
      textStyle: { color: '#c3c2b7', fontSize: 11 },
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8
    },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '44%'],
        itemStyle: { borderColor: '#1a1a19', borderWidth: 2 },
        label: {
          color: '#c3c2b7',
          fontSize: 11,
          formatter: (p) => `${p.name}\n${p.percent}%`
        },
        labelLine: { lineStyle: { color: '#383835' } },
        data
      }
    ]
  }
}

function buildLineOption() {
  const codes = ['cpu_usage', 'mem_usage', 'net_send', 'net_recv']
  return {
    color: codes.map((c) => DARK_SERIES[c]),
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1a1a19',
      borderColor: '#383835',
      textStyle: { color: '#c3c2b7' },
      valueFormatter: (v) => (v === null || v === undefined ? '—' : Number(v).toFixed(2))
    },
    legend: {
      data: codes.map((c) => METRIC_META[c].label),
      top: 4,
      icon: 'roundRect',
      textStyle: { color: '#c3c2b7', fontSize: 11 }
    },
    grid: { left: 56, right: 24, top: 44, bottom: 32 },
    xAxis: {
      type: 'category',
      data: seriesData.value.map((p) => p.ts),
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#383835' } },
      axisLabel: { color: '#898781', hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#2c2c2a' } },
      axisLabel: { color: '#898781' }
    },
    series: codes.map((c) => ({
      name: METRIC_META[c].label,
      type: 'line',
      smooth: true,
      showSymbol: false,
      connectNulls: true,
      lineStyle: { width: 2 },
      data: seriesData.value.map((p) => p[c])
    }))
  }
}

// ---------- data ----------

async function refresh() {
  try {
    const [latestRows, points, recent] = await Promise.all([
      getLatest(),
      getRealtime(30),
      getRecentAlerts(8)
    ])
    latest.value = Object.fromEntries(latestRows.map((r) => [r.metricCode, r.currentValue]))
    seriesData.value = points
    alerts.value = recent
    if (pieChart) pieChart.setOption(buildPieOption(), true)
    if (lineChart) lineChart.setOption(buildLineOption(), true)
  } catch (e) {
    // interceptor shows the error; keep the last content
  }
}

function resize() {
  pieChart && pieChart.resize()
  lineChart && lineChart.resize()
}

onMounted(() => {
  pieChart = echarts.init(pieEl.value)
  lineChart = echarts.init(lineEl.value)
  refresh()
  timer = setInterval(refresh, 5000)
  clockTimer = setInterval(() => (now.value = dayjs()), 1000)
  window.addEventListener('resize', resize)
  ws.connect()
  offStatus = ws.onStatus((ok) => (wsConnected.value = ok))
})
onBeforeUnmount(() => {
  clearInterval(timer)
  clearInterval(clockTimer)
  window.removeEventListener('resize', resize)
  pieChart && pieChart.dispose()
  lineChart && lineChart.dispose()
  if (offStatus) offStatus()
})

const marqueeText = computed(() => {
  if (!alerts.value.length) return '暂无预警记录 — 系统运行正常'
  return alerts.value
    .map((a) => `【${dayjs(a.createTime).format('HH:mm:ss')} ${a.alertLevel}级】${a.message}`)
    .join('　')
})

const LEVEL_COLORS = { 1: '#fab219', 2: '#ec835a', 3: '#d03b3b' }
const LEVEL_TEXT = { 1: '1级·阈值', 2: '2级·波动', 3: '3级·严重' }
</script>

<template>
  <div class="screen">
    <!-- header -->
    <div class="screen-header">
      <div class="screen-title">关键指标监控系统 · 数据大屏</div>
      <div class="header-right">
        <span class="status-dot" :class="wsConnected ? 'ok' : 'down'" />
        <span class="header-hint">{{ wsConnected ? '预警推送在线' : '预警推送离线' }}</span>
        <span class="clock">{{ now.format('YYYY-MM-DD HH:mm:ss') }}</span>
        <el-button size="small" plain @click="window.close()">退出大屏</el-button>
      </div>
    </div>

    <!-- scrolling alert marquee -->
    <div class="marquee">
      <div class="marquee-track">
        <span class="marquee-item">{{ marqueeText }}</span>
        <span class="marquee-item">{{ marqueeText }}</span>
      </div>
    </div>

    <!-- main grid -->
    <div class="screen-main">
      <div class="panel">
        <div class="panel-title">各指标当前值占比</div>
        <div ref="pieEl" class="panel-chart" />
      </div>

      <div class="panel">
        <div class="panel-title">CPU / 内存 / 带宽 · 最近 30 分钟</div>
        <div ref="lineEl" class="panel-chart" />
      </div>

      <div class="panel">
        <div class="panel-title">最新预警</div>
        <div class="alert-list">
          <div v-for="alert in alerts" :key="alert.id" class="alert-item">
            <span class="alert-dot" :style="{ background: LEVEL_COLORS[alert.alertLevel] || '#898781' }" />
            <div class="alert-body">
              <div class="alert-head">
                <span class="alert-level">{{ LEVEL_TEXT[alert.alertLevel] || `${alert.alertLevel}级` }}</span>
                <span class="alert-time">{{ dayjs(alert.createTime).format('HH:mm:ss') }}</span>
              </div>
              <div class="alert-msg">{{ alert.message }}</div>
            </div>
          </div>
          <el-empty v-if="!alerts.length" description="暂无预警" :image-size="60" />
        </div>
      </div>
    </div>

    <!-- bottom live tiles -->
    <div class="screen-footer">
      <div v-for="code in METRIC_CODES" :key="code" class="tile">
        <div class="tile-label">{{ METRIC_META[code].label }}</div>
        <div class="tile-value" :style="{ color: DARK_SERIES[code] }">
          {{ formatValue(code, latest[code]) }}
        </div>
      </div>
    </div>

    <AlertPopup />
  </div>
</template>

<style scoped>
/* Dark dashboard: validated dark surface (#1a1a19) and page plane (#0d0d0d),
   secondary ink #c3c2b7, muted #898781, gridlines #2c2c2a. */
.screen {
  min-height: 100vh;
  background: linear-gradient(180deg, #0d1120 0%, #0d0d0d 40%);
  color: #fff;
  display: flex;
  flex-direction: column;
  padding: 12px 16px 16px;
  gap: 12px;
}

.screen-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.screen-title {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 2px;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-hint {
  font-size: 12px;
  color: #c3c2b7;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.status-dot.ok {
  background: #0ca30c;
  box-shadow: 0 0 6px #0ca30c;
}
.status-dot.down {
  background: #d03b3b;
  box-shadow: 0 0 6px #d03b3b;
}
.clock {
  font-size: 14px;
  color: #c3c2b7;
  font-variant-numeric: tabular-nums;
}

/* marquee */
.marquee {
  overflow: hidden;
  white-space: nowrap;
  background: #1a1a19;
  border: 1px solid #2c2c2a;
  border-radius: 4px;
  padding: 8px 0;
}
.marquee-track {
  display: inline-block;
  animation: marquee 30s linear infinite;
}
.marquee-item {
  color: #fab219;
  font-size: 13px;
  padding-right: 80px;
}
@keyframes marquee {
  from {
    transform: translateX(0);
  }
  to {
    transform: translateX(-50%);
  }
}

/* main grid */
.screen-main {
  display: grid;
  grid-template-columns: 1fr 1.6fr 1fr;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.panel {
  background: #1a1a19;
  border: 1px solid #2c2c2a;
  border-radius: 6px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  min-height: 380px;
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #c3c2b7;
  margin-bottom: 8px;
}
.panel-chart {
  flex: 1;
  min-height: 320px;
}

/* right alert list */
.alert-list {
  overflow-y: auto;
  flex: 1;
}
.alert-item {
  display: flex;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #2c2c2a;
}
.alert-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 5px;
  flex-shrink: 0;
}
.alert-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.alert-level {
  color: #c3c2b7;
}
.alert-time {
  color: #898781;
  font-variant-numeric: tabular-nums;
}
.alert-msg {
  font-size: 12px;
  color: #fff;
  margin-top: 2px;
  line-height: 1.5;
}

/* bottom tiles */
.screen-footer {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}
.tile {
  background: #1a1a19;
  border: 1px solid #2c2c2a;
  border-radius: 6px;
  padding: 10px 12px;
  text-align: center;
}
.tile-label {
  font-size: 12px;
  color: #898781;
}
.tile-value {
  font-size: 18px;
  font-weight: 700;
  margin-top: 4px;
  font-variant-numeric: tabular-nums;
}

/* responsive: stack on narrow screens (mobile adaptation, spec 4.4 bonus) */
@media (max-width: 1100px) {
  .screen-main {
    grid-template-columns: 1fr;
  }
  .screen-footer {
    grid-template-columns: repeat(3, 1fr);
  }
  .panel {
    min-height: 300px;
  }
}
</style>
