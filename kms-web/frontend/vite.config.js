import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Development: `npm run dev` serves on :3001 and proxies /api to the web
// backend on :3000 (the web app owns :3000, so the dev server takes :3001 to
// avoid the clash). Production: `npm run build` -> dist/ is copied into
// kms-web/src/main/resources/static and served by Spring Boot on :3000.
export default defineConfig({
  plugins: [vue()],
  base: '/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    // 开发服务器端口：web 应用已占用 3000，开发服务器改用 3001，避免同时启动时端口冲突
    port: 3001,
    proxy: {
      // 代理目标必须是 web 应用实际监听的端口（3000），否则开发模式下接口全部 404/连不上
      '/api': {
        target: 'http://localhost:3000',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 2000
  }
})
