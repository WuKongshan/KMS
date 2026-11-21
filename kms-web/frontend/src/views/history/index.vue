<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { getHistory } from '@/api/metric'
import { formatValue, METRIC_CODES, METRIC_META } from '@/utils/metrics'

// History query (spec 4.4, item 3): pick a metric and a time range, get the
// raw TDengine series as a table and a line chart.
//
// 历史查询页（规格 4.4 第 3 项）：选择指标 + 时间范围，从 TDengine 取原始时序数据，
// 同时以折线图和明细表格展示。
// 与实时监控页的关键差异：这里是"按需查询"（点查询按钮才发请求），不做定时轮询。
const form = reactive({
  metricCode: 'cpu_usage',
  // 默认时间范围"最近 1 小时"，与后端默认值保持一致；
  // 格式 YYYY-MM-DDTHH:mm:ss 对应后端的 @DateTimeFormat(iso = ISO.DATE_TIME)，保证能正确反序列化
  range: [dayjs().subtract(1, 'hour').format('YYYY-MM-DDTHH:mm:ss'), dayjs().format('YYYY-MM-DDTHH:mm:ss')]
})
const rows = ref([])
// 查询结果明细数组，每项形如 { ts, value }
const loading = ref(false)
// 查询中标志：驱动查询按钮的 loading，并参与空状态判断
const chartEl = ref(null)
let chart
// ECharts 实例；卸载时必须 dispose

function buildOption() {
  const code = form.metricCode
  return {
    // 单条曲线，颜色仍按指标本身取（切换指标时曲线颜色随之变化）
    color: [METRIC_META[code].color],
    tooltip: {
      trigger: 'axis',
      // 空值格式化为 "—"，避免出现 NaN
      valueFormatter: (v) => (v === null || v === undefined ? '—' : Number(v).toFixed(2))
    },
    // 本页无图例，绘图区顶部固定留 20px 即可
    grid: { left: 56, right: 24, top: 20, bottom: 32 },
    xAxis: {
      // 同样使用时间字符串作类目轴：采集间隔固定 60 秒，点均匀排布；
      // 时间轴在有缺失点时会出现空档，不利于观察历史趋势
      type: 'category',
      data: rows.value.map((r) => r.ts),
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#c3c2b7' } },
      // 标签过多时自动抽稀，避免重叠
      axisLabel: { color: '#898781', hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      // Y 轴显示单位：历史页一次只画一个指标，不存在单位混淆
      // （实时页多指标单位不同，所以那里不显示单位）
      name: METRIC_META[code].unit,
      splitLine: { lineStyle: { color: '#e1e0d9' } },
      axisLabel: { color: '#898781' }
    },
    series: [
      {
        name: METRIC_META[code].label,
        type: 'line',
        // 隐藏数据点符号，让曲线更干净
        showSymbol: false,
        // 连接空值点，指标偶尔缺采时曲线不会断开
        itemStyle: { color: '#ff0000' },
        connectNulls: true,
        lineStyle: { width: 2, color: METRIC_META[code].color },
        data: rows.value.map((r) => r.value)
      }
    ]
  }
}

// 按需查询：只有点击"查询"按钮（以及首次进入页面）才发请求，不做定时轮询
async function query() {
  loading.value = true
  try {
    rows.value = await getHistory({
      metricCode: form.metricCode,
      begin: form.range[0],
      end: form.range[1]
    })
    // 拿到数据后整体替换 option（notMerge = true），切换指标时旧曲线不会残留
    chart.setOption(buildOption(), true)
  } catch (e) {
    // interceptor shows the error
  } finally {
    // 无论成功失败都要结束 loading，否则按钮会一直转圈
    loading.value = false
  }
}

function resize() {
  // 窗口尺寸变化时重算图表尺寸
  chart && chart.resize()
}

onMounted(() => {
  // 初始化 ECharts 实例，并立即按默认条件（cpu_usage、最近 1 小时）查询一次
  chart = echarts.init(chartEl.value)
  query()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  // 内存泄漏防护：移除 resize 监听 + 销毁 ECharts 实例
  // （本页没有定时器，所以不需要 clearInterval）
  window.removeEventListener('resize', resize)
  chart && chart.dispose()
})
</script>

<template>
  <div>
    <div class="page-card">
      <div class="filter-row">
        <span class="filter-label">指标</span>
        <!-- 【中文】指标下拉：选项来自 METRIC_CODES，与后端 KmsMetrics 白名单保持一致 -->
        <el-select v-model="form.metricCode" style="width: 180px">
          <el-option
            v-for="code in METRIC_CODES"
            :key="code"
            :label="METRIC_META[code].label"
            :value="code"
          />
        </el-select>
        <span class="filter-label">时间范围</span>
        <!-- 【中文】时间范围选择器：value-format 与后端 @DateTimeFormat(iso = ISO.DATE_TIME) 对齐；
             default-time 让只选到"天"时自动补足 00:00:00 ~ 23:59:59 的边界 -->
        <el-date-picker
          v-model="form.range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :default-time="[new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]"
        />
        <!-- 【中文】按需查询按钮：点击才发请求（本页不轮询），loading 期间按钮转圈并禁用 -->
        <el-button type="primary" :loading="loading" @click="query">
          <el-icon><Search /></el-icon>&nbsp;查询
        </el-button>
      </div>
    </div>

    <div class="page-card">
      <!-- 【中文】图表容器：高度由 .chart 的 340px 决定 -->
      <div ref="chartEl" class="chart" />
      <!-- 【中文】空状态：仅当"查询结束且没有数据"时显示，查询过程中不会闪烁 -->
      <el-empty
        v-if="!loading && !rows.length"
        description="该时间范围内没有数据"
        :image-size="80"
      />
    </div>

    <div class="page-card">
      <!-- 【中文】明细表格：固定 360px 高度，超出部分表体内部滚动、表头保持可见 -->
      <el-table :data="rows" size="small" height="360" stripe>
        <el-table-column prop="ts" label="采集时间" min-width="160" />
        <!-- 【中文】指标值复用 formatValue（按指标代码带单位），与上方图表口径一致 -->
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
  /* 【中文】窄屏时筛选控件自动换行 */
  flex-wrap: wrap;
}
.filter-label {
  font-size: 13px;
  color: #52514e;
}
.chart {
  /* 【中文】给 ECharts 容器一个确定高度，否则画布高度为 0、图表不可见 */
  height: 340px;
}
</style>
