<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import ws from '@/utils/ws'
import { clearAuth, getProfile } from '@/utils/session'
import AlertPopup from '@/components/AlertPopup.vue'

// RuoYi-style admin layout: dark sidebar + light header + content area.
//
// 若依（RuoYi）风格的后台布局：深色侧边栏 + 亮色顶栏 + 内容区。
// 它是整个后台的骨架组件：左侧菜单在 210px（展开）与 64px（折叠）之间切换，
// 顶栏承载折叠按钮、当前页面标题、WebSocket 连接状态标签、数据大屏入口和实时时钟；
// 内容区通过 <router-view /> 渲染各业务页面，并常驻一个全局预警弹窗 AlertPopup。
const route = useRoute()
const router = useRouter()
// 侧边栏折叠状态：true 时 el-aside 宽度收窄到 64px，只保留图标
const collapsed = ref(false)
// 预警推送 WebSocket 连接状态，由 ws.onStatus 回调驱动，用于顶栏的状态标签
const wsConnected = ref(false)
// 顶栏时钟显示的当前时间（dayjs 对象），由 1 秒定时器刷新
const now = ref(dayjs())
let clockTimer
// 1 秒时钟定时器句柄；组件卸载时必须 clearInterval，否则定时器会一直空转
let offStatus
// ws.onStatus() 返回的退订函数；卸载时调用，防止路由切换导致状态回调不断累积

onMounted(() => {
  // 建立预警推送连接（ws 是全局单例，重复 connect 不会重复建连）
  ws.connect()
  // 订阅连接状态变化：回调参数 ok 即当前是否已连接，直接同步到 wsConnected
  offStatus = ws.onStatus((ok) => (wsConnected.value = ok))
  // 1 秒一次的时钟定时器，驱动顶栏 "YYYY-MM-DD HH:mm:ss" 的显示
  clockTimer = setInterval(() => (now.value = dayjs()), 1000)
})
onBeforeUnmount(() => {
  // 清理时钟定时器，避免组件销毁后仍在更新已失效的响应式引用
  clearInterval(clockTimer)
  // 退订状态回调：不退订的话每次进出布局都会多注册一个回调，越积越多形成内存泄漏
  if (offStatus) offStatus()
})

// 顶栏标题直接取路由 meta.title（在 router 中配置），取不到时回退为空字符串
const pageTitle = computed(() => route.meta?.title || '')

// 侧边栏菜单全集：path 与路由表一一对应，icon 是 Element Plus 的图标组件名
// （模板里用 <component :is="menu.icon" /> 动态渲染）
// adminOnly = true 的菜单只对 ADMIN 显示（指标配置的接口本身也做了 ADMIN 校验，
// 前端过滤只是避免让普通用户点进去看 403）
const MENUS = [
  { path: '/realtime', title: '实时监控', icon: 'Odometer' },
  { path: '/history', title: '历史查询', icon: 'Search' },
  { path: '/alert', title: '预警管理', icon: 'Bell' },
  { path: '/ops', title: '运维管理', icon: 'Setting' },
  { path: '/metric-config', title: '指标配置', icon: 'SetUp', adminOnly: true },
  { path: '/operlog', title: '操作日志', icon: 'Document' }
]

function openScreen() {
  // 用 router.resolve 解析出带 hash 前缀的完整地址（本项目为 hash 路由），
  // 再交给 window.open(..., '_blank')，在浏览器新标签页中打开数据大屏
  window.open(router.resolve({ path: '/screen' }).href, '_blank')
}

// 当前登录用户（登录时存入 localStorage，顶栏只做展示）；
// 昵称为空时回退显示登录名，避免顶栏出现空白
const profile = getProfile()

// 侧边栏实际展示的菜单：按角色过滤掉仅管理员可见的项（如"指标配置"）
const menus = computed(() => MENUS.filter((m) => !m.adminOnly || profile.role === 'ADMIN'))

