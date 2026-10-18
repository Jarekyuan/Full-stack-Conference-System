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
 *
 * ⚠️ `version` 解决的是**两个权威来源互相覆盖**的竞态（2026-10-16 评审 O7）：
 * "点全部已读 → REST 请求在途" 期间若 WS 推来新值（1），REST 的旧响应（0）落地会把新值盖回去，
 * 角标要等下一次推送才纠正。做法：调用方在**发请求前**记下 version，落地时比对——
 * 期间被 WS 写过就丢弃这次 REST 结果（HTTP 响应天然可能比 WS 慢，不能假设谁先到）。
 */
import { create } from 'zustand'
import { immer } from 'zustand/middleware/immer'

interface NotificationState {
  unreadCount: number
  /** 每次写入自增：给"迟到的 REST 响应"当判据用 */
  version: number
  /** 只在收到 WS NOTIFICATION 时自增：通知中心页面用它触发列表刷新（O12） */
  tick: number
  /** 用权威值覆盖；传 `ifUnchangedSince` 时，期间已被别人写过就丢弃这次结果 */
  setUnreadCount: (count: number, ifUnchangedSince?: number) => void
  /** 收到一条通知先乐观 +1（随后到达的 UNREAD_COUNT 会用权威值校正） */
  bumpUnread: () => void
  /** 通知列表的刷新信号：与 version 分开，避免 reload→setUnreadCount 自触发循环 */
  bumpTick: () => void
  /** 登出时清空：不清的话下一个用户登录会先看到上一个人的角标（评审 O6） */
  reset: () => void
}

export const useNotificationStore = create<NotificationState>()(
  immer((set) => ({
    unreadCount: 0,
    version: 0,
    tick: 0,
    setUnreadCount: (count, ifUnchangedSince) => {
      set((state) => {
        if (ifUnchangedSince !== undefined && state.version !== ifUnchangedSince) {
          return // 这次响应已经过期（期间有更新的写入），丢弃
        }
        state.unreadCount = Math.max(count, 0)
        state.version += 1
      })
    },
    bumpUnread: () => {
      set((state) => {
        state.unreadCount += 1
        state.version += 1
      })
    },
    bumpTick: () => {
      set((state) => {
        state.tick += 1
      })
    },
    reset: () => {
      set((state) => {
        state.unreadCount = 0
        state.version += 1
      })
    },
  })),
)
