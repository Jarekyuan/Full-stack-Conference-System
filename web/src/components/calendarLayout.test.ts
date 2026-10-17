/**
 * 重叠会议泳道布局的单测（T6.1）。
 * 重点是三个容易写错的边界：相邻但不相交、不同时间簇互不挤压、链式重叠的泳道复用。
 */
import { describe, expect, it } from 'vitest'
import type { CalendarMeeting } from '@/api/meeting'
import { layoutMeetings } from './calendarLayout'

let nextId = 1
function meeting(start: string, end: string, id = nextId++): CalendarMeeting {
  return {
    meetingId: id,
    title: `会议${id}`,
    startTime: `2026-10-17 ${start}:00`,
    endTime: `2026-10-17 ${end}:00`,
    status: 20,
    organiserId: 4,
    organiserName: '张三',
  }
}

describe('layoutMeetings（重叠会议分泳道）', () => {
  it('两场重叠 → 同一簇、两条泳道', () => {
    const a = meeting('09:00', '10:00')
    const b = meeting('09:30', '10:30')
    const result = layoutMeetings([a, b])
    expect(result.get(a.meetingId)).toEqual({ lane: 0, lanes: 2 })
    expect(result.get(b.meetingId)).toEqual({ lane: 1, lanes: 2 })
  })

  it('三场两两重叠 → 三条泳道', () => {
    const a = meeting('09:00', '10:00')
    const b = meeting('09:15', '10:15')
    const c = meeting('09:30', '10:30')
    const result = layoutMeetings([a, b, c])
    expect(result.get(a.meetingId)?.lanes).toBe(3)
    expect(new Set([a, b, c].map((m) => result.get(m.meetingId)?.lane))).toEqual(new Set([0, 1, 2]))
  })

  it('相邻但不相交（10:00 结束 / 10:00 开始）→ 各自独占一条泳道', () => {
    const a = meeting('09:00', '10:00')
    const b = meeting('10:00', '11:00')
    const result = layoutMeetings([a, b])
    expect(result.get(a.meetingId)).toEqual({ lane: 0, lanes: 1 })
    expect(result.get(b.meetingId)).toEqual({ lane: 0, lanes: 1 })
  })

  it('两个独立时间簇 → 各自按自己的泳道数算，不被对方撑宽', () => {
    const a1 = meeting('09:00', '10:00')
    const a2 = meeting('09:30', '10:30')
    const b1 = meeting('14:00', '15:00')
    const b2 = meeting('14:30', '15:30')
    const result = layoutMeetings([a1, a2, b1, b2])
    expect(result.get(a1.meetingId)?.lanes).toBe(2)
    expect(result.get(b1.meetingId)?.lanes).toBe(2)
    // 两簇都是 2 条泳道，但不是同一个 4 泳道簇
    expect(result.get(b1.meetingId)?.lane).toBe(0)
  })

  it('链式重叠：第一场结束后泳道可以被第三场复用', () => {
    const a = meeting('09:00', '10:00')
    const b = meeting('09:30', '10:30')
    const c = meeting('10:15', '11:15') // 与 a 不重叠，与 b 重叠
    const result = layoutMeetings([a, b, c])
    expect(result.get(a.meetingId)).toEqual({ lane: 0, lanes: 2 })
    expect(result.get(b.meetingId)).toEqual({ lane: 1, lanes: 2 })
    expect(result.get(c.meetingId)).toEqual({ lane: 0, lanes: 2 })
  })

  it('单个会议 → lane 0 / lanes 1', () => {
    const a = meeting('09:00', '10:00')
    expect(layoutMeetings([a]).get(a.meetingId)).toEqual({ lane: 0, lanes: 1 })
  })

  it('空数组 → 空 Map（不抛异常）', () => {
    expect(layoutMeetings([]).size).toBe(0)
  })
})
