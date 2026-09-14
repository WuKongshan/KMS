import { createRouter, createWebHashHistory } from 'vue-router'
// 布局组件同步引入（后台骨架首屏就要用），各业务页面则用 () => import() 懒加载
import Layout from '@/layout/index.vue'

// Hash history: the built frontend is served as static resources by Spring
// Boot, so deep links must work without server-side rewrites.
// 用 hash 模式（createWebHashHistory）而不是 history 模式：前端产物由 Spring Boot
// 当静态资源托管，history 模式下用户直接访问或刷新 /alert，服务器会去找 /alert 这个
// 文件从而返回 404（除非额外配置服务端 rewrite 规则）；而 hash 模式下 # 之后的内容
// 根本不会发给服务器，永远只请求 / 并返回 index.html，深链接与刷新都天然可用。
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      // 后台布局路由：带深色侧边栏 + 顶栏的骨架，下面 4 个业务页面都是它的子路由
      path: '/',
      component: Layout,
      // 访问根路径 / 时重定向到实时监控页
      redirect: '/realtime',
      children: [
        {
          // 子路由 path 不带前导斜杠，最终地址为 /realtime
          path: 'realtime',
          name: 'Realtime',
          // 路由级懒加载：Vite 自动分包，首屏只加载当前页面代码
          component: () => import('@/views/realtime/index.vue'),
          // meta.title 供侧边栏菜单文案与 document.title 使用；
          // meta.icon 是图标组件名字符串，由 <component :is="menu.icon" /> 渲染
          meta: { title: '实时监控', icon: 'Odometer' }
        },
        {
          // 历史查询页：按指标 + 时间范围查 TDengine
          path: 'history',
          name: 'History',
          component: () => import('@/views/history/index.vue'),
          meta: { title: '历史查询', icon: 'Search' }
        },
        {
          // 预警管理页：分页筛选预警记录、标记处理
          path: 'alert',
          name: 'Alert',
          component: () => import('@/views/alert/index.vue'),
          meta: { title: '预警管理', icon: 'Bell' }
        },
        {
          // 运维管理页：查看采集器状态、修改周期、启停采集（后端代理到 8081）
          path: 'ops',
          name: 'Ops',
          component: () => import('@/views/ops/index.vue'),
          meta: { title: '运维管理', icon: 'Setting' }
        }
      ]
    },
    {
      // 数据大屏：独立全屏页面，故意不挂在 Layout 之下（没有侧边栏与顶栏）。
      // 大屏要投到墙上，需要全屏常驻且用深色配色，与浅色后台混在同一个布局里样式会互相污染。
      path: '/screen',
      name: 'Screen',
      component: () => import('@/views/screen/index.vue'),
      meta: { title: '数据大屏' }
    }
  ]
})

// 全局后置钩子：每次路由切换完成后，把 meta.title 写进浏览器标签页标题
router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - 关键指标监控系统` : '关键指标监控系统'
})

// 导出路由实例（模块级单例），供 main.js 中 app.use(router) 使用
export default router
