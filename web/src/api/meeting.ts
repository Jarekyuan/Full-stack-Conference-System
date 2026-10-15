/**
 * src/api/meeting.ts
 * 会议域接口封装，对应后端 MeetingController / AvailabilityController。
 * 真相源：docs/04-接口设计.md（4.3 日历 / 4.4 创建预约 / 3.2 列表与详情）+ 后端 VO 字段名，
 * 接口类型以 04 文档为准手写，字段名与后端严格一致（camelCase），不臆造。
 * 按里程碑逐步补齐：T2.F1 日历 → T2.F2 可用性/创建 → T2.F3 列表/详情/取消。
 */
import { http } from './request'

/** 日历中的会议条目（后端 CalendarMeetingVO）；日历只返回有效日程（10/20/30） */
export interface CalendarMeeting {
  meetingId: number
  title: string
  /** 后端统一时间格式 yyyy-MM-dd HH:mm:ss */
  startTime: string
  endTime: string
  /** 10 待审批 / 20 已预约 / 30 进行中（配色见 docs/04 5.1） */
  status: number
  organiserId: number
  organiserName: string
}

/** 一天的日程（后端 RoomCalendarVO.Day；固定 7 项，无会议的天为空数组） */
export interface CalendarDay {
  /** yyyy-MM-dd */
  date: string
  meetings: CalendarMeeting[]
}

/** 单会议室某周日历（后端 RoomCalendarVO） */
export interface RoomCalendar {
  roomId: number
  /** 归一化后的周一 */
  weekStart: string
  /** 周日 */
  weekEnd: string
  /** 固定 7 项（周一 → 周日） */
  days: CalendarDay[]
}

/** 单会议室某周日历；week 传该周任意一天，后端归一化到周一 */
export function getRoomCalendar(roomId: number, week: string): Promise<RoomCalendar> {
  return http.get<RoomCalendar>(`/rooms/${roomId}/calendar`, { params: { week } })
}
