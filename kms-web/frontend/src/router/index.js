import { createRouter, createWebHashHistory } from 'vue-router'
import Layout from '@/layout/index.vue'

// Hash history: the built frontend is served as static resources by Spring
// Boot, so deep links must work without server-side rewrites.
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      component: Layout,
      redirect: '/realtime',
      children: [
        {
          path: 'realtime',
          name: 'Realtime',
          component: () => import('@/views/realtime/index.vue'),
          meta: { title: '实时监控', icon: 'Odometer' }
        },
        {
          path: 'history',
          name: 'History',
          component: () => import('@/views/history/index.vue'),
          meta: { title: '历史查询', icon: 'Search' }
        },
        {
          path: 'alert',
          name: 'Alert',
          component: () => import('@/views/alert/index.vue'),
          meta: { title: '预警管理', icon: 'Bell' }
        },
        {
          path: 'ops',
          name: 'Ops',
          component: () => import('@/views/ops/index.vue'),
          meta: { title: '运维管理', icon: 'Setting' }
        }
      ]
    },
    {
      path: '/screen',
      name: 'Screen',
      component: () => import('@/views/screen/index.vue'),
      meta: { title: '数据大屏' }
    }
  ]
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - 关键指标监控系统` : '关键指标监控系统'
})

export default router
