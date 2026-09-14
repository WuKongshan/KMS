<template>
  <!-- 顶层路由出口：后台布局（Layout 下的实时/历史/预警/运维）与独立全屏的数据大屏 /screen 都由它渲染 -->
  <router-view />
</template>

<style>
/* Global styles (RuoYi-style admin, light theme). Palette per the KMS
   visualization design system: page plane #f9f9f7, surface #fcfcfb. */
/* 【中文】全局样式（若依风格后台，浅色主题）。配色取自 KMS 可视化设计系统：
   页面底色（plane）#f9f9f7，卡片/浮层表面色（surface）#fcfcfb。
   深色数据大屏（/#/screen）自带一套深色配色，不在这里覆盖，避免两套主题互相污染。 */
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
}

/* 【中文】字体栈：优先系统 UI 字体，中文回退到「微软雅黑」；开启字体抗锯齿让浅底深字更清晰。 */
body {
  font-family: system-ui, -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  background: #f9f9f7;
  color: #0b0b0b;
  -webkit-font-smoothing: antialiased;
}

/* 【中文】统一 border-box 盒模型：padding 与 border 计入元素宽度，避免卡片/栅格被撑破。 */
* {
  box-sizing: border-box;
}

/* Thin scrollbars for a cleaner admin look */
/* 【中文】细滚动条，让后台界面更清爽：滑块宽/高 8px、圆角 4px，轨道透明（仅 WebKit 内核生效）。 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
::-webkit-scrollbar-thumb {
  background: #c3c2b7;
  border-radius: 4px;
}
::-webkit-scrollbar-track {
  background: transparent;
}

/* Card container used by every page */
/* 【中文】各业务页面通用的卡片容器：表面色背景 + 6px 圆角 + 浅描边 + 16px 内边距，底部留 16px 间距。 */
.page-card {
  background: #fcfcfb;
  border-radius: 6px;
  border: 1px solid rgba(11, 11, 11, 0.1);
  padding: 16px;
  margin-bottom: 16px;
}
</style>
