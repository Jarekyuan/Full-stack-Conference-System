/**
 * src/components/MeetingStatusTag.tsx
 * 会议状态标签（docs/02 3.4 列出的业务组件）：**状态码 → 文案 + 配色的唯一映射处**。
 * 列表、详情、审批中心共用，避免每个页面各写一份，改配色时只改这里。
 * 配色真相源：docs/04 §5.1（10 待审橙 / 20 已预约蓝 / 30 进行中绿 / 40 已结束灰 / 50 已取消浅灰 / 60 已拒绝红）。
 */
import { Tag } from 'antd'

/** 状态元数据（文案与配色均照 docs/04 5.1） */
export const MEETING_STATUS_META: Record<number, { text: string; color: string }> = {
  10: { text: '待审批', color: 'orange' },
  20: { text: '已预约', color: 'blue' },
  30: { text: '进行中', color: 'green' },
  40: { text: '已结束', color: 'default' },
  50: { text: '已取消', color: 'default' },
  60: { text: '已拒绝', color: 'red' },
}

/** 状态文案（时间线等只想要文字的场景用） */
export function meetingStatusText(status: number | null): string {
  return status == null ? '（无）' : (MEETING_STATUS_META[status]?.text ?? String(status))
}

/** 状态变更事件 → 文案（取值照 docs/04 5.4，文案照后端 MeetingEventEnum 的描述，不自编） */
const EVENT_TEXT: Record<string, string> = {
  RESERVE: '创建预约',
  APPROVE: '审批通过',
  REJECT: '审批拒绝',
  CANCEL: '取消会议',
  START: '会议开始',
  END: '会议结束',
  EXTEND: '延长会议',
  MODIFY: '修改会议',
}

/** 事件文案 */
export function meetingEventText(event: string): string {
  return EVENT_TEXT[event] ?? event
}

export default function MeetingStatusTag({ status }: { status: number }) {
  const meta = MEETING_STATUS_META[status]
  // 已取消是"浅灰"（docs/04 5.1），用默认色再压一档透明度区分于"已结束"
  return (
    <Tag color={meta?.color ?? 'default'} className={status === 50 ? 'opacity-60' : undefined}>
      {meta?.text ?? status}
    </Tag>
  )
}
