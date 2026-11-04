import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/admin': { target: 'http://127.0.0.1:18081', changeOrigin: true },
      '/v1': { target: 'http://127.0.0.1:18081', changeOrigin: true }
    }
  },
  build: {
    // Element Plus 体积较大，提高告警阈值避免噪音
    chunkSizeWarningLimit: 1500
  }
})
