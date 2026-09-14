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
//
// 数据大屏（规格 4.4 第 5 项）：深色全屏看板，共 5 个区块——
// ① 顶部预警跑马灯 ② 各指标当前值占比饼图 ③ 最近 30 分钟折线图
// ④ 右侧最新预警列表 ⑤ 底部 6 个实时指标磁贴。
// 预警弹窗与声音由 analyser 的 WebSocket 推送驱动（内部挂载 AlertPopup）。
const now = ref(dayjs())
// 大屏时钟时间（dayjs 对象），由 1 秒定时器刷新
const latest = ref({})
// 最新值映射 { cpu_usage: 12.3, ... }，供饼图与底部磁贴使用
const seriesData = ref([])
// 最近 30 分钟的时序点，供折线图使用
const alerts = ref([])
// 最新预警列表（后端返回最近 8 条），供跑马灯与右侧列表使用
const wsConnected = ref(false)
// 预警推送连接状态，驱动顶部状态点的颜色

const pieEl = ref(null)
const lineEl = ref(null)
let pieChart
let lineChart
// 两个 ECharts 实例；卸载时必须分别 dispose
let timer
// 数据刷新定时器（5 秒）
let clockTimer
// 时钟定时器（1 秒）
let offStatus
// ws.onStatus() 返回的退订函数；卸载时调用，避免回调累积

// 一行把亮色主题的指标色映射成深色主题色：{ cpu_usage: '#...', ... }
const DARK_SERIES = Object.fromEntries(METRIC_CODES.map((c) => [c, METRIC_META[c].darkColor]))

// ---------- charts ----------
// 图表构造区：饼（环）图与折线图的 ECharts option 构造函数

function buildPieOption() {
  // Current values with non-null data; share of the positive sum, fixed
  // metric order and per-slice labels so identity never rests on color alone.
  //
  // 饼（环）图构造：只取当前值非 null 的指标，保持固定的指标顺序，
  // 每片扇形自带名称与百分比标签，遵循"不单纯依赖颜色区分"的无障碍原则。
  const data = METRIC_CODES
    // 过滤 null：cpu_temp 读不到温度时为 null，不过滤会出现 0 值扇形、误导占比
    .filter((c) => latest.value[c] != null)
    .map((c) => ({
      name: METRIC_META[c].label,
      value: Number(latest.value[c].toFixed(2)),
      code: c
    }))
  return {
    // 逐片指定颜色（颜色绑定指标代码 d.code）而不是依赖调色板索引：
    // 某个指标为 null 被过滤后，后面的扇形颜色不会整体前移错位
    color: data.map((d) => DARK_SERIES[d.code]),
    tooltip: {
      trigger: 'item',
      formatter: (p) => `${p.name}: ${p.value} ${METRIC_META[p.data.code]?.unit || ''} (${p.percent}%)`
    },
    legend: {
      // 图例放到底部居中，深色系文字，小圆点图标，与环形图搭配
      bottom: 0,
      textStyle: { color: '#c3c2b7', fontSize: 11 },
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8
    },
    series: [
      {
        type: 'pie',
        // 环形图：内半径 42%、外半径 68%，中间留空，视线更容易落在环上
        radius: ['42%', '68%'],
        center: ['50%', '44%'],
        // 描边色与面板背景 #1a1a19 相同，形成扇形之间的视觉分隔（留白感）
        itemStyle: { borderColor: '#1a1a19', borderWidth: 2 },
        // 每片显示"名称 + 百分比"两行标签，不依赖颜色也能辨认指标
        label: {
          color: '#c3c2b7',
          fontSize: 11,
          formatter: (p) => `${p.name}\n${p.percent}%`
        },
        // 标签引导线用 #383835（深色分隔线色系），避免亮色线条在深色底上刺眼
        labelLine: { lineStyle: { color: '#383835' } },
        data
      }
    ]
  }
}

