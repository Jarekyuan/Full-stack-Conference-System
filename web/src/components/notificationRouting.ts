/**
 * src/components/notificationRouting.ts
 * 通知点击去向（T6.2，docs/04 3.3）：
 *   APPROVAL_TODO   → 需要先查会议状态（仍是 10 才进审批中心）
 *   MEETING_CREATED → 会议列表
 *   其余类型        → 信息型通知，只标已读、不跳转
 *
 * 抽成纯函数是为了让“六种类型分别去哪”可单测；组件只负责执行副作用（查状态 / navigate）。
 */
export type NotificationTarget = 'approvals-check' | 'meetings' | 'none'

export function notificationTarget(type: string): NotificationTarget {
  switch (type) {
    case 'APPROVAL_TODO':
      return 'approvals-check'
    case 'MEETING_CREATED':
      return 'meetings'
    // APPROVAL_RESULT / MEETING_REMIND / MEETING_UPDATED / MEETING_CANCELLED
    // 以及将来新增的未知类型：一律不跳转，避免误把用户带到不相关的页面
    default:
      return 'none'
  }
}
