/**
 * Vite 配置：React 插件 + `@` 路径别名 + 开发代理 + 构建分包 + 单测（vitest）配置
 * 核心功能：把浏览器对 /api 的请求转发到后端网关 :8080，规避跨域（生产由 Nginx 承担同样职责）
 * 注意：这里只配 /api，**不**启用 host 监听；端口固定 5173 与 05 计划一致
 *
 * `defineConfig` 从 `vitest/config` 导入（它兼容 Vite 的配置类型并额外认识 `test` 段），
 * 这样一个文件就能同时管构建和单测，不必再拆一份 vitest.config.ts。
 */
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
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
      // WebSocket 也代理（T4.F1）：让前端在开发期就用**同源**的 ws://localhost:5173/ws/notifications，
      // 与生产（Nginx 反代同一域名）形态一致——否则前端要写死 :8080，
      // 而且 Chrome 的本地网络访问保护对"跨源→localhost"另有拦截（T4.6 踩过）。
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 分包（评审 P1-15）：此前所有依赖打进**一个** 1.36MB 的 chunk，
        // 改一行业务代码就让整个包失效、浏览器必须重下全部依赖。
        // 按"变更频率"切三刀：react 运行时 / antd 组件库 / 其余工具库——
        // 业务代码迭代时这三个 vendor 包基本不动，能一直吃浏览器缓存。
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-antd': ['antd'],
          'vendor-utils': ['axios', 'dayjs', 'zod', 'zustand', 'immer', 'ahooks', 'react-hook-form'],
        },
      },
    },
  },
  test: {
    // 单测只覆盖**纯逻辑**（校验规则、几何换算、状态映射），不引 jsdom/测试库：
    // 组件行为交给 web/e2e 的 playwright 脚本（真浏览器、真接口），两者不重复投入
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
