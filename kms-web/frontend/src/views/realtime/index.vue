<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { getLatest, getRealtime } from '@/api/metric'
import { formatValue, METRIC_CODES, METRIC_META } from '@/utils/metrics'

// Real-time monitoring (spec 4.4, item 2): line chart of the last 30 minutes
// from TDengine, refreshed every 5 s, plus latest-value tiles from DM8.
//
// 实时监控页（规格 4.4 第 2 项）：折线图展示 TDengine 中最近 30 分钟的时序数据，
// 每 5 秒轮询刷新；底部磁贴展示从达梦（DM8）读取到的各指标最新值。
const selected = ref(['cpu_usage', 'mem_usage', 'net_send', 'net_recv'])
// 默认只勾选 4 条曲线：6 个指标全画会很乱——disk_free 是 GB 量级（100+），
// cpu_usage 是百分比（0-100），同一条 Y 轴下磁盘会把其他曲线压成一条平线
const latest = ref({})
// 最新值映射，形如 { cpu_usage: 12.3, mem_usage: 48.6, ... }
const seriesData = ref([])
// 最近 30 分钟的数据点数组，每项形如 { ts, cpu_usage, mem_usage, ... }
const chartEl = ref(null)
// 图表容器 DOM 引用（模板中的 ref="chartEl"）
let chart
// ECharts 实例；组件卸载时必须 dispose
let timer
// 5 秒轮询定时器句柄，卸载时清理

function buildOption() {
  const codes = selected.value
  return {
    // fixed categorical palette; color follows the metric, not its position
    // 固定调色板：颜色跟随"指标"而非曲线位置，勾选变化时同一指标的颜色保持不变
    color: codes.map((c) => METRIC_META[c].color),
    tooltip: {
      trigger: 'axis',
      // 空值统一格式化为 "—"，避免 cpu_temp 这类恒为 null 的指标显示出 NaN
      valueFormatter: (v) => (v === null || v === undefined ? '—' : Number(v).toFixed(2))
    },
    legend:
      // 仅 2 条及以上曲线时显示图例；单条曲线时图例与曲线名重复，直接隐藏
      codes.length >= 2
        ? { data: codes.map((c) => METRIC_META[c].label), top: 4, icon: 'roundRect' }
        : undefined,
    // grid.top 与图例联动：有图例时留 44px，单曲线时收缩到 20px 让绘图区上移
    grid: { left: 56, right: 24, top: codes.length >= 2 ? 44 : 20, bottom: 32 },
    xAxis: {
      // 用时间字符串作"类目轴"而不是 type:'time'：采集间隔固定 60 秒，
      // 类目轴让数据点均匀分布；时间轴在缺点时会按真实时间留白，导致曲线断档
      type: 'category',
      data: seriesData.value.map((p) => p.ts),
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#c3c2b7' } },
      // hideOverlap 让 30 个时间标签自动抽稀，防止文字互相重叠糊成一片
      axisLabel: { color: '#898781', hideOverlap: true }
    },
    yAxis: {
      // 实时页同时绘制多个指标、单位各不相同（% / GB / kbps），
      // 因此 Y 轴不标注单位，具体单位见底部各指标磁贴
      type: 'value',
      splitLine: { lineStyle: { color: '#e1e0d9' } },
      axisLabel: { color: '#898781' }
    },
    series: codes.map((c) => ({
      name: METRIC_META[c].label,
      type: 'line',
      smooth: true,
      // 不显示数据点符号：30 个圆点会让曲线显得很脏
      showSymbol: false,
      // connectNulls 让 cpu_temp 恒为 null 时的曲线保持连续而不出现断口
      connectNulls: true,
      lineStyle: { width: 2 },
      // 鼠标悬停时聚焦当前曲线、其余曲线变淡，方便多线对比
      emphasis: { focus: 'series' },
      data: seriesData.value.map((p) => p[c])
    }))
  }
}

function render() {
  // 第二个参数 true 表示 notMerge：整体替换 option，取消勾选的曲线不会残留
  if (chart) chart.setOption(buildOption(), true)
}

async function refresh() {
  try {
    // Promise.all 并发发起两个请求：达梦取"当前值"、TDengine 取"30 分钟曲线"，
    // 总耗时是两者中的较大值，而不是两者之和
    const [latestRows, points] = await Promise.all([getLatest(), getRealtime(30)])
    // 把 [{ metricCode, currentValue }] 转成 { cpu_usage: 12.3, ... }，便于模板按指标代码取值
    latest.value = Object.fromEntries(latestRows.map((r) => [r.metricCode, r.currentValue]))
    seriesData.value = points
    render()
  } catch (e) {
    // interceptor shows the error; keep the last chart content
    // 响应拦截器已弹出错误提示，这里刻意吞掉异常并保留上次的图表内容：
    // 数据库短暂抖动时图表不会变空白，5 秒后自动重试，体验上是"停顿后自动恢复"而非"页面崩了"
  }
}

function resize() {
  // 窗口尺寸变化时重算图表尺寸（实例尚未初始化时直接跳过）
  chart && chart.resize()
}

onMounted(() => {
  // 初始化 ECharts 实例，绑定到模板中 ref="chartEl" 的容器
  chart = echarts.init(chartEl.value)
  refresh()
  // 每 5 秒轮询一次（与 60 秒的采集周期解耦，保证切回本页能较快看到新数据）
  timer = setInterval(refresh, 5000)
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  // 内存泄漏防护三件套：清定时器、移除 resize 监听、销毁 ECharts 实例
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
        <!-- 【中文】勾选状态直接绑定 selected；图表在下次 setOption 时按新的勾选重建 series -->
        <el-checkbox-group v-model="selected">
          <el-checkbox v-for="code in METRIC_CODES" :key="code" :value="code">
            {{ METRIC_META[code].label }}
          </el-checkbox>
        </el-checkbox-group>
      </div>
      <!-- 【中文】图表容器：高度由 .chart 的 380px 决定，ECharts 需要确定高度的容器才能正确绘制 -->
      <div ref="chartEl" class="chart" />
      <div class="chart-note">最近 30 分钟 · 每 5 秒自动刷新 · 数据来自 TDengine</div>
    </div>

    <el-row :gutter="12">
      <!-- 【中文】响应式栅格：手机每行 2 个（xs=12）、平板每行 3 个（sm=8）、桌面每行 6 个（md=4） -->
      <el-col v-for="code in METRIC_CODES" :key="code" :xs="12" :sm="8" :md="4">
        <div class="stat-tile">
          <!-- 【中文】formatValue 按指标代码套用各自的单位与精度（% / GB / kbps / ℃） -->
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
  /* 【中文】窄屏时勾选项自动换行，避免横向挤压溢出 */
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.filter-label {
  font-size: 13px;
  color: #52514e;
}
.chart {
  /* 【中文】必须给定确定高度：ECharts 需要容器有高度才能算出画布尺寸，0 高度会画出空白图 */
  height: 500px;
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
  /* 【中文】等宽数字（tabular-nums）：数值变化时字符宽度稳定，磁贴不会左右抖动 */
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12px;
  color: #898781;
  margin-top: 4px;
}
</style>
