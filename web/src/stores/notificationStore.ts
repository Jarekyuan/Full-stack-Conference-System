/**
 * src/stores/notificationStore.ts
 * 未读数的**客户端镜像**（顶栏铃铛角标）。
 *
 * 职责边界（与 userStore 同一条原则）：
 * - 真实来源是后端（REST `GET /notifications/unread-count`），这里只放"当前显示的角标值"；
 * - **WS 推来的 UNREAD_COUNT 是权威值，直接覆盖**——不靠前端自己 +1 累加：
 *   丢一条推送、多端同时在线各自计数，角标就会长期偏离真实值，而角标是用户判断
 *   "有没有人找我"的唯一依据（docs/04 6.2 的取舍）。
 * - 不 persist：它是**服务端状态**，刷新页面重新拉即可；持久化反而会在断线时展示过期数字。
 */
import { create } from 'zustand'
import { immer } from 'zustand/middleware/immer'

interface NotificationState {
  unreadCount: number
  /** 用权威值覆盖（REST 拉取 / WS 的 UNREAD_COUNT 都走它） */
  setUnreadCount: (count: number) => void
  /** 收到一条通知先乐观 +1（随后到达的 UNREAD_COUNT 会校正它） */
  bumpUnread: () => void
}

export const useNotificationStore = create<NotificationState>()(
  immer((set) => ({
    unreadCount: 0,
    setUnreadCount: (count) => {
      set((state) => {
        state.unreadCount = Math.max(count, 0)
      })
    },
    bumpUnread: () => {
      set((state) => {
        state.unreadCount += 1
      })
    },
  })),
)
