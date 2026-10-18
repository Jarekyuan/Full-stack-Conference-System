/**
 * 日期时间选择器的“今天不可选过去时刻”规则（T6.1 评审 C3）。
 *
 * 从 BookingDrawer / MeetingEditDrawer 抽出来，原因：
 * ① 两个 Drawer 必须用同一份规则，否则一处改了另一处漏；
 * ② 这段逻辑踩过一个很隐蔽的坑：**不能返回 disabledSeconds**。
 *    rc-picker 的 useInvalidate 会校验选中时刻的秒（即使 showTime.format 只到分钟、秒列不展示）；
 *    返回 `[0..59]` 会让“今天”的任何值都变成 invalid，triggerSubmit 校验失败后不调用 onChange——
 *    界面可能短暂显示新值，点“确定”后又回退，用户以为改成功了，实际表单还是旧时间。
 *
 * 因此这里只禁到分钟，并且**把当前分钟也禁掉**：`10:30:45` 时选 `10:30` 会生成一个已经过去的
 * 开始时间（后端 30003），禁掉当前分钟比让用户提交后再报错更直接。
 */
import dayjs, { type Dayjs } from 'dayjs'

export interface DisabledTimeRule {
  disabledHours?: () => number[]
  disabledMinutes?: (hour: number) => number[]
}

/**
 * 返回 antd `disabledTime` 所需的规则。
 *
 * @param current 选择器当前渲染的日期（antd 传入）
 * @param now     可注入的“现在”（单测用；生产不传）
 */
export function disabledTimeForToday(
  current: Dayjs | null | undefined,
  now: Dayjs = dayjs(),
): DisabledTimeRule {
  if (!current || !current.isSame(now, 'day')) {
    return {}
  }
  const hour = now.hour()
  const minute = now.minute()
  return {
    disabledHours: () => Array.from({ length: hour }, (_, i) => i),
    // 当前小时：禁用 0..minute（含当前分钟）；其他小时不禁用
    disabledMinutes: (h) => (h === hour ? Array.from({ length: minute + 1 }, (_, i) => i) : []),
  }
}
