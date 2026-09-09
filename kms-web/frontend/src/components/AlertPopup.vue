<script setup>
import { onMounted, onBeforeUnmount } from 'vue'
import { ElNotification } from 'element-plus'
import ws from '@/utils/ws'
import { playAlertSound } from '@/utils/sound'

// Alert popup (spec 4.4, item 6): every alert pushed over the analyser's
// WebSocket becomes an Element Plus notification with a sound. Mounted by
// the admin layout and by the dashboard screen — only one is ever active.
const LEVEL_CONFIG = {
  1: { title: '一级预警 · 阈值超限', type: 'warning' },
  2: { title: '二级预警 · 波动异常', type: 'info' },
  3: { title: '三级预警 · 严重预警', type: 'error' }
}

function handleAlert(alert) {
  const cfg = LEVEL_CONFIG[alert.alertLevel] || { title: '预警', type: 'warning' }
  playAlertSound()
  ElNotification({
    title: cfg.title,
    message: alert.message || `${alert.metricName || alert.metricCode} 触发预警`,
    type: cfg.type,
    duration: 8000,
    position: 'top-right'
  })
}

let offAlert
onMounted(() => {
  ws.connect()
  offAlert = ws.onAlert(handleAlert)
})
onBeforeUnmount(() => {
  if (offAlert) offAlert()
})
</script>

<template>
  <!-- invisible host component; notifications are rendered globally -->
  <span style="display: none" />
</template>
