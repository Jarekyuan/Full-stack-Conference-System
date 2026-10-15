/**
 * src/components/calendarGeometry.ts
 * 日历时间轴的几何换算与点击吸附（**纯函数，不依赖 React/DOM**）。
 *
 * 为什么单独成模块：这块"像素 ↔ 时刻"的换算是日历页最容易算错的地方
 * （吸附粒度、跨天会议、点/拖到窗口外、末端不超过 20:00），
 * 埋在组件的 JSX 里就只能靠手点验证。抽出来才能用单测钉住（评审 P1-15）。
 * DOM 侧的 `getBoundingClientRect()` 由调用方算成比例后传进来，本模块不碰 window。
 */
import dayjs from 'dayjs'
import type { CalendarMeeting } from '@/api/meeting'

/** 时间轴窗口（业务时间）：超出窗口的会议块裁剪到边界 */
export const DAY_START_MINUTES = 8 * 60
export const DAY_END_MINUTES = 20 * 60
export const TOTAL_MINUTES = DAY_END_MINUTES - DAY_START_MINUTES

/** 点击吸附粒度 / 默认会议时长 */
export const SNAP_MINUTES = 30
export const DEFAULT_DURATION_MINUTES = 60

/** 距 00:00 的分钟数；跨天会议（结束在次日）这里只取时刻，天数由调用方判断 */
export function toMinutes(time: string): number {
  const t = dayjs(time)
  return t.hour() * 60 + t.minute()
}

/** 把分钟数裁进时间轴窗口 [08:00, 20:00] */
export function clampMinutes(minutes: number): number {
  return Math.min(Math.max(minutes, DAY_START_MINUTES), DAY_END_MINUTES)
}

/** 起点能取到的最大值：留够一个吸附粒度，保证 start < end（否则点最底部会给出零时长时段） */
const LATEST_START_MINUTES = DAY_END_MINUTES - SNAP_MINUTES

/**
 * 点击格子时带出的预填时段。
 * @param ratio 点击位置在该格内的纵向比例（0 = 顶 = 08:00，1 = 底 = 20:00），由调用方用
 *              `(clientY - rect.top) / rect.height` 算出——允许 <0 或 >1（点在格子外沿），由本函数裁进窗口
 */
export function slotFromClickRatio(ratio: number): { startMinutes: number; endMinutes: number } {
  const raw = DAY_START_MINUTES + ratio * TOTAL_MINUTES
  // 上限收到 19:30：原来点最底部会得 start=end=20:00，预填进表单立刻撞 zod 的
  // "结束时间必须晚于开始时间"——用户什么都没做就被报错（写单测时发现，评审 P1-15）
  const start = Math.min(clampMinutes(Math.round(raw / SNAP_MINUTES) * SNAP_MINUTES), LATEST_START_MINUTES)
  // 末端不越界：19:30 点下去给到 20:00（半小时），而不是 20:30
  return { startMinutes: start, endMinutes: Math.min(start + DEFAULT_DURATION_MINUTES, DAY_END_MINUTES) }
}

/** 会议块的几何：top/height 百分比（裁剪到时间轴窗口内）+ 时长分钟数（决定文字排几行） */
export function blockGeometry(meeting: CalendarMeeting): {
  style: { top: string; height: string }
  minutes: number
} {
  const start = clampMinutes(toMinutes(meeting.startTime))
  // 跨天会议（结束日期 ≠ 开始日期）按窗口末端处理，否则 end < start 会算出负高度
  const end = dayjs(meeting.endTime).isSame(dayjs(meeting.startTime), 'day')
    ? clampMinutes(toMinutes(meeting.endTime))
    : DAY_END_MINUTES
  const minutes = Math.max(end - start, 0)
  return {
    style: {
      top: `${((start - DAY_START_MINUTES) / TOTAL_MINUTES) * 100}%`,
      height: `${(minutes / TOTAL_MINUTES) * 100}%`,
    },
    minutes,
  }
}
