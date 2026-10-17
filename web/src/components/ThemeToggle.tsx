/**
 * src/components/ThemeToggle.tsx
 * 顶栏/登录页的主题切换入口：亮色 / 暗色 / 跟随系统（三态）。
 * 当前模式持久化在 themeStore；具体解析（system → 亮/暗）由 useResolvedTheme 负责。
 */
import { Button, Dropdown, type MenuProps } from 'antd'
import { Monitor, Moon, Sun } from 'lucide-react'
import { useResolvedTheme, useThemeStore, type ThemeMode } from '@/stores/themeStore'

const ITEMS: MenuProps['items'] = [
  { key: 'light', icon: <Sun size={15} />, label: '亮色' },
  { key: 'dark', icon: <Moon size={15} />, label: '暗色' },
  { key: 'system', icon: <Monitor size={15} />, label: '跟随系统' },
]

export default function ThemeToggle() {
  const mode = useThemeStore((s) => s.mode)
  const setMode = useThemeStore((s) => s.setMode)
  const resolved = useResolvedTheme()

  const icon =
    mode === 'system' ? <Monitor size={18} /> : resolved === 'dark' ? <Moon size={18} /> : <Sun size={18} />

  return (
    <Dropdown
      placement="bottomRight"
      menu={{
        items: ITEMS,
        selectable: true,
        selectedKeys: [mode],
        onClick: ({ key }) => setMode(key as ThemeMode),
      }}
    >
      <Button type="text" aria-label="切换主题" title="切换主题" icon={icon} />
    </Dropdown>
  )
}
