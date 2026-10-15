/**
 * src/router.tsx
 * 应用路由 + 两级守卫（对应 02 文档 3.3 #9）：
 * - RequireAuth：无 token 跳 /login（验收②"退出后访问 /meetings 跳登录"）
 * - RequireRole：角色不足拦（审批中心=APPROVER/ADMIN，管理端=ADMIN）
 * 页面地图见 02 文档 4.1；T1.F3 阶段各业务页用 Placeholder 占位，后续任务逐个替换。
 */
import type { ReactNode } from 'react'
import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router-dom'
import { getAccessToken } from '@/api/token'
import Placeholder from '@/components/Placeholder'
import MainLayout from '@/layouts/MainLayout'
import Calendar from '@/pages/Calendar'
import Login from '@/pages/Login'
import RoomManage from '@/pages/admin/RoomManage'
import { useUserStore } from '@/stores/userStore'

/** 鉴权守卫：未登录（无 accessToken）重定向登录页，并带上原目标路径（登录后跳回） */
function RequireAuth() {
  const location = useLocation()
  if (!getAccessToken()) {
    const redirect = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?redirect=${redirect}`} replace />
  }
  return <Outlet />
}

/** 角色守卫：用户拥有的任一角色命中白名单即通过（RBAC 多角色）；否则回退到日历页 */
function RequireRole({ roles: allowed, children }: { roles: string[]; children: ReactNode }) {
  const user = useUserStore((s) => s.user)
  const hasRole = !!user && (user.roles ?? []).some((role) => allowed.includes(role))
  if (!hasRole) {
    return <Navigate to="/calendar" replace />
  }
  return <>{children}</>
}

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  {
    element: <RequireAuth />,
    children: [
      {
        path: '/',
        element: <MainLayout />,
        children: [
          { index: true, element: <Navigate to="/calendar" replace /> },
          { path: 'calendar', element: <Calendar /> },
          { path: 'meetings', element: <Placeholder title="我的会议" /> },
          { path: 'meetings/:id', element: <Placeholder title="会议详情" /> },
          { path: 'notifications', element: <Placeholder title="通知中心" /> },
          {
            path: 'approvals',
            element: (
              <RequireRole roles={['ADMIN', 'APPROVER']}>
                <Placeholder title="审批中心" />
              </RequireRole>
            ),
          },
          {
            path: 'admin/rooms',
            element: (
              <RequireRole roles={['ADMIN']}>
                <RoomManage />
              </RequireRole>
            ),
          },
          {
            path: 'admin/users',
            element: (
              <RequireRole roles={['ADMIN']}>
                <Placeholder title="用户管理" />
              </RequireRole>
            ),
          },
          {
            path: 'admin/dashboard',
            element: (
              <RequireRole roles={['ADMIN']}>
                <Placeholder title="统计看板" />
              </RequireRole>
            ),
          },
        ],
      },
    ],
  },
])
