/**
 * src/components/calendarAppearance.ts
 * 日历块的四色口径（T6.1，docs/04 5.1）：**按“和我什么关系 + 状态”分类**，而不是纯状态。
 *
 * 优先级（从高到低）：
 *   已过期 → 其他（灰） → 待审批（橙） → 我预订（蓝） → 邀请我的（绿） → 其他（灰）
 *
 * 为什么“已过期”优先：时间已过的会不管是别人订的、还是仍停在待审批，对用户都不再可操作；
 * 若让“待审批”优先，日历里会长期挂着一堆过去的橙色，反而掩盖真正待处理的会议。
 *
 * 这里是纯函数 + 元数据，单独成模块是为了让分类规则可单测（页面只负责按结果渲染）。
 */
import dayjs, { type Dayjs } from 'dayjs'
import type { CalendarMeeting } from '@/api/meeting'

export type CalendarCategory = 'pending' | 'mine' | 'invited' | 'other'

export interface CalendarCategoryResult {
  category: CalendarCategory
  /** 结束时间已过**或已提前结束(status=40)**；其他类里再降透明度区分 */
  expired: boolean
}

/** 四色元数据：图例与会议块共用一份，避免两处各写一套颜色 */
export const CALENDAR_CATEGORY_META: Record<
  CalendarCategory,
  { label: string; blockClass: string; dotClass: string }
> = {
  pending: {
    label: '待审批',
    blockClass: 'bg-amber-100 border-amber-400 text-amber-900 dark:bg-amber-950/60 dark:border-amber-500 dark:text-amber-200',
    dotClass: 'bg-amber-400',
  },
  mine: {
    label: '我预订',
    blockClass: 'bg-blue-100 border-blue-500 text-blue-900 dark:bg-blue-950/60 dark:border-blue-500 dark:text-blue-200',
    dotClass: 'bg-blue-500',
  },
  invited: {
    label: '邀请我的',
    blockClass: 'bg-emerald-100 border-emerald-500 text-emerald-900 dark:bg-emerald-950/60 dark:border-emerald-500 dark:text-emerald-200',
    dotClass: 'bg-emerald-500',
  },
  other: {
    label: '其他（含已过期）',
    blockClass: 'bg-gray-100 border-gray-300 text-gray-600 dark:bg-slate-800 dark:border-slate-600 dark:text-slate-300',
    dotClass: 'bg-gray-400',
  },
}

/**
 * 判定一场会议在日历上的类别。
 * @param currentUserId 当前用户 id；undefined 时“我预订/邀请我的”都退化为其他
 * @param now 可注入的当前时刻（单测用；生产不传）
 */
export function calendarCategory(
  meeting: CalendarMeeting,
  currentUserId: number | undefined,
  now: Dayjs = dayjs(),
): CalendarCategoryResult {
  // 结束时间 <= now 视为过期；status=40 也直接按过期处理——
  // “提前结束”只改状态、不改 end_time，只看 endTime 会让这场会继续显示成我预订/邀请我的（评审 R9）
  const expired = meeting.status === 40 || !dayjs(meeting.endTime).isAfter(now)
  if (expired) {
    return { category: 'other', expired: true }
  }
  if (meeting.status === 10) {
    return { category: 'pending', expired: false }
  }
  if (currentUserId != null && meeting.organiserId === currentUserId) {
    return { category: 'mine', expired: false }
  }
  if (meeting.invited) {
    return { category: 'invited', expired: false }
  }
  return { category: 'other', expired: false }
}
