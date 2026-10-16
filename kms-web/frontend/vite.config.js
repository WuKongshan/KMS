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
    port: 3001,
    host: '0.0.0.0',
    allowedHosts: true,
    hmr: {
      protocol: 'ws',
      clientPort: 80
    },
    proxy: {
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
