/**
 * 音频 + 转写工作台（Task 13 临时壳）。
 * Task 14 会替换为播放器 / 转写列表 / 发言人重命名 UI；当前只保证面板编排可编译、可联调，
 * props 先按最终形态声明，避免 Task 14 再改调用方。
 */
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
