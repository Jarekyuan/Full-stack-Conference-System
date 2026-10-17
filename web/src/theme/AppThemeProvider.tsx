/**
 * src/theme/AppThemeProvider.tsx
 * 把主题模式接到 antd：暗色时使用 `theme.darkAlgorithm`，并把 `dark` class /
 * `data-theme` / `color-scheme` 同步到 <html>（Tailwind 与浏览器原生控件共用）。
 *
 * `index.html` 的内联脚本负责“React 挂载前”的防闪烁；这里的 effect 负责挂载后
 * 以及系统偏好变化的同步。
 */
import { useEffect, type ReactNode } from 'react'
import { ConfigProvider, theme as antdTheme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { useResolvedTheme } from '@/stores/themeStore'

export default function AppThemeProvider({ children }: { children: ReactNode }) {
  const resolved = useResolvedTheme()

  useEffect(() => {
    const root = document.documentElement
    root.classList.toggle('dark', resolved === 'dark')
    root.dataset.theme = resolved
    root.style.colorScheme = resolved
  }, [resolved])

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: resolved === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
      }}
    >
      {children}
    </ConfigProvider>
  )
}
