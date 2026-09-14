<script setup>
import { onMounted, onBeforeUnmount } from 'vue'
import { ElNotification } from 'element-plus'
import ws from '@/utils/ws'
import { playAlertSound } from '@/utils/sound'

// Alert popup (spec 4.4, item 6): every alert pushed over the analyser's
// WebSocket becomes an Element Plus notification with a sound. Mounted by
// the admin layout and by the dashboard screen — only one is ever active.
//
// 全局预警弹窗（规格 4.4 第 6 项）：analyser 通过 WebSocket 推送的每条预警，
// 都会变成一个带声音提示的 Element Plus 通知（ElNotification）。
// 该组件被后台布局 layout 与数据大屏 screen 各挂载一次；两者属于互斥的路由布局，
// 同一时刻只有一个存活，所以同一条预警不会被弹出两次。
const LEVEL_CONFIG = {
  // 级别 → 标题/通知类型的映射：1 级最轻（warning 黄）、2 级波动（info 灰蓝）、3 级最重（error 红）
  1: { title: '一级预警 · 阈值超限', type: 'warning' },
  2: { title: '二级预警 · 波动异常', type: 'info' },
  3: { title: '三级预警 · 严重预警', type: 'error' }
}

function handleAlert(alert) {
  // 未知级别（后端新增级别或字段缺失）时兜底为通用的"预警"/warning，保证通知一定能弹出来
  const cfg = LEVEL_CONFIG[alert.alertLevel] || { title: '预警', type: 'warning' }
  // 先播音再弹窗：playAlertSound() 内部对音频异常做了静默处理，音频不可用也不影响弹窗
  playAlertSound()
  ElNotification({
    title: cfg.title,
    // 优先用后端下发的 message；缺失时用"指标名/指标代码 + 触发预警"兜底
    message: alert.message || `${alert.metricName || alert.metricCode} 触发预警`,
    type: cfg.type,
    // 8000ms 比 Element Plus 默认的 4500ms 长近一倍：预警文案较长，需要更充裕的阅读时间
    duration: 8000,
    position: 'top-right'
  })
}

let offAlert
// ws.onAlert() 返回的退订函数；卸载时必须调用，否则弹窗回调会随路由切换不断累积
onMounted(() => {
  // ws 是全局单例，connect 幂等：layout 与 screen 先后挂载也只会建立一条连接
  ws.connect()
  // 订阅预警事件：收到推送即执行 handleAlert（先播音、再弹窗）
  offAlert = ws.onAlert(handleAlert)
})
onBeforeUnmount(() => {
  // 退订预警回调，避免组件销毁后仍被推送触发
  if (offAlert) offAlert()
})
</script>

<template>
  <!-- invisible host component; notifications are rendered globally -->
  <!-- 【中文】不可见的宿主组件：这里只提供一个 display:none 的占位节点，
       真正的 UI 是由 Element Plus 全局渲染到 body 上的 ElNotification 通知 -->
  <span style="display: none" />
</template>
