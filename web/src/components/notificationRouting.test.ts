/**
 * 通知点击去向的单测（T6.2）：把六种类型 + 未知类型的目标钉死。
 * 这些分支如果只写在组件 switch 里，要靠 e2e 逐类造通知才能覆盖，成本高且容易漏。
 */
import { describe, expect, it } from 'vitest'
import { notificationTarget } from './notificationRouting'

describe('notificationTarget', () => {
  it('待审批 → 先查会议状态（approvals-check）', () => {
    expect(notificationTarget('APPROVAL_TODO')).toBe('approvals-check')
  })

  it('预约创建 → 会议列表', () => {
    expect(notificationTarget('MEETING_CREATED')).toBe('meetings')
  })

  it('审批结果 / 提醒 / 变更 / 取消 → 不跳转', () => {
    for (const type of [
      'APPROVAL_RESULT',
      'MEETING_REMIND',
      'MEETING_UPDATED',
      'MEETING_CANCELLED',
    ]) {
      expect(notificationTarget(type)).toBe('none')
    }
  })

  it('未知类型 → 不跳转（新增通知类型不会意外把用户带走）', () => {
    expect(notificationTarget('SOMETHING_NEW')).toBe('none')
  })
})
