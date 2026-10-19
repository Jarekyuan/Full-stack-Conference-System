/**
 * 预约表单校验规则的单测（评审 P1-15：前端此前**零测试**）。
 * 重点不是"合法输入能过"，而是把几个真实踩过的坑钉死——尤其 R12：
 * rc-picker 清空时间传的是 `null`，曾漏出 zod 默认英文 "Expected array, received null"。
 */
import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import { bookingSchema } from '@/pages/bookingSchema'

/** 一份合法基线，各用例只覆盖关心的字段 */
const validInput = {
  title: '产品评审会',
  roomId: 3,
  timeRange: [dayjs('2099-01-01 14:00'), dayjs('2099-01-01 15:00')] as const,
  userIds: [] as number[],
}

/** 取第一条错误的 message（表单只展示第一条） */
function firstMessage(input: unknown): string {
  const result = bookingSchema.safeParse(input)
  if (result.success) throw new Error('期望校验失败，实际通过了')
  return result.error.issues[0].message
}

describe('bookingSchema', () => {
  it('合法输入通过', () => {
    expect(bookingSchema.safeParse(validInput).success).toBe(true)
  })

  it('备注可选：不传也通过', () => {
    expect(bookingSchema.safeParse({ ...validInput, remark: undefined }).success).toBe(true)
  })

  // ———— R12 回归组：三个"没选时间"的形态，文案必须都是中文 ————
  it('时间被清空（rc-picker 传 null）→ 中文提示，而不是 zod 默认英文', () => {
    expect(firstMessage({ ...validInput, timeRange: null })).toBe('请选择会议时间')
  })

  it('时间从未选过（undefined）→ 中文提示', () => {
    expect(firstMessage({ ...validInput, timeRange: undefined })).toBe('请选择会议时间')
  })

  it('数组里是 null（[null, null]）→ 中文提示', () => {
    // 元素级 custom 必须真校验：早期写法 z.custom<Dayjs>() 不带校验函数等于恒真，
    // 会让这个输入一路走到 refine，然后在 .isBefore() 上抛 TypeError
    expect(firstMessage({ ...validInput, timeRange: [null, null] })).toBe('请选择会议时间')
  })

  it('结束时间早于开始时间 → 报在 timeRange 上', () => {
    const result = bookingSchema.safeParse({
      ...validInput,
      timeRange: [dayjs('2099-01-01 15:00'), dayjs('2099-01-01 14:00')],
    })
    expect(result.success).toBe(false)
    if (result.success) return
    expect(result.error.issues[0].path).toEqual(['timeRange'])
    expect(result.error.issues[0].message).toBe('结束时间必须晚于开始时间')
  })

  it('首尾相同（零时长）也不合法', () => {
    const t = dayjs('2099-01-01 14:00')
    expect(firstMessage({ ...validInput, timeRange: [t, t] })).toBe('结束时间必须晚于开始时间')
  })

  it('过去的时间 → 不能预约过去的时间（T6.1）', () => {
    const past = dayjs().subtract(1, 'day').startOf('hour')
    expect(
      firstMessage({ ...validInput, timeRange: [past, past.add(1, 'hour')] }),
    ).toBe('不能预约过去的时间')
  })

  it('08:00 前 / 20:00 后 / 跨天都不合法（O23）', () => {
    expect(
      firstMessage({
        ...validInput,
        timeRange: [dayjs('2099-01-01 07:30'), dayjs('2099-01-01 08:30')],
      }),
    ).toBe('开始时间需在 08:00–20:00 之间')
    expect(
      firstMessage({
        ...validInput,
        timeRange: [dayjs('2099-01-01 21:00'), dayjs('2099-01-01 22:00')],
      }),
    ).toBe('开始时间需在 08:00–20:00 之间')
    expect(
      firstMessage({
        ...validInput,
        timeRange: [dayjs('2099-01-01 19:00'), dayjs('2099-01-01 20:30')],
      }),
    ).toBe('结束时间不能晚于 20:00')
    expect(
      firstMessage({
        ...validInput,
        timeRange: [dayjs('2099-01-01 23:00'), dayjs('2099-01-02 01:00')],
      }),
    ).toContain('跨天')
  })

  it('主题：空白字符串与超长都拦下', () => {
    expect(firstMessage({ ...validInput, title: '   ' })).toBe('请填写会议主题')
    expect(firstMessage({ ...validInput, title: 'x'.repeat(101) })).toBe('主题最长 100 字符')
  })

  it('未选会议室 → 中文提示', () => {
    expect(firstMessage({ ...validInput, roomId: undefined })).toBe('请选择会议室')
  })

  it('备注超 500 字 → 拦下', () => {
    expect(firstMessage({ ...validInput, remark: 'x'.repeat(501) })).toBe('备注最长 500 字符')
  })
})
