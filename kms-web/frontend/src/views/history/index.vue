<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { getHistory } from '@/api/metric'
import { formatValue, METRIC_CODES, METRIC_META } from '@/utils/metrics'

// History query (spec 4.4, item 3): pick a metric and a time range, get the
// raw TDengine series as a table and a line chart.
const form = reactive({
  metricCode: 'cpu_usage',
  range: [dayjs().subtract(1, 'hour').format('YYYY-MM-DDTHH:mm:ss'), dayjs().format('YYYY-MM-DDTHH:mm:ss')]
})
const rows = ref([])
const loading = ref(false)
const chartEl = ref(null)
let chart

function buildOption() {
  const code = form.metricCode
  return {
    color: [METRIC_META[code].color],
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => (v === null || v === undefined ? '—' : Number(v).toFixed(2))
    },
    grid: { left: 56, right: 24, top: 20, bottom: 32 },
    xAxis: {
      type: 'category',
      data: rows.value.map((r) => r.ts),
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#c3c2b7' } },
      axisLabel: { color: '#898781', hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      name: METRIC_META[code].unit,
      splitLine: { lineStyle: { color: '#e1e0d9' } },
      axisLabel: { color: '#898781' }
    },
    series: [
      {
        name: METRIC_META[code].label,
        type: 'line',
        showSymbol: false,
        connectNulls: true,
        lineStyle: { width: 2 },
        data: rows.value.map((r) => r.value)
      }
    ]
  }
}

async function query() {
  loading.value = true
  try {
    rows.value = await getHistory({
      metricCode: form.metricCode,
      begin: form.range[0],
      end: form.range[1]
    })
    chart.setOption(buildOption(), true)
  } catch (e) {
    // interceptor shows the error
  } finally {
    loading.value = false
  }
}

function resize() {
  chart && chart.resize()
}

onMounted(() => {
  chart = echarts.init(chartEl.value)
  query()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart && chart.dispose()
})
</script>

<template>
  <div>
    <div class="page-card">
      <div class="filter-row">
        <span class="filter-label">指标</span>
        <el-select v-model="form.metricCode" style="width: 180px">
          <el-option
            v-for="code in METRIC_CODES"
            :key="code"
            :label="METRIC_META[code].label"
            :value="code"
          />
        </el-select>
        <span class="filter-label">时间范围</span>
        <el-date-picker
          v-model="form.range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :default-time="[new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]"
        />
        <el-button type="primary" :loading="loading" @click="query">
          <el-icon><Search /></el-icon>&nbsp;查询
        </el-button>
      </div>
    </div>

    <div class="page-card">
      <div ref="chartEl" class="chart" />
      <el-empty
        v-if="!loading && !rows.length"
        description="该时间范围内没有数据"
        :image-size="80"
      />
    </div>

    <div class="page-card">
      <el-table :data="rows" size="small" height="360" stripe>
        <el-table-column prop="ts" label="采集时间" min-width="160" />
        <el-table-column label="指标值" min-width="140" align="right">
          <template #default="{ row }">
            {{ formatValue(form.metricCode, row.value) }}
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.filter-label {
  font-size: 13px;
  color: #52514e;
}
.chart {
  height: 340px;
}
</style>
