/**
 * 状态映射的单测（评审 P1-15 / R15）。
 * R15 的教训是"状态→文案/配色曾有**三处副本**，改了列表忘了日历"——
 * 所以这里除了逐个状态断言，还专门守住"元数据是唯一来源"这件事：六态齐全、字段都不空、未知态有兜底。
 */
import { describe, expect, it } from 'vitest'
import { MEETING_STATUS_META, meetingEventText, meetingStatusText, statusBlockClass } from '@/components/MeetingStatusTag'

/** 后端 MeetingStatusEnum 的全部取值（docs/04 5.1） */
const ALL_STATUSES = [10, 20, 30, 40, 50, 60]

describe('MEETING_STATUS_META（唯一映射处）', () => {
  it('6 个状态一个不少，且每个都有文案/标签色/块样式', () => {
    expect(Object.keys(MEETING_STATUS_META).map(Number).sort((a, b) => a - b)).toEqual(ALL_STATUSES)
    ALL_STATUSES.forEach((status) => {
      const meta = MEETING_STATUS_META[status]
      expect(meta.text).toBeTruthy()
      expect(meta.color).toBeTruthy()
      expect(meta.blockClass).toBeTruthy()
    })
  })

  it('文案与 docs/04 5.1 一致', () => {
    expect(MEETING_STATUS_META[10].text).toBe('待审批')
    expect(MEETING_STATUS_META[20].text).toBe('已预约')
    expect(MEETING_STATUS_META[30].text).toBe('进行中')
    expect(MEETING_STATUS_META[40].text).toBe('已结束')
    expect(MEETING_STATUS_META[50].text).toBe('已取消')
    expect(MEETING_STATUS_META[60].text).toBe('已拒绝')
  })
})

describe('未知状态兜底', () => {
  it('statusBlockClass 给中性灰（不返回 undefined 让 class 变成 "undefined"）', () => {
    expect(statusBlockClass(999)).toContain('bg-gray')
  })

  it('meetingStatusText：未知码原样显示数字，null 显示（无）', () => {
    expect(meetingStatusText(999)).toBe('999')
    expect(meetingStatusText(null)).toBe('（无）')
  })
})

describe('meetingEventText（时间线事件文案）', () => {
  it('已知事件给中文', () => {
    expect(meetingEventText('RESERVE')).toBe('创建预约')
    expect(meetingEventText('CANCEL')).toBe('取消会议')
  })

  it('未知事件原样透出，不显示成 undefined', () => {
    expect(meetingEventText('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})
