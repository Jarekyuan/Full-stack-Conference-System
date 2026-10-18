/**
 * 日期时间选择器的可预约窗口规则（O23）。
 *
 * 业务口径与日历时间轴/统计一致：**08:00–20:00、不可跨天**。
 * 这里同时解决 T6.1 评审 C3 的坑：
 * ① **不能返回 disabledSeconds**。rc-picker 的 useInvalidate 会校验选中时刻的秒（即使
 *    showTime.format 只到分钟、秒列不展示）；返回 `[0..59]` 会让“今天”的任何值都变成 invalid，
 *    triggerSubmit 校验失败后不调用 onChange——界面可能短暂显示新值，点“确定”后又回退。
 * ② 当前分钟也禁掉：`10:30:45` 时选 `10:30` 会生成一个已经过去的开始时间（后端 30003）。
 */
import dayjs, { type Dayjs } from 'dayjs'

export const BUSINESS_START_HOUR = 8
export const BUSINESS_END_HOUR = 20

export interface DisabledTimeRule {
  disabledHours?: () => number[]
  disabledMinutes?: (hour: number) => number[]
}

/**
 * 返回 antd `disabledTime` 所需的规则。
 *
 * - 所有日期：禁 00:00–07:59、21:00–23:59；
 * - 20:00 只允许整点（作为结束时间）；
 * - 今天额外禁已过去的整点/分钟（当前分钟也禁）。
 *
 * @param current 选择器当前渲染的日期（antd 传入）
 * @param now     可注入的“现在”（单测用；生产不传）
 */
export function disabledTimeForBooking(
  current: Dayjs | null | undefined,
  now: Dayjs = dayjs(),
): DisabledTimeRule {
  if (!current) {
    return {}
  }
  const isToday = current.isSame(now, 'day')
  const disabledHoursSet = new Set<number>()
  for (let h = 0; h < BUSINESS_START_HOUR; h++) disabledHoursSet.add(h)
  for (let h = BUSINESS_END_HOUR + 1; h < 24; h++) disabledHoursSet.add(h)
  if (isToday) {
    for (let h = 0; h < now.hour(); h++) disabledHoursSet.add(h)
  }

  return {
    disabledHours: () => [...disabledHoursSet].sort((a, b) => a - b),
    disabledMinutes: (hour) => {
      const minutes = new Set<number>()
      // 20:00 作为结束时间是合法的，20:01–20:59 不允许
      if (hour === BUSINESS_END_HOUR) {
        for (let m = 1; m < 60; m++) minutes.add(m)
      }
      // 今天：当前小时禁 0..当前分钟（含当前分钟）
      if (isToday && hour === now.hour()) {
        for (let m = 0; m <= now.minute(); m++) minutes.add(m)
      }
      return [...minutes].sort((a, b) => a - b)
    },
  }
}

/**
 * 校验业务窗口（纯函数，供 zod 与单测复用）。
 * 返回中文错误；合法返回 null。
 */
export function businessHoursViolation(start: Dayjs, end: Dayjs): string | null {
  if (!start.isSame(end, 'day')) {
    return '会议不能跨天（结束时间须在当日 20:00 前）'
  }
  if (start.hour() < BUSINESS_START_HOUR || start.hour() >= BUSINESS_END_HOUR) {
    return '开始时间需在 08:00–20:00 之间'
  }
  if (end.hour() > BUSINESS_END_HOUR || (end.hour() === BUSINESS_END_HOUR && end.minute() > 0)) {
    return '结束时间不能晚于 20:00'
  }
  return null
}
