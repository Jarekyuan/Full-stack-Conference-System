import { Card } from 'antd'
import type { MeetingDetail } from '@/api/meeting'
import type { MinutesDetail } from '@/api/minutes'

interface Props {
  meeting: MeetingDetail
  minutes: MinutesDetail
  canManage: boolean
  onRefresh: () => void
}

export default function AudioTranscriptWorkspace(_props: Props) {
  return <Card loading />
}