function handleLogout() {
  // 无状态令牌无法在服务端作废：清掉本地令牌即完成退出
  clearAuth()
  // 跳到登录页后强制刷新，清空内存里的残留状态（WebSocket 连接、各页面缓存的数据）
  window.location.hash = '#/login'
  window.location.reload()
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '210px'" class="sidebar">
      <!-- 【中文】侧边栏宽度由 collapsed 决定：210px（展开）/ 64px（折叠），过渡动画见 .sidebar 的 transition -->
      <div class="logo">
        <el-icon :size="22" color="#409eff"><Monitor /></el-icon>
        <!-- 【中文】折叠时用 v-show 隐藏标题文字，保留节点让宽度过渡更平滑 -->
        <span v-show="!collapsed" class="logo-text">关键指标监控系统</span>
      </div>
      <!-- 【中文】:default-active 绑定当前路由路径实现菜单高亮；router 模式下点击菜单项即执行路由跳转；
           :collapse-transition="false" 关闭菜单自带的折叠动画，避免与 aside 的 width 过渡叠加产生抖动 -->
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="sidebar-menu"
      >
        <!-- 【中文】:index 必须是路由路径（router 模式下它就是跳转目标）；折叠时 #title 插槽自动隐藏，只留图标 -->
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <el-icon><component :is="menu.icon" /></el-icon>
          <template #title>{{ menu.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="navbar">
        <div class="navbar-left">
          <!-- 【中文】折叠开关：展开时显示 Fold 图标，折叠时显示 Expand 图标 -->
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="page-title">{{ pageTitle }}</span>
        </div>
        <div class="navbar-right">
          <!-- 【中文】WebSocket 连接状态标签：已连接为绿色 success，断开为红色 danger -->
          <el-tag :type="wsConnected ? 'success' : 'danger'" size="small" effect="plain">
            {{ wsConnected ? '预警推送已连接' : '预警推送已断开' }}
          </el-tag>
          <!-- 【中文】数据大屏入口：在浏览器新标签页中打开 /#/screen（见 openScreen） -->
          <el-button size="small" type="primary" plain @click="openScreen">
            <el-icon><DataAnalysis /></el-icon>&nbsp;数据大屏
          </el-button>
          <!-- 【中文】当前登录用户与退出入口：退出会清掉令牌并回登录页（见 handleLogout） -->
          <span class="user-name">{{ profile.nickname || profile.username }}</span>
          <el-button size="small" text @click="handleLogout">退出</el-button>
          <!-- 【中文】实时时钟：由 1 秒定时器不断刷新 now -->
          <span class="clock">{{ now.format('YYYY-MM-DD HH:mm:ss') }}</span>
        </div>
      </el-header>

      <el-main class="content">
        <!-- 【中文】业务页面出口；AlertPopup 常驻于此，作为全局预警弹窗的宿主（模板本身不可见） -->
        <router-view />
        <AlertPopup />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  /* 【中文】占满整个视口高度：下挂 el-container 撑满，内容区内部滚动 */
  height: 100vh;
}

/* RuoYi-style dark sidebar */
/* 【中文】若依风格的深色侧边栏：#304156 即若依默认的侧边栏底色 */
.sidebar {
  background: #304156;
  /* 【中文】宽度过渡 0.2s，与 el-aside 的 :width 切换配合，折叠时有缓动而非瞬变 */
  transition: width 0.2s;
  /* 【中文】折叠时菜单文字会溢出，横向裁掉，避免出现横向滚动条 */
  overflow-x: hidden;
}
.logo {
  /* 【中文】高度与顶栏 navbar 一致（都是 50px），左右两栏的视觉基线才能对齐 */
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-weight: 600;
  /* 【中文】禁止换行：折叠动画过程中标题文字不会折成两行 */
  white-space: nowrap;
}
.logo-text {
  font-size: 15px;
}
.sidebar-menu {
  border-right: none;
  /* 【中文】覆盖 Element Plus 菜单的 CSS 变量来实现深色菜单主题： #0d4891 #304156
     底色 #304156、常规文字 #bfcbd9、悬停底色 #263445、选中高亮 #409eff、菜单项行高 48px */
  --el-menu-bg-color: #304156;
  --el-menu-text-color: #bfcbd9;
  --el-menu-hover-bg-color: #263445;
  --el-menu-active-color: #ff4040;
  --el-menu-item-height: 48px;
}

/* Header */
/* 【中文】亮色顶栏：与深色侧边栏形成明暗对比，底部一条浅色分隔线 */
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
  /* 【中文】鼠标移上去变手型，提示这是可点击的折叠开关 */
  cursor: pointer;
  color: #22c756;
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
  /* 【中文】等宽数字：秒数跳动时字符宽度一致，右侧元素不会左右抖动 */
  font-variant-numeric: tabular-nums;
}
.user-name {
  font-size: 13px;
  color: #52514e;
}

.content {
  padding: 16px;
  /* 【中文】内容区独立纵向滚动：侧边栏与顶栏固定，只有这里滚动 */
  overflow-y: auto;
}
</style>
