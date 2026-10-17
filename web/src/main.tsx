/**
 * 应用入口：挂载 React 18 根节点 + antd 全局配置（中文语言包 + App 上下文）
 * 核心功能：
 * 1. createRoot 挂载 <App />（React 18 并发特性入口）
 * 2. ConfigProvider 注入 zhCN，使 antd 组件文案/日期本地化
 * 3. antd <App> 提供 message/notification 的命令式 API 上下文（后续 useAppRequest 的错误 toast 依赖它）
 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { App as AntdApp } from 'antd'
import 'dayjs/locale/zh-cn'
import dayjs from 'dayjs'

import { router } from './router'
import AppThemeProvider from './theme/AppThemeProvider'
import './index.css'

dayjs.locale('zh-cn')

const container = document.getElementById('root')
if (!container) {
  throw new Error('#root 节点缺失，请检查 index.html')
}

createRoot(container).render(
  <StrictMode>
    <AppThemeProvider>
      <AntdApp>
        <RouterProvider router={router} />
      </AntdApp>
    </AppThemeProvider>
  </StrictMode>,
)
