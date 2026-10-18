/**
 * 纪要空态：按"权限 → 已取消/已拒绝 → 未结束 → 可上传"的顺序给出提示。
 * 只有已结束（40）且有管理权限时才渲染上传器；其余情况一律不暴露上传入口。
 */
import { Empty } from 'antd'
import type { MeetingDetail } from '@/api/meeting'
import { minutesEmptyStateKind } from './minutesUi'
import type { MinutesEmptyStateKind } from './minutesUi'
import MinutesUploader from './MinutesUploader'

type EmptyKind = Exclude<MinutesEmptyStateKind, 'uploader'>

const EMPTY_TEXT: Record<EmptyKind, string> = {
  'no-permission': '暂无会议纪要',
  cancelled: '会议已取消，无法上传会议纪要',
  rejected: '会议已拒绝，无法上传会议纪要',
  'not-ended': '会议结束后可上传会议纪要',
}

interface Props {
  meeting: MeetingDetail
  canManage: boolean
  onImported: () => void
}

export default function MinutesEmptyState({ meeting, canManage, onImported }: Props) {
  const kind = minutesEmptyStateKind(meeting.status, canManage)
  if (kind === 'uploader') {
    return <MinutesUploader meetingId={meeting.id} onImported={onImported} />
  }
  return <Empty description={EMPTY_TEXT[kind]} />
}
