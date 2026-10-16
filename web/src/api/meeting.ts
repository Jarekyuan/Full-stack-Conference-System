/**
 * src/api/meeting.ts
 * 会议域接口封装，对应后端 MeetingController / AvailabilityController。
 * 真相源：docs/04-接口设计.md（4.3 日历 / 4.4 创建预约 / 3.2 列表与详情）+ 后端 VO 字段名，
 * 接口类型以 04 文档为准手写，字段名与后端严格一致（camelCase），不臆造。
 * 按里程碑逐步补齐：T2.F1 日历 → T2.F2 可用性/创建 → T2.F3 列表/详情/取消。
 */
import { http } from './request'
import type { PageResult } from './types'

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
 * 返回类型因此显式带上 `| undefined`：调用方必须先判空，不能当成功结果用。
 * （此前类型承诺非空、只有调用点手写注解兜住，下一个调用方直接 `r.meetingId` 就会炸。）
 */
export function createMeeting(
  input: MeetingCreateInput,
  idempotencyToken: string,
): Promise<MeetingCreateResult | undefined> {
  return http.post<MeetingCreateResult>('/meetings', input, {
    headers: { 'X-Idempotency-Token': idempotencyToken },
  })
}

// ———————————————————— 列表与详情（T2.F3）————————————————————

/** 会议列表条目（后端 MeetingVO）：按场景裁剪——含 roomName/organiserName 冗余，不含参会人明细 */
export interface MeetingListItem {
  id: number
  title: string
  roomId: number
  roomName: string
  organiserId: number
  organiserName: string
  startTime: string
  endTime: string
  /** 10 待审批 / 20 已预约 / 30 进行中 / 40 已结束 / 50 已取消 / 60 已拒绝 */
  status: number
  needApproval: number
  participantCount: number
  createdAt: string
}

/** 列表查询参数（docs/04 4.5；status 为逗号分隔多值，page 从 1 起） */
export interface MeetingQuery {
  scope?: 'mine' | 'all'
  status?: string
  roomId?: number
  startDate?: string
  endDate?: string
  keyword?: string
  page?: number
  size?: number
}

/** 参会人（后端 MeetingDetailVO.ParticipantVO，v1.3 起仅系统用户；不含 email，见 docs/04 4.5） */
export interface MeetingParticipant {
  userId: number
  realName: string
}

/** 状态时间线条目（后端 MeetingDetailVO.StatusLogVO）；fromStatus 为 null 表示"创建"这一步 */
export interface MeetingTimelineLog {
  fromStatus: number | null
  toStatus: number
  /** 事件名（docs/04 5.4：RESERVE/APPROVE/REJECT/CANCEL/START/END/EXTEND/MODIFY） */
  event: string
  operatorId: number | null
  operatorName: string | null
  remark: string | null
  createdAt: string
}

/** 会议详情（后端 MeetingDetailVO；04 文档只登记了接口，字段以后端 VO 为准） */
export interface MeetingDetail {
  id: number
  title: string
  roomId: number
  roomName: string
  organiserId: number
  organiserName: string
  startTime: string
  endTime: string
  status: number
  remark: string | null
  cancelReason: string | null
  cancelTime: string | null
  recurringId: number | null
  version: number
  createdAt: string
  participants: MeetingParticipant[]
  /** 状态变更时间线（含系统任务，其 operatorName 可能为 null） */
  logs: MeetingTimelineLog[]
}

/** 我的会议列表（分页；scope=mine 为"我组织的或我参与的"） */
export function listMeetings(query: MeetingQuery): Promise<PageResult<MeetingListItem>> {
  return http.get<PageResult<MeetingListItem>>('/meetings', { params: query })
}

/** 会议详情（含参会人明细 + 状态时间线） */
export function getMeeting(id: number): Promise<MeetingDetail> {
  return http.get<MeetingDetail>(`/meetings/${id}`)
}

/** 取消会议（仅组织人；20 状态还要过 R-6 取消截止，超时后端回 30005） */
export function cancelMeeting(id: number, reason?: string): Promise<void> {
  return http.post<void>(`/meetings/${id}/cancel`, { reason })
}

// ———————————————————— 延长 / 提前结束（T3.F2）————————————————————
// 状态要求：**仅进行中（30）**；权限：**仅组织人**。前端只负责"不给无意义的入口"，
// 真正的裁决在服务端（40002 / 40004 / 40006 / 40001）。

/** 可延长上限（后端 ExtendLimitVO）：R-15 的计算在服务端，reason 是给用户看的人话解释 */
export interface ExtendLimit {
  maxDelayMinutes: number
  reason: string
}

/** 可延长上限；登录即可查（只读） */
export function getExtendLimit(id: number): Promise<ExtendLimit> {
  return http.get<ExtendLimit>(`/meetings/${id}/extend-limit`)
}

/** 延长会议（超上限 40006，data 带 maxDelayMinutes；与下一场冲突 40001） */
export function extendMeeting(id: number, minutes: number): Promise<void> {
  return http.post<void>(`/meetings/${id}/extend`, { minutes })
}

/** 提前结束（仅进行中，30 → 40） */
export function finishMeeting(id: number): Promise<void> {
  return http.post<void>(`/meetings/${id}/finish`)
}

// ———————————————————— 周期会议（T5.1/T5.2，契约 docs/04 4.9）————————————————————
// 复用 createMeeting 的幂等约定：token 由调用方在"进入表单时"生成一次并复用。

/** 创建周期会议的输入（与后端 RecurringCreateDTO 对齐） */
export interface RecurringCreateInput {
  title: string
  roomId: number
  /** 1=周一 … 7=周日（与 java.time.DayOfWeek.getValue() 一致） */
  dayOfWeek: number
  /** 当天分钟数（540=09:00）：与"时刻"解耦，避免时区/夏令时下的漂移 */
  startMinutes: number
  durationMinutes: number
  /** 日期区间（含端点） */
  startDate: string
  endDate: string
}

/** 周期创建的冲突条目（后端 RecurringConflictVO）：比单场冲突多 occurrenceStart（**哪一场**撞了） */
export interface RecurringConflict {
  /** 冲突所属的场次开始时间（展开出来的那一场） */
  occurrenceStart: string
  meetingId: number
  title: string
  startTime: string
  endTime: string
  organiserId: number
  organiserName: string
}

export interface RecurringCreateResult {
  recurringId: number
  created: number
  meetingIds: number[]
}

/**
 * 创建周期会议。**幂等比单场更要紧**：重复提交一次单场顶多多一场会，
 * 重复提交一次系列会一次多出 N 场（而且都要逐个取消）。
 */
export function createRecurring(
  input: RecurringCreateInput,
  idempotencyToken: string,
): Promise<RecurringCreateResult | undefined> {
  return http.post<RecurringCreateResult>('/meetings/recurring', input, {
    headers: { 'X-Idempotency-Token': idempotencyToken },
  })
}

/** 取消整个系列（只取消未开始的场次；系列内有进行中的场次会被拒 → 40002） */
export function cancelRecurring(
  recurringId: number,
  reason?: string,
): Promise<{ cancelled: number; skipped: number }> {
  return http.post<{ cancelled: number; skipped: number }>(
    `/meetings/recurring/${recurringId}/cancel`,
    { reason },
  )
}
