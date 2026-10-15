/**
 * src/api/approval.ts
 * 审批域接口封装，对应后端 ApprovalController（网关路由 /api/approvals/**）。
 * 真相源：docs/04-接口设计.md 3.2 + 4.7/4.8 + 后端 VO 字段，字段名严格一致，不臆造。
 *
 * 权限：待办/已办/通过/拒绝都要求 APPROVER（后端 @PreAuthorize）——
 * 非审批人拿到的是框架级 403，不是业务码。
 */
import { http } from './request'
import type { PageResult } from './types'

/** 待审批条目（后端 ApprovalVO；含 remark，审批人靠它做判断） */
export interface ApprovalPendingItem {
  meetingId: number
  title: string
  roomId: number
  roomName: string
  organiserId: number
  organiserName: string
  startTime: string
  endTime: string
  /** 用途/事由（申请人填的备注） */
  remark: string | null
  createdAt: string
}

/** 已处理条目（后端 ApprovalHistoryVO；meetingStatus 是会议**当前**状态，不是审批那一刻的） */
export interface ApprovalHistoryItem {
  approvalId: number
  meetingId: number
  title: string
  roomName: string
  organiserName: string
  startTime: string
  endTime: string
  /** PASS / REJECT */
  action: string
  comment: string | null
  approverId: number
  approverName: string | null
  /** 会议当前状态（10/20/30/40/50/60） */
  meetingStatus: number
  handledAt: string
}

/** 审批动作结果（后端 ApprovalResultVO） */
export interface ApprovalResult {
  meetingId: number
  /** 通过 → 20 已预约；拒绝 → 60 已拒绝 */
  status: number
}

/** 待审批列表（按会议开始时间升序：最早要开的先批） */
export function listPending(page: number, size: number): Promise<PageResult<ApprovalPendingItem>> {
  return http.get<PageResult<ApprovalPendingItem>>('/approvals/pending', { params: { page, size } })
}

/** 已处理记录（只含当前审批人处理过的，按处理时间倒序） */
export function listHistory(page: number, size: number): Promise<PageResult<ApprovalHistoryItem>> {
  return http.get<PageResult<ApprovalHistoryItem>>('/approvals/history', { params: { page, size } })
}

/** 审批通过（含复检）：待审批期间时段被抢 → 50001，data.conflicts 带冲突详情 */
export function passApproval(meetingId: number, comment?: string): Promise<ApprovalResult> {
  return http.post<ApprovalResult>(`/approvals/${meetingId}/pass`, { comment })
}

/** 审批拒绝：comment 必填（后端校验 10001） */
export function rejectApproval(meetingId: number, comment: string): Promise<ApprovalResult> {
  return http.post<ApprovalResult>(`/approvals/${meetingId}/reject`, { comment })
}