function buildLineOption() {
  // 大屏折线图硬编码 4 条曲线（CPU / 内存 / 发送 / 接收）：大屏无人交互，固定内容更稳定；
  // disk_free 与 cpu_temp 因量纲差异和取值可能为 null 而不入图
  const codes = ['cpu_usage', 'mem_usage', 'net_send', 'net_recv']
  return {
    // 颜色取深色主题专用色（DARK_SERIES），与亮色页面用的 color 区分开
    color: codes.map((c) => DARK_SERIES[c]),
    tooltip: {
      trigger: 'axis',
      // 深色主题适配：tooltip 背景 / 描边 / 文字全部改为深色系配色，避免默认白底突兀
      backgroundColor: '#1a1a19',
      borderColor: '#383835',
      textStyle: { color: '#c3c2b7' },
      // 空值显示为 "—"，避免出现 NaN
      valueFormatter: (v) => (v === null || v === undefined ? '—' : Number(v).toFixed(2))
    },
    legend: {
      data: codes.map((c) => METRIC_META[c].label),
      top: 4,
      icon: 'roundRect',
      // 图例文字同样改成次级文字色 #c3c2b7，保证深色底上的可读性
      textStyle: { color: '#c3c2b7', fontSize: 11 }
    },
    grid: { left: 56, right: 24, top: 44, bottom: 32 },
    xAxis: {
      // 类目轴（时间字符串）：采集间隔固定 60 秒，数据点等距排布，
      // 不会像时间轴那样因缺失点而出现断档
      type: 'category',
      data: seriesData.value.map((p) => p.ts),
      boundaryGap: false,
      // 轴线用 #383835（深色分隔线色系），与面板边框保持同一视觉层级
      axisLine: { lineStyle: { color: '#383835' } },
      axisLabel: { color: '#898781', hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      // 网格线 #2c2c2a 是深色体系里的分隔线色，比亮色页面更暗，避免抢视线
      splitLine: { lineStyle: { color: '#2c2c2a' } },
      axisLabel: { color: '#898781' }
    },
    series: codes.map((c) => ({
      name: METRIC_META[c].label,
      type: 'line',
      smooth: true,
      // 隐藏数据点符号：30 个圆点会让大屏显得杂乱
      showSymbol: false,
      // 连接空值点，个别指标缺采时曲线不会断开
      connectNulls: true,
      lineStyle: { width: 2 },
      data: seriesData.value.map((p) => p[c])
    }))
  }
}

// ---------- data ----------
// 数据获取区

async function refresh() {
  try {
    // 一次并发三个请求：达梦最新值 + TDengine 30 分钟曲线 + 达梦最近 8 条预警，
    // 任一失败都会走 catch，不会阻塞另外两个
    const [latestRows, points, recent] = await Promise.all([
      getLatest(),
      getRealtime(30),
      getRecentAlerts(8)
    ])
    // 最新值转成 { 指标代码: 值 } 的映射，供饼图与底部磁贴按代码取值
    latest.value = Object.fromEntries(latestRows.map((r) => [r.metricCode, r.currentValue]))
    seriesData.value = points
    alerts.value = recent
    // 两个图表都整体替换 option（notMerge = true），保证与最新数据一致、不残留旧系列
    if (pieChart) pieChart.setOption(buildPieOption(), true)
    if (lineChart) lineChart.setOption(buildLineOption(), true)
  } catch (e) {
    // interceptor shows the error; keep the last content
    // 拦截器已提示错误，这里保留上一次的内容：短暂故障时大屏不会变空白
  }
}

function resize() {
  // 窗口尺寸变化时两个图表都要重算尺寸
  pieChart && pieChart.resize()
  lineChart && lineChart.resize()
}

onMounted(() => {
  // 分别初始化饼图与折线图实例
  pieChart = echarts.init(pieEl.value)
  lineChart = echarts.init(lineEl.value)
  refresh()
  // 定时器 ①：每 5 秒刷新数据（最新值 + 曲线 + 预警列表）
  timer = setInterval(refresh, 5000)
  // 定时器 ②：每 1 秒刷新大屏时钟
  clockTimer = setInterval(() => (now.value = dayjs()), 1000)
  window.addEventListener('resize', resize)
  // 定时器 ③（事件驱动）：连接 WebSocket，预警到达时由 AlertPopup 弹出提示
  ws.connect()
  offStatus = ws.onStatus((ok) => (wsConnected.value = ok))
})
onBeforeUnmount(() => {
  // 清理两个定时器，避免离开大屏后仍在后台轮询
  clearInterval(timer)
  clearInterval(clockTimer)
  window.removeEventListener('resize', resize)
  // 销毁两个 ECharts 实例，释放 canvas 与事件监听
  pieChart && pieChart.dispose()
  lineChart && lineChart.dispose()
  // 退订 WebSocket 状态回调，防止回调随路由切换不断累积
  if (offStatus) offStatus()
})

// 跑马灯文本：把最近几条预警拼成一行，条目之间用全角空格 '　' 分隔（比半角更醒目）；
// 每条的格式是【HH:mm:ss N级】预警消息
const marqueeText = computed(() => {
  // 无预警时给一句兜底文案，避免一条空跑马灯让人误以为页面坏了
  if (!alerts.value.length) return '暂无预警记录 — 系统运行正常'
  return alerts.value
    .map((a) => `【${dayjs(a.createTime).format('HH:mm:ss')} ${a.alertLevel}级】${a.message}`)
    .join('　')
})

// 预警级别配色（深色底专用）：1 级黄 #fab219、2 级橙 #ec835a、3 级红 #d03b3b
const LEVEL_COLORS = { 1: '#fab219', 2: '#ec835a', 3: '#d03b3b' }
// 预警级别简称，用于右侧"最新预警"列表的级别文字
const LEVEL_TEXT = { 1: '1级·阈值', 2: '2级·波动', 3: '3级·严重' }
</script>

<template>
  <div class="screen">
    <!-- header -->
    <!-- 【中文】大屏顶栏：系统标题 + 连接状态点 + 时钟 + 退出按钮 -->
    <div class="screen-header">
      <div class="screen-title">关键指标监控系统 · 数据大屏</div>
      <div class="header-right">
        <!-- 【中文】连接状态点：wsConnected 为真显示绿点（带发光阴影），否则红点 -->
        <span class="status-dot" :class="wsConnected ? 'ok' : 'down'" />
        <span class="header-hint">{{ wsConnected ? '预警推送在线' : '预警推送离线' }}</span>
        <!-- 【中文】大屏时钟：由 1 秒定时器刷新 now -->
        <span class="clock">{{ now.format('YYYY-MM-DD HH:mm:ss') }}</span>
        <!-- 【中文】退出大屏：调用 window.close() 关闭当前标签页
             （浏览器只允许脚本关闭由脚本打开的窗口，直接输入地址访问时可能无效） -->
        <el-button size="small" plain @click="window.close()">退出大屏</el-button>
      </div>
    </div>

    <!-- scrolling alert marquee -->
    <!-- 【中文】预警跑马灯：轨道里放了两份内容完全相同的 marqueeText，
         配合 @keyframes marquee 的 translateX(0) → translateX(-50%) 实现无缝滚动 -->
    <div class="marquee">
      <div class="marquee-track">
        <!-- 【中文】第 1 份文本 -->
        <span class="marquee-item">{{ marqueeText }}</span>
        <!-- 【中文】第 2 份文本（与第 1 份完全相同，是首尾无缝衔接的关键，不可删除） -->
        <span class="marquee-item">{{ marqueeText }}</span>
      </div>
    </div>

    <!-- main grid -->
    <!-- 【中文】主网格：CSS Grid 三栏 1fr / 1.6fr / 1fr，中间列更宽，因为折线图需要横向空间 -->
    <div class="screen-main">
      <div class="panel">
        <div class="panel-title">各指标当前值占比</div>
        <!-- 【中文】饼（环）图容器 -->
        <div ref="pieEl" class="panel-chart" />
      </div>

      <div class="panel">
        <div class="panel-title">CPU / 内存 / 带宽 · 最近 30 分钟</div>
        <!-- 【中文】折线图容器（与饼图共用 .panel-chart 的弹性高度） -->
        <div ref="lineEl" class="panel-chart" />
      </div>

      <div class="panel">
        <div class="panel-title">最新预警</div>
        <div class="alert-list">
          <div v-for="alert in alerts" :key="alert.id" class="alert-item">
            <!-- 【中文】级别色点：查 LEVEL_COLORS，未知级别兜底为灰 #898781 -->
            <span class="alert-dot" :style="{ background: LEVEL_COLORS[alert.alertLevel] || '#898781' }" />
            <div class="alert-body">
              <div class="alert-head">
                <!-- 【中文】级别简称，未知级别兜底显示 "N级" -->
                <span class="alert-level">{{ LEVEL_TEXT[alert.alertLevel] || `${alert.alertLevel}级` }}</span>
                <span class="alert-time">{{ dayjs(alert.createTime).format('HH:mm:ss') }}</span>
              </div>
              <div class="alert-msg">{{ alert.message }}</div>
            </div>
          </div>
          <!-- 【中文】没有预警时的空状态 -->
          <el-empty v-if="!alerts.length" description="暂无预警" :image-size="60" />
        </div>
      </div>
    </div>

    <!-- bottom live tiles -->
    <!-- 【中文】底部实时磁贴：6 个指标各一格（CSS Grid repeat(6, 1fr)） -->
    <div class="screen-footer">
      <div v-for="code in METRIC_CODES" :key="code" class="tile">
        <div class="tile-label">{{ METRIC_META[code].label }}</div>
        <!-- 【中文】数值颜色取该指标的深色主题色，与折线图 / 饼图配色保持一致 -->
        <div class="tile-value" :style="{ color: DARK_SERIES[code] }">
          {{ formatValue(code, latest[code]) }}
        </div>
      </div>
    </div>

    <!-- 【中文】大屏内的全局预警弹窗宿主：与后台布局互斥，同一时刻只有一个存活 -->
    <AlertPopup />
  </div>
</template>

<style scoped>
/* Dark dashboard: validated dark surface (#1a1a19) and page plane (#0d0d0d),
   secondary ink #c3c2b7, muted #898781, gridlines #2c2c2a. */
/* 【中文】深色大屏配色体系（经过对比度验证）：面板底色 #1a1a19、页面底色 #0d0d0d、
   次级文字 #c3c2b7、弱化文字 #898781、网格线/分隔线 #2c2c2a */
.screen {
  min-height: 100vh;
  /* 【中文】页面底色从顶部微蓝 #0d1120 在 40% 处渐变到 #0d0d0d，形成"夜空"氛围 */
  background: linear-gradient(180deg, #0d1120 0%, #0d0d0d 40%);
  color: #fff;
  display: flex;
  /* 【中文】纵向排列各区块：顶栏 / 跑马灯 / 主网格 / 底部磁贴（弹窗宿主不可见） */
  flex-direction: column;
  padding: 12px 16px 16px;
  /* 【中文】区块之间统一 12px 间距 */
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
  /* 【中文】标题字距拉大 2px，增强大屏的展示感 */
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
  /* 【中文】在线绿点：叠加同色发光阴影，远距离也能一眼识别状态 */
  box-shadow: 0 0 6px #0ca30c;
}
.status-dot.down {
  background: #d03b3b;
  /* 【中文】离线红点：同样叠加同色发光 */
  box-shadow: 0 0 6px #d03b3b;
}
.clock {
  font-size: 14px;
  color: #c3c2b7;
  /* 【中文】等宽数字：秒数跳动时宽度稳定，不牵动相邻元素 */
  font-variant-numeric: tabular-nums;
}

/* marquee */
/* 【中文】跑马灯容器：裁掉溢出内容并禁止换行，内部轨道才能横向平移 */
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
  /* 【中文】30 秒一轮、线性匀速、无限循环的滚动动画（关键帧定义见下） */
  animation: marquee 30s linear infinite;
}
.marquee-item {
  /* 【中文】预警文字用 1 级预警黄 #fab219，在深色底上足够醒目 */
  color: #fab219;
  font-size: 13px;
  /* 【中文】每份文本尾部留 80px 间距，保证首尾衔接处不粘连 */
  padding-right: 80px;
}
@keyframes marquee {
  /* 【中文】位移 -50% 的原理：轨道里放了两份完全相同的文本，总宽记为 2T，-50% 即 -T；
     动画结束时第 2 份刚好移动到第 1 份的起始位置，因为两份内容完全一致，视觉上无跳变。
     若只放一份，文本走到一半后右半屏会空出来，然后瞬间跳回起点 */
  from {
    transform: translateX(0);
  }
  to {
    transform: translateX(-50%);
  }
}

/* main grid */
/* 【中文】主网格：三栏 1fr / 1.6fr / 1fr，中间列更宽（折线图需要横向空间）；
   flex: 1 让主网格吃掉剩余高度，min-height: 0 防止 grid 子项撑破父容器导致整页滚动 */
.screen-main {
  display: grid;
  grid-template-columns: 1fr 1.6fr 1fr;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
/* 【中文】通用面板：深色底 + 1px 深色边框 + 6px 圆角；内部纵向排列（标题 + 内容） */
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
  /* 【中文】图表撑满面板剩余高度；min-height 保证 ECharts 画布有足够高度 */
  flex: 1;
  min-height: 320px;
}

/* right alert list */
/* 【中文】右侧预警列表：占满面板剩余高度并内部滚动，预警再多也不会撑高整页 */
.alert-list {
  overflow-y: auto;
  flex: 1;
}
.alert-item {
  display: flex;
  gap: 8px;
  padding: 8px 0;
  /* 【中文】每条预警之间用 1px 深色分隔线隔开 */
  border-bottom: 1px solid #2c2c2a;
}
.alert-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  /* 【中文】下移 5px 让色点与首行文字视觉居中对齐 */
  margin-top: 5px;
  /* 【中文】禁止收缩：文字过长时色点保持正圆而不是被压扁 */
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
  /* 【中文】等宽数字，多条预警的时间列对齐 */
  font-variant-numeric: tabular-nums;
}
.alert-msg {
  font-size: 12px;
  color: #fff;
  margin-top: 2px;
  /* 【中文】1.5 倍行距，长消息更易读 */
  line-height: 1.5;
}

/* bottom tiles */
/* 【中文】底部磁贴区：CSS Grid 固定 6 等分，与 6 个监控指标一一对应 */
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
  /* 【中文】等宽数字：每 5 秒刷新时数值宽度稳定，磁贴内容不抖动 */
  font-variant-numeric: tabular-nums;
}

/* responsive: stack on narrow screens (mobile adaptation, spec 4.4 bonus) */
/* 【中文】响应式断点 1100px（规格 4.4 的加分项：移动端适配）：
   三栏收缩为单栏纵向堆叠，底部六列磁贴改为两行三列，面板最小高度同步降低 */
@media (max-width: 1100px) {
  .screen-main {
    /* 【中文】主网格由三栏变单栏：饼图 / 折线图 / 预警列表自上而下排列 */
    grid-template-columns: 1fr;
  }
  .screen-footer {
    /* 【中文】磁贴由 repeat(6, 1fr) 变为 repeat(3, 1fr)，即两行三列 */
    grid-template-columns: repeat(3, 1fr);
  }
  .panel {
    min-height: 300px;
  }
}
</style>
