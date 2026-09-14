/**
 * Application entry: assembles the Vue 3 app (Element Plus + router) and
 * mounts it into #app.
 *
 * 【中文】应用入口：装配整个前端应用——注册 Element Plus（含中文语言包）、全量图标组件
 * 与路由，最后把根组件挂载到 index.html 中的 #app 节点上。
 */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)

// Register all Element Plus icons globally (RuoYi-style menus use them).
// 全量注册 Element Plus 图标为全局组件：菜单配置里存的是图标名字符串
//（如 icon: 'Odometer'），模板用 <component :is="menu.icon" /> 按名字动态渲染，
// 因此必须把所有图标都注册进来；代价是打包体积略大，换来的是菜单配置的灵活性。
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// locale: zhCn 让 Element Plus 内置文案变中文——分页的"共 X 条"、日期选择器占位符、
// el-empty 的"暂无数据"、确认框按钮等；不设置则默认英文。
app.use(ElementPlus, { locale: zhCn })
// 注册路由插件，路由表与 hash 模式见 router/index.js
app.use(router)
// 挂载根实例：index.html 里的 <div id="app"></div> 就是这里的挂载点
app.mount('#app')
