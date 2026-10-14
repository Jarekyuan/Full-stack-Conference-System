/**
 * src/api/room.ts
 * 会议室域接口封装，与后端 RoomController / RoomGroupController 一一对应。
 * 真相源：docs/04-接口设计.md 3.2 + 后端 RoomVO/RoomSaveDTO/StrategyDTO/RoomGroupVO。
 * 字段名与后端 VO 严格一致（camelCase），不臆造。
 */
import { http } from './request'

/** 会议室分组（后端 RoomGroupVO record） */
export interface RoomGroup {
  id: number
  name: string
  sortOrder: number
}

/** 会议室（后端 RoomVO，列表与详情共用，含策略字段） */
export interface Room {
  id: number
  name: string
  groupId: number
  groupName: string
  capacity: number
  location: string
  images: string[]
  deviceTags: string[]
  /** 0 可用 / 1 维护中 */
  status: number
  needApproval: number
  advanceDays: number
  maxDurationMinutes: number
  cancelDeadlineMinutes: number
  bufferMinutes: number
  allowRecurring: number
}

/** 会议室新增/修改入参（后端 RoomSaveDTO） */
export interface RoomSaveInput {
  name: string
  groupId: number
  capacity: number
  location?: string
  images?: string[]
  deviceTags?: string[]
  status?: number
}

/** 预约策略入参（后端 StrategyDTO，全量更新） */
export interface StrategyInput {
  needApproval: number
  advanceDays: number
  maxDurationMinutes: number
  cancelDeadlineMinutes: number
  bufferMinutes: number
  allowRecurring: number
}

/** 列表筛选参数（groupId 精确 / capacity 下限 / status 精确） */
export interface RoomQuery {
  groupId?: number
  capacity?: number
  status?: number
}

/** 分组列表 */
export function listRoomGroups(): Promise<RoomGroup[]> {
  return http.get<RoomGroup[]>('/room-groups')
}

/** 会议室列表（后端返回 List<RoomVO>，不分页） */
export function listRooms(params?: RoomQuery): Promise<Room[]> {
  return http.get<Room[]>('/rooms', { params })
}

/** 会议室详情（含策略） */
export function getRoom(id: number): Promise<Room> {
  return http.get<Room>(`/rooms/${id}`)
}

/** 新增会议室，返回新 id */
export function createRoom(input: RoomSaveInput): Promise<number> {
  return http.post<number>('/rooms', input)
}

/** 编辑会议室 */
export function updateRoom(id: number, input: RoomSaveInput): Promise<void> {
  return http.put<void>(`/rooms/${id}`, input)
}

/** 删除会议室（存在未结束会议时后端返回 30006） */
export function deleteRoom(id: number): Promise<void> {
  return http.delete<void>(`/rooms/${id}`)
}

/** 配置预约策略（全量更新） */
export function updateStrategy(id: number, input: StrategyInput): Promise<void> {
  return http.put<void>(`/rooms/${id}/strategy`, input)
}
