/**
 * Vite 配置：React 插件 + `@` 路径别名 + 开发代理
 * 核心功能：把浏览器对 /api 的请求转发到后端网关 :8080，规避跨域（生产由 Nginx 承担同样职责）
 * 注意：这里只配 /api，**不**启用 host 监听；端口固定 5173 与 05 计划一致
 */
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // 前端所有请求都走相对路径 /api/**，由这里转发到网关
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
