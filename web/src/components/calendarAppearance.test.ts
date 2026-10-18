/**
 * 日历四色分类的单测（T6.1）：把“过期优先、待审批高于关系色”的优先级钉死。
 * 这些规则如果只写在组件 JSX 里，交叉状态（过期 + 待审批 + 我预订）几乎没法靠手点验证。
 */
import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import type { CalendarMeeting } from '@/api/meeting'
import { calendarCategory } from './calendarAppearance'

const NOW = dayjs('2026-10-17 12:00:00')
const ME = 4

function meeting(overrides: Partial<CalendarMeeting> = {}): CalendarMeeting {
  return {
    meetingId: 1,
    title: '评审会',
    startTime: '2026-10-17 14:00:00',
    endTime: '2026-10-17 15:00:00',
    status: 20,
    organiserId: 9,
    organiserName: '别人',
    invited: false,
    ...overrides,
  }
}

describe('calendarCategory（日历四色）', () => {
  it('我预订的（未来、非待审批）→ mine', () => {
    expect(calendarCategory(meeting({ organiserId: ME }), ME, NOW)).toEqual({
      category: 'mine',
      expired: false,
    })
  })

  it('邀请我的（未来、非待审批、非我预订）→ invited', () => {
    expect(calendarCategory(meeting({ invited: true }), ME, NOW)).toEqual({
      category: 'invited',
      expired: false,
    })
  })

  it('与我无关的未来会议 → other', () => {
    expect(calendarCategory(meeting(), ME, NOW)).toEqual({ category: 'other', expired: false })
  })

  it('待审批优先于“我预订/邀请我的” → pending', () => {
    expect(calendarCategory(meeting({ status: 10, organiserId: ME }), ME, NOW).category).toBe('pending')
    expect(calendarCategory(meeting({ status: 10, invited: true }), ME, NOW).category).toBe('pending')
  })

  it('已过期优先级最高：即使是我预订/邀请我的/待审批，也归入 other + expired', () => {
    const past = { startTime: '2026-10-17 09:00:00', endTime: '2026-10-17 10:00:00' }
    expect(calendarCategory(meeting({ ...past, organiserId: ME }), ME, NOW)).toEqual({
      category: 'other',
      expired: true,
    })
    expect(calendarCategory(meeting({ ...past, invited: true }), ME, NOW)).toEqual({
      category: 'other',
      expired: true,
    })
    expect(calendarCategory(meeting({ ...past, status: 10 }), ME, NOW)).toEqual({
      category: 'other',
      expired: true,
    })
  })

  it('结束时间恰好等于 now 也算过期（避免“这一秒还亮着、下一秒突然灰”的抖动）', () => {
    const endingNow = { startTime: '2026-10-17 11:00:00', endTime: '2026-10-17 12:00:00' }
    expect(calendarCategory(meeting(endingNow), ME, NOW).expired).toBe(true)
  })

  it('status=40 即使 endTime 仍在未来（提前结束），也按已过期处理（评审 R9）', () => {
    // 提前结束只把 status 改成 40、end_time 仍是原值；不能让它继续显示为“我预订/邀请我的”
    expect(calendarCategory(meeting({ status: 40, organiserId: ME }), ME, NOW)).toEqual({
      category: 'other',
      expired: true,
    })
    expect(calendarCategory(meeting({ status: 40, invited: true }), ME, NOW)).toEqual({
      category: 'other',
      expired: true,
    })
  })

  it('没有当前用户信息时，“我预订”退化为 other；invited 是服务端按当前用户算好的，仍可信', () => {
    // organiserId 需要与当前用户比对，没有 userId 时无法判断“我的”
    expect(calendarCategory(meeting({ organiserId: ME, invited: false }), undefined, NOW)).toEqual({
      category: 'other',
      expired: false,
    })
    // invited 由服务端按请求用户装配（不是前端猜的），即使 userStore 还没恢复也能正确显示
    expect(calendarCategory(meeting({ organiserId: 9, invited: true }), undefined, NOW)).toEqual({
      category: 'invited',
      expired: false,
    })
  })
})
