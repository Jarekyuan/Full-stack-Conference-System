/**
 * src/api/stats.ts
 * 统计域接口封装（T5.3/T5.4），对应后端 StatsController（网关路由 /api/stats/**）。
 * 真相源：docs/04-接口设计.md 4.10（**口径都在那里**：计入状态、区间裁剪、可用分钟、占用率的量纲）。
 */
import { http } from './request'

/** 会议室使用率条目（后端 RoomUsageVO.Item）；usageRate 是 **0~1 的小数**（不是百分数） */
export interface RoomUsageItem {
  roomId: number
  roomName: string
  meetingCount: number
  totalMinutes: number
  usageRate: number
}

/** H-1 会议室使用率（start/end 缺省 = 后端按近 30 天算） */
export interface RoomUsage {
  start: string
  end: string
  availableMinutes: number
  totalMinutes: number
  items: RoomUsageItem[]
}

/** H-2 个人统计：daily 只含**有会议的**日期（补零是展示层的事） */
export interface MyMeetingStats {
  days: number
  totalMeetings: number
  totalMinutes: number
  daily: { date: string; count: number; minutes: number }[]
}

/** 会议室使用率（仅 ADMIN；非管理员会拿到框架级 403） */
export function fetchRoomUsage(start?: string, end?: string): Promise<RoomUsage> {
  return http.get<RoomUsage>('/stats/room-usage', { params: { start, end } })
}

/** 个人会议统计（登录即可，近 30 天） */
export function fetchMyStats(): Promise<MyMeetingStats> {
  return http.get<MyMeetingStats>('/stats/my-meetings')
}
