import { Empty } from 'antd'
import type { MeetingDetail } from '@/api/meeting'
import MinutesUploader from './MinutesUploader'

interface Props {
  meeting: MeetingDetail
  canManage: boolean
  onImported: () => void
}

export default function MinutesEmptyState({ meeting, canManage, onImported }: Props) {
  if (meeting.status !== 40) {
    return <Empty description="会议结束后可上传会议纪要" />
  }
  if (!canManage) {
    return <Empty description="暂无会议纪要" />
  }
  return <MinutesUploader meetingId={meeting.id} onImported={onImported} />
}
