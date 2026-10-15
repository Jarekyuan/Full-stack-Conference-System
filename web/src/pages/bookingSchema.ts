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
  })
  .refine((v) => v.timeRange[0].isBefore(v.timeRange[1]), {
    path: ['timeRange'],
    message: '结束时间必须晚于开始时间',
  })

export type BookingForm = z.infer<typeof bookingSchema>
