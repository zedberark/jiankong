import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5181,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8083',
        changeOrigin: true,
        ws: true,
        // 巡检/批量等长请求默认会超过 Node 代理或浏览器侧限制，与 inspection.js 超时对齐（10 分钟）
        timeout: 600000,
        proxyTimeout: 600000,
      },
    },
  },
})
