<template>
  <router-view />
</template>

<style>
/* Global styles (RuoYi-style admin, light theme). Palette per the KMS
   visualization design system: page plane #f9f9f7, surface #fcfcfb. */
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
}

body {
  font-family: system-ui, -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  background: #f9f9f7;
  color: #0b0b0b;
  -webkit-font-smoothing: antialiased;
}

* {
  box-sizing: border-box;
}

/* Thin scrollbars for a cleaner admin look */
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
.page-card {
  background: #fcfcfb;
  border-radius: 6px;
  border: 1px solid rgba(11, 11, 11, 0.1);
  padding: 16px;
  margin-bottom: 16px;
}
</style>
