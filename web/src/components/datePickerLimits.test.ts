/**
 * datePickerLimits 的单测（T6.1 评审 C3）。
 * 重点钉住：① 不返回 disabledSeconds（否则今天整天的值都会被 rc-picker 判为 invalid）；
 * ② 当前分钟也被禁用；③ 非今天不做任何限制。
 */
import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import { disabledTimeForToday } from './datePickerLimits'

const NOW = dayjs('2026-10-17 10:30:45')

describe('disabledTimeForToday', () => {
  it('非今天：不返回任何限制', () => {
    const rule = disabledTimeForToday(dayjs('2026-10-18 10:30:00'), NOW)
    expect(rule).toEqual({})
  })

  it('空日期：不返回任何限制', () => {
    expect(disabledTimeForToday(null, NOW)).toEqual({})
    expect(disabledTimeForToday(undefined, NOW)).toEqual({})
  })

  it('今天：不返回 disabledSeconds（C3 的根因）', () => {
    const rule = disabledTimeForToday(dayjs('2026-10-17 22:00:00'), NOW)
    expect('disabledSeconds' in rule).toBe(false)
  })

  it('今天：禁掉当前小时之前的整点，当前小时及之后不禁', () => {
    const rule = disabledTimeForToday(dayjs('2026-10-17 22:00:00'), NOW)
    expect(rule.disabledHours?.()).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
  })

  it('今天当前小时：禁用 0..当前分钟（含当前分钟）', () => {
    const rule = disabledTimeForToday(dayjs('2026-10-17 10:45:00'), NOW)
    expect(rule.disabledMinutes?.(10)).toEqual(
      Array.from({ length: 31 }, (_, i) => i),
    )
    expect(rule.disabledMinutes?.(11)).toEqual([])
  })

  it('今天 00:05：没有更早的整点，分钟只禁 0..5', () => {
    const rule = disabledTimeForToday(dayjs('2026-10-17 00:10:00'), dayjs('2026-10-17 00:05:10'))
    expect(rule.disabledHours?.()).toEqual([])
    expect(rule.disabledMinutes?.(0)).toEqual([0, 1, 2, 3, 4, 5])
  })
})
