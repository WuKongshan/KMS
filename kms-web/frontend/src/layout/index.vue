<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import ws from '@/utils/ws'
import AlertPopup from '@/components/AlertPopup.vue'

// RuoYi-style admin layout: dark sidebar + light header + content area.
const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const wsConnected = ref(false)
const now = ref(dayjs())
let clockTimer
let offStatus

onMounted(() => {
  ws.connect()
  offStatus = ws.onStatus((ok) => (wsConnected.value = ok))
  clockTimer = setInterval(() => (now.value = dayjs()), 1000)
})
onBeforeUnmount(() => {
  clearInterval(clockTimer)
  if (offStatus) offStatus()
})

const pageTitle = computed(() => route.meta?.title || '')

const menus = [
  { path: '/realtime', title: '实时监控', icon: 'Odometer' },
  { path: '/history', title: '历史查询', icon: 'Search' },
  { path: '/alert', title: '预警管理', icon: 'Bell' },
  { path: '/ops', title: '运维管理', icon: 'Setting' }
]

function openScreen() {
  window.open(router.resolve({ path: '/screen' }).href, '_blank')
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '210px'" class="sidebar">
      <div class="logo">
        <el-icon :size="22" color="#409eff"><Monitor /></el-icon>
        <span v-show="!collapsed" class="logo-text">关键指标监控系统</span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="sidebar-menu"
      >
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <el-icon><component :is="menu.icon" /></el-icon>
          <template #title>{{ menu.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="navbar">
        <div class="navbar-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="page-title">{{ pageTitle }}</span>
        </div>
        <div class="navbar-right">
          <el-tag :type="wsConnected ? 'success' : 'danger'" size="small" effect="plain">
            {{ wsConnected ? '预警推送已连接' : '预警推送已断开' }}
          </el-tag>
          <el-button size="small" type="primary" plain @click="openScreen">
            <el-icon><DataAnalysis /></el-icon>&nbsp;数据大屏
          </el-button>
          <span class="clock">{{ now.format('YYYY-MM-DD HH:mm:ss') }}</span>
        </div>
      </el-header>

      <el-main class="content">
        <router-view />
        <AlertPopup />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

/* RuoYi-style dark sidebar */
.sidebar {
  background: #304156;
  transition: width 0.2s;
  overflow-x: hidden;
}
.logo {
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-weight: 600;
  white-space: nowrap;
}
.logo-text {
  font-size: 15px;
}
.sidebar-menu {
  border-right: none;
  --el-menu-bg-color: #304156;
  --el-menu-text-color: #bfcbd9;
  --el-menu-hover-bg-color: #263445;
  --el-menu-active-color: #409eff;
  --el-menu-item-height: 48px;
}

/* Header */
.navbar {
  height: 50px;
  background: #fcfcfb;
  border-bottom: 1px solid rgba(11, 11, 11, 0.1);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}
.navbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.collapse-btn {
  font-size: 18px;
  cursor: pointer;
  color: #52514e;
}
.page-title {
  font-size: 15px;
  font-weight: 600;
}
.navbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.clock {
  font-size: 13px;
  color: #52514e;
  font-variant-numeric: tabular-nums;
}

.content {
  padding: 16px;
  overflow-y: auto;
}
</style>
