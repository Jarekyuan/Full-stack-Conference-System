/**
 * datePickerLimits 的单测（C3 + O23）。
 * 重点钉住：① 不返回 disabledSeconds（否则今天整天的值都会被 rc-picker 判为 invalid）；
 * ② 可预约窗口 08:00–20:00、不可跨天；③ 当前分钟也被禁用。
 */
import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import { BUSINESS_END_HOUR, BUSINESS_START_HOUR, businessHoursViolation, disabledTimeForBooking } from './datePickerLimits'

const NOW = dayjs('2026-10-17 10:30:45')

describe('disabledTimeForBooking', () => {
  it('空日期：不返回任何限制', () => {
    expect(disabledTimeForBooking(null, NOW)).toEqual({})
    expect(disabledTimeForBooking(undefined, NOW)).toEqual({})
  })

  it('未来日期：只开放 08:00–20:00，不返回 disabledSeconds', () => {
    const rule = disabledTimeForBooking(dayjs('2026-10-18 10:00:00'), NOW)
    expect('disabledSeconds' in rule).toBe(false)
    expect(rule.disabledHours?.()).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 21, 22, 23])
    expect(rule.disabledMinutes?.(20)).toEqual(Array.from({ length: 59 }, (_, i) => i + 1))
    expect(rule.disabledMinutes?.(10)).toEqual([])
  })

  it('今天：在业务窗口外再叠加“过去时刻”，当前分钟也禁', () => {
    const rule = disabledTimeForBooking(dayjs('2026-10-17 19:00:00'), NOW)
    expect(rule.disabledHours?.()).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 21, 22, 23])
    expect(rule.disabledMinutes?.(10)).toEqual(Array.from({ length: 31 }, (_, i) => i))
    expect(rule.disabledMinutes?.(11)).toEqual([])
    // 20:00 仍可作为结束时间
    expect(rule.disabledMinutes?.(20)).toEqual(Array.from({ length: 59 }, (_, i) => i + 1))
  })

  it('业务窗口常量与日历/统计一致', () => {
    expect(BUSINESS_START_HOUR).toBe(8)
    expect(BUSINESS_END_HOUR).toBe(20)
  })
})

describe('businessHoursViolation', () => {
  const day = (time: string) => dayjs(`2026-10-18 ${time}`)

  it('08:00–20:00 内合法', () => {
    expect(businessHoursViolation(day('09:00'), day('10:00'))).toBeNull()
    expect(businessHoursViolation(day('19:00'), day('20:00'))).toBeNull()
  })

  it('开始早于 08:00 / 晚于 20:00 被拒', () => {
    expect(businessHoursViolation(day('07:30'), day('08:30'))).toContain('开始时间')
    expect(businessHoursViolation(day('20:00'), day('21:00'))).toContain('开始时间')
  })

  it('结束晚于 20:00 被拒', () => {
    expect(businessHoursViolation(day('19:00'), day('20:30'))).toContain('结束时间')
    expect(businessHoursViolation(day('19:00'), day('21:00'))).toContain('结束时间')
  })

  it('跨天被拒（即使结束时刻本身在窗口内）', () => {
    expect(businessHoursViolation(day('23:00'), dayjs('2026-10-19 01:00'))).toContain('跨天')
  })
})
