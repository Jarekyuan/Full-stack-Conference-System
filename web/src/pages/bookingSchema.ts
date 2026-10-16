/**
 * src/pages/bookingSchema.ts
 * 预约表单的 zod 校验规则（从 BookingDrawer 抽出，2026-10-15 评审 P1-15）。
 *
 * 抽出来的两个理由：
 * ① 规则本身值得单测——尤其是"清空时间范围"这类只在天上掉下来的输入（见 R12 的坑）；
 * ② BookingDrawer.tsx 已超 AGENT.md 的 300 行阈值，校验规则是最好切的一块。
 */
import { z } from 'zod'
import dayjs, { type Dayjs } from 'dayjs'

/** 后端统一时间格式 */
export const API_TIME_FORMAT = 'YYYY-MM-DD HH:mm:ss'

export const bookingSchema = z
  .object({
    title: z.string().trim().min(1, '请填写会议主题').max(100, '主题最长 100 字符'),
    roomId: z.number({ required_error: '请选择会议室' }),
    // ⚠️ rc-picker 的"清空"传上来的是 `null`（不是 `[null, null]`），而 required_error 只覆盖 undefined
    // → 实测会漏出 zod 默认英文报错 "Expected array, received null"（评审 R12）。
    // 故同时给 invalid_type_error，并用**带校验函数**的 custom 保证元素真的是 Dayjs
    // （原写法 `z.custom<Dayjs>()` 不带校验等于恒真，只校验了"数组长度 2"，还让下面的 refine 有抛 TypeError 的风险）
    timeRange: z.tuple(
      [
        z.custom<Dayjs>((v) => dayjs.isDayjs(v), '请选择会议时间'),
        z.custom<Dayjs>((v) => dayjs.isDayjs(v), '请选择会议时间'),
      ],
      { required_error: '请选择会议时间', invalid_type_error: '请选择会议时间' },
    ),
    userIds: z.array(z.number()),
    remark: z.string().max(500, '备注最长 500 字符').optional(),
    // 周期会议（T5.4）：勾选后展开"重复到哪天"；星期几不需要用户选——
    // 它就是本次开始时间落在星期几（选了周三 10:00，系列就是每周三 10:00）
    // .default(false)：**默认单次**。不给默认值的话，所有既有调用点（含单测）都会因为
    // "缺这个字段"而校验失败——加可选功能不该把老路径打断（这正是单测的价值：它先叫了）
    repeatWeekly: z.boolean().default(false),
    repeatEndDate: z.custom<Dayjs>((v) => dayjs.isDayjs(v), '请选择重复结束日期').optional(),
  })
  .refine((v) => v.timeRange[0].isBefore(v.timeRange[1]), {
    path: ['timeRange'],
    message: '结束时间必须晚于开始时间',
  })
  .refine((v) => !v.repeatWeekly || (v.repeatEndDate && dayjs.isDayjs(v.repeatEndDate)), {
    path: ['repeatEndDate'],
    message: '请选择重复结束日期',
  })
  // 客户端只挡"明显不合法"（顺序）；"最多提前 N 天"由服务端按会议室策略判定并给出**第几场**不合适的提示
  .refine((v) => !v.repeatWeekly || !v.repeatEndDate || v.repeatEndDate.isAfter(v.timeRange[0], 'day'), {
    path: ['repeatEndDate'],
    message: '重复结束日期必须晚于开始日期',
  })

export type BookingForm = z.infer<typeof bookingSchema>
