/**
 * src/stores/themeStore.ts
 * 主题模式（T6.3）：亮色 / 暗色 / 跟随系统，持久化到 localStorage。
 *
 * 为什么用 `dark` class 而不是只靠 matchMedia：
 * - antd 5 的 darkAlgorithm 与 Tailwind 的 `dark:` 变体都以“class 存在”为开关；
 * - 用户手动选择必须能覆盖系统偏好，所以需要 class（不能只依赖媒体查询）。
 *
 * 防闪烁：`index.html` 里有一段内联脚本，在 React 挂载前按持久化值/系统偏好先加上 class；
 * 这里挂载后再同步一次（系统偏好变化时也走这里）。
 */
import { useEffect, useState } from 'react'
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

/** 纯函数：把三态模式 + 系统偏好解析成实际主题（可单测） */
export function resolveTheme(mode: ThemeMode, systemDark: boolean): ResolvedTheme {
  if (mode === 'system') {
    return systemDark ? 'dark' : 'light'
  }
  return mode
}

interface ThemeState {
  mode: ThemeMode
  setMode: (mode: ThemeMode) => void
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set) => ({
      mode: 'system',
      setMode: (mode) => set({ mode }),
    }),
    { name: 'meeting.theme' }, // index.html 的内联脚本按这个 key 预置 class
  ),
)

/** 系统偏好（浏览器不支持 matchMedia 时按亮色处理） */
function getSystemDark(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)').matches
    : false
}

export function useSystemDark(): boolean {
  const [systemDark, setSystemDark] = useState(getSystemDark)
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = (event: MediaQueryListEvent) => setSystemDark(event.matches)
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])
  return systemDark
}

export function useResolvedTheme(): ResolvedTheme {
  const mode = useThemeStore((s) => s.mode)
  const systemDark = useSystemDark()
  return resolveTheme(mode, systemDark)
}
