/**
 * 日历时间轴几何换算的单测（评审 P1-15）。
 * 覆盖三处最容易算错的地方：点击吸附、窗口裁剪、跨天/越界会议的块高。
 */
import { describe, expect, it } from 'vitest'
import type { CalendarMeeting } from '@/api/meeting'
import dayjs from 'dayjs'
import {
  blockGeometry,
  DAY_END_MINUTES,
  DAY_START_MINUTES,
  pastRatio,
  slotFromClickRatio,
  toMinutes,
} from './calendarGeometry'

/** 造一个只关心时间的会议块 */
function meeting(startTime: string, endTime: string): CalendarMeeting {
  return { meetingId: 1, title: '评审会', startTime, endTime, status: 20, organiserId: 4, organiserName: '张三' }
}

describe('slotFromClickRatio（点击空白格 → 预填时段）', () => {
  it('点最顶部 → 08:00 起，默认 1 小时', () => {
    expect(slotFromClickRatio(0)).toEqual({ startMinutes: 480, endMinutes: 540 })
  })

  it('点正中间（14:00）→ 14:00–15:00', () => {
    expect(slotFromClickRatio(0.5)).toEqual({ startMinutes: 840, endMinutes: 900 })
  })

  it('吸附到最近的 30 分钟：往下偏一点仍归 14:00，再往下则进到 14:30', () => {
    expect(slotFromClickRatio(0.52).startMinutes).toBe(840) // 14:06.4 → 14:00
    expect(slotFromClickRatio(0.54).startMinutes).toBe(870) // 14:28.8 → 14:30
  })

  it('点最底部 → 19:30–20:00（**不是**零时长的 20:00–20:00）', () => {
    // 回归：早期实现点最底部会得 start=end=20:00，预填进表单立刻撞"结束时间必须晚于开始时间"
    const slot = slotFromClickRatio(1)
    expect(slot).toEqual({ startMinutes: 1170, endMinutes: DAY_END_MINUTES })
    expect(slot.endMinutes).toBeGreaterThan(slot.startMinutes)
  })

  it('点在格子外（比例 <0 或 >1，如子元素边缘）也裁进窗口', () => {
    expect(slotFromClickRatio(-0.5).startMinutes).toBe(DAY_START_MINUTES)
    expect(slotFromClickRatio(1.5).startMinutes).toBe(19 * 60 + 30)
  })
})

describe('toMinutes', () => {
  it('取"距 00:00 的分钟数"，与日期无关', () => {
    expect(toMinutes('2026-10-15 00:00:00')).toBe(0)
    expect(toMinutes('2026-10-15 08:30:00')).toBe(510)
    expect(toMinutes('2026-10-16 23:59:00')).toBe(1439)
  })
})

describe('blockGeometry（会议块定位）', () => {
  it('窗口内的整点会议：top/height 按比例，时长 60 分钟', () => {
    const { style, minutes } = blockGeometry(meeting('2026-10-15 09:00:00', '2026-10-15 10:00:00'))
    expect(minutes).toBe(60)
    // 09:00 距窗口起点 60 分钟，窗口总长 720 分钟
    expect(style.top).toBe(`${(60 / 720) * 100}%`)
    expect(style.height).toBe(`${(60 / 720) * 100}%`)
  })

  it('早于 08:00 开始的会议裁到窗口顶端，高度不变负', () => {
    const { style, minutes } = blockGeometry(meeting('2026-10-15 07:00:00', '2026-10-15 09:00:00'))
    expect(style.top).toBe('0%')
    expect(minutes).toBe(60) // 07:00 被裁到 08:00，只剩 1 小时落在窗口内
  })

  it('跨天会议（结束在次日）算到窗口末端，不出现负高度', () => {
    const { style, minutes } = blockGeometry(meeting('2026-10-15 19:00:00', '2026-10-16 01:00:00'))
    expect(minutes).toBe(60) // 19:00 → 20:00
    expect(style.height).toBe(`${(60 / 720) * 100}%`)
    expect(minutes).toBeGreaterThan(0)
  })

  it('整段都在窗口外的会议：高度 0 且不为负（不会画出倒挂的块）', () => {
    const { minutes } = blockGeometry(meeting('2026-10-15 06:00:00', '2026-10-15 07:00:00'))
    expect(minutes).toBe(0)
  })
})

describe('pastRatio（过去时段灰罩比例，T6.1）', () => {
  const now = dayjs('2026-10-17 14:00:00')

  it('过去的日期 → 整天灰（1）', () => {
    expect(pastRatio('2026-10-16', now)).toBe(1)
  })

  it('未来的日期 → 不灰（0）', () => {
    expect(pastRatio('2026-10-18', now)).toBe(0)
  })

  it('今天 14:00 → 从 08:00 到 14:00 占窗口 6/12 = 0.5', () => {
    expect(pastRatio('2026-10-17', now)).toBe(0.5)
  })

  it('今天 08:00 前 → 0；20:00 后 → 1（不会越界）', () => {
    expect(pastRatio('2026-10-17', dayjs('2026-10-17 07:30:00'))).toBe(0)
    expect(pastRatio('2026-10-17', dayjs('2026-10-17 22:00:00'))).toBe(1)
  })
})
