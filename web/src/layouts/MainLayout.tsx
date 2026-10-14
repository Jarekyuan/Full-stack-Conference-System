/**
 * src/layouts/MainLayout.tsx
 * 主布局：左侧角色化菜单 + 顶栏（Logo / 通知铃铛占位 / 用户下拉）。
 * 菜单按 role 过滤（02 文档 4.1 页面地图）：审批中心仅 APPROVER/ADMIN，管理端仅 ADMIN。
 * 退出：先尽力调后端 /auth/logout 作废 refreshToken，再清本地并跳登录（容错）。
 */
import { useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Avatar, Dropdown, Layout, Menu, theme, type MenuProps } from 'antd'
import {
  Bell,
  Building2,
  CalendarClock,
  CalendarDays,
  LayoutDashboard,
  LogOut,
  MessageSquareText,
  ShieldCheck,
  User as UserIcon,
  Users,
} from 'lucide-react'
import { logout as logoutApi } from '@/api/auth'
import { useUserStore } from '@/stores/userStore'

const { Sider, Header, Content } = Layout

/** 角色常量（对应 04 文档 5.2） */
const ROLE_ADMIN = 'ADMIN'
const ROLE_APPROVER = 'APPROVER'

type MenuItem = Required<MenuProps>['items'][number]

export default function MainLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const user = useUserStore((s) => s.user)
  const logoutStore = useUserStore((s) => s.logout)
  const [loggingOut, setLoggingOut] = useState(false)

  const { token } = theme.useToken()

  const role = user?.role ?? ''
  const isAdmin = role === ROLE_ADMIN
  const isApprover = role === ROLE_ADMIN || role === ROLE_APPROVER

  // 角色化菜单：基础项全员可见，审批/管理按角色追加
  const menuItems: MenuItem[] = [
    { key: '/calendar', icon: <CalendarDays size={16} />, label: '日历预约' },
    { key: '/meetings', icon: <CalendarClock size={16} />, label: '我的会议' },
    { key: '/notifications', icon: <Bell size={16} />, label: '通知中心' },
    ...(isApprover
      ? [{ key: '/approvals', icon: <ShieldCheck size={16} />, label: '审批中心' }]
      : []),
    ...(isAdmin
      ? [
          {
            type: 'group' as const,
            label: '管理端',
            children: [
              { key: '/admin/rooms', icon: <Building2 size={16} />, label: '会议室管理' },
              { key: '/admin/users', icon: <Users size={16} />, label: '用户管理' },
              { key: '/admin/dashboard', icon: <LayoutDashboard size={16} />, label: '统计看板' },
            ],
          },
        ]
      : []),
  ]

  const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
    navigate(key)
  }

  const handleLogout = async () => {
    if (loggingOut) return
    setLoggingOut(true)
    try {
      await logoutApi()
    } catch {
      // 后端登出失败（token 已过期等）不阻断本地退出
    } finally {
      logoutStore()
      navigate('/login', { replace: true })
      setLoggingOut(false)
    }
  }

  const userMenu: MenuProps['items'] = [
    {
      key: 'logout',
      icon: <LogOut size={16} />,
      label: '退出登录',
      onClick: handleLogout,
    },
  ]

  return (
    <Layout className="min-h-screen">
      <Sider theme="light" width={220}>
        <div className="flex h-16 items-center justify-center border-b border-slate-100 px-4">
          <MessageSquareText size={20} className="mr-2 text-blue-600" />
          <span className="text-base font-semibold text-slate-800">会议室预约</span>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={handleMenuClick}
          className="border-0"
        />
      </Sider>
      <Layout>
        <Header
          className="flex items-center justify-end border-b px-6"
          style={{ background: token.colorBgContainer, padding: '0 24px' }}
        >
          <div className="flex items-center gap-4">
            <Bell size={18} className="cursor-pointer text-slate-500" />
            <Dropdown menu={{ items: userMenu }} placement="bottomRight">
              <div className="flex cursor-pointer items-center gap-2">
                <Avatar size="small" icon={<UserIcon size={14} />} />
                <span className="text-slate-700">{user?.realName ?? user?.username ?? '未登录'}</span>
              </div>
            </Dropdown>
          </div>
        </Header>
        <Content style={{ margin: 16, padding: 16, background: token.colorBgContainer, borderRadius: 8 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
