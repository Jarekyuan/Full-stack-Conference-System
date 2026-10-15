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

// ———————————————————— 可用性（T2.F2 预约表单实时校验）————————————————————
// 注意：只读查询**不加锁**，只是 UI 辅助；最终以提交时的冲突校验为准（docs/04 7.4"查询与提交分离"）。

/** 冲突会议简报（后端 MeetingConflictVO；比日历条目少 status/organiserId，以文档为准不臆造） */
export interface MeetingConflict {
  meetingId: number
  title: string
  startTime: string
  endTime: string
  organiserName: string
}

/** 单会议室可用性结果 */
export interface AvailabilityItem {
  roomId: number
  available: boolean
  conflicts: MeetingConflict[]
}

/** 批量可用性查询入参（后端 AvailabilityQueryDTO；excludeMeetingId 用于"修改会议"排除自身） */
export interface AvailabilityQuery {
  roomIds: number[]
  startTime: string
  endTime: string
  excludeMeetingId?: number | null
}

/** 批量可用性查询：这些会议室在这段时间是否可用 */
export function checkAvailability(query: AvailabilityQuery): Promise<{ items: AvailabilityItem[] }> {
  return http.post<{ items: AvailabilityItem[] }>('/rooms/availability', query)
}

// ———————————————————— 创建预约（T2.F2，最核心）————————————————————

/** 创建预约入参（后端 MeetingCreateDTO；v1.3 起参会人只收 userIds） */
export interface MeetingCreateInput {
  title: string
  roomId: number
  startTime: string
  endTime: string
  remark?: string
  participants?: { userIds: number[] }
}

/** 创建预约结果（后端 MeetingCreateVO） */
export interface MeetingCreateResult {
  meetingId: number
  /** 10 待审批 / 20 已预约（按会议室策略分流） */
  status: number
  needApproval: boolean
  msg: string
}

/**
 * 创建预约。幂等：idempotencyToken 由调用方在"进入表单时"生成一次并复用
 * （重试/手抖重发必须带同一个 token，后端 SETNX 判重）。
 * ⚠️ 重复提交时后端返回 10006，request.ts 会**静默解包成 undefined**（不抛异常）——
 * 调用方拿到 undefined 时说明是重复提交，不能当成功结果用。
 */
export function createMeeting(input: MeetingCreateInput, idempotencyToken: string): Promise<MeetingCreateResult> {
  return http.post<MeetingCreateResult>('/meetings', input, {
    headers: { 'X-Idempotency-Token': idempotencyToken },
  })
}
