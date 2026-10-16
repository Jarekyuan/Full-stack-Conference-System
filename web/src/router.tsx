/**
 * src/router.tsx
 * 应用路由表（对应 02 文档 3.3 #9）。
 * 两级守卫（RequireAuth / RequireRole）已抽到 components/RouteGuards.tsx——本文件只留"路径 → 页面"。
 * 页面地图见 02 文档 4.1；T1.F3 阶段各业务页用 Placeholder 占位，后续任务逐个替换。
 */
import { createBrowserRouter, Navigate } from 'react-router-dom'
import Placeholder from '@/components/Placeholder'
import { RequireAuth, RequireRole } from '@/components/RouteGuards'
import NotificationList from '@/pages/NotificationList'
import MainLayout from '@/layouts/MainLayout'
import ApprovalCenter from '@/pages/ApprovalCenter'
import Calendar from '@/pages/Calendar'
import Login from '@/pages/Login'
import MeetingDetail from '@/pages/MeetingDetail'
import MeetingList from '@/pages/MeetingList'
import RoomManage from '@/pages/admin/RoomManage'

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
          { path: 'meetings', element: <MeetingList /> },
          { path: 'meetings/:id', element: <MeetingDetail /> },
          { path: 'notifications', element: <NotificationList /> },
          {
            path: 'approvals',
            element: (
              <RequireRole roles={['ADMIN', 'APPROVER']}>
                <ApprovalCenter />
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
