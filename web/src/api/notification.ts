/**
 * src/api/notification.ts
 * 通知域接口封装，对应后端 NotificationController（网关路由 /api/notifications/**）。
 * 真相源：docs/04-接口设计.md 3.3（含 T4.4 补登的契约）。
 *
 * 收件人由后端按网关注入的身份决定，前端**不传 userId**（传了也没用，后端不看）。
 */
import { http } from './request'
import type { PageResult } from './types'

/** 通知条目（后端 NotificationVO；字段与 WS 推送体一致，列表与实时推送共用一套渲染） */
export interface NotificationItem {
  id: number
  /** MEETING_CREATED / APPROVAL_TODO / APPROVAL_RESULT / MEETING_REMIND / MEETING_UPDATED / MEETING_CANCELLED */
  type: string
  title: string
  content: string
  /** 关联会议（点击跳详情），可能为空 */
  meetingId: number | null
  /** 0 未读 / 1 已读 */
  readFlag: number
  createdAt: string
}

export const fetchNotifications = (page: number, size: number) =>
  http.get<PageResult<NotificationItem>>('/notifications', { params: { page, size } })

export const fetchUnreadCount = () => http.get<{ unreadCount: number }>('/notifications/unread-count')

/** 单条已读（幂等：重复标记不报错；别人的通知 → 60001，前端静默） */
export const markRead = (id: number) => http.post<void>(`/notifications/${id}/read`)

/** 全部已读；返回实际更新条数 */
export const markAllRead = () => http.post<{ updated: number }>('/notifications/read-all')

/** 批量删除选中的通知（只删自己的；返回实际删除条数；单条删除由批量接口覆盖） */
export const deleteNotifications = (ids: number[]) =>
  http.post<{ deleted: number }>('/notifications/delete', { ids })

/** 删除我的全部通知（返回实际删除条数） */
export const deleteAllNotifications = () => http.delete<{ deleted: number }>('/notifications')
