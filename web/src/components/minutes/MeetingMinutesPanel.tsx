/**
 * 会议纪要 Tab 编排：加载 / 空态 / 上传 / 工作台 + AI 状态轮询。
 */
import { useEffect } from 'react'
import { Alert, Button, Card, Spin } from 'antd'
import type { MeetingDetail } from '@/api/meeting'
import { getMinutes } from '@/api/minutes'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'
import AudioTranscriptWorkspace from './AudioTranscriptWorkspace'
import MinutesEmptyState from './MinutesEmptyState'

interface Props {
  meeting: MeetingDetail
}

export default function MeetingMinutesPanel({ meeting }: Props) {
  const currentUser = useUserStore((state) => state.user)
  const isAdmin = (currentUser?.roles ?? []).includes('ADMIN')
  const { data, loading, error, refresh } = useAppRequest(
    () => getMinutes(meeting.id),
    { manual: false, refreshDeps: [meeting.id] },
  )

  const aiStatus = data?.aiStatus
  useEffect(() => {
    if (aiStatus !== 10 && aiStatus !== 20) return
    const timer = window.setInterval(() => refresh(), 3000)
    return () => window.clearInterval(timer)
  }, [aiStatus, refresh])

  const canManage =
    data?.permissions.canManage ?? (meeting.organiserId === currentUser?.id || isAdmin)

  if (loading && !data) {
    return (
      <Card>
        <Spin />
      </Card>
    )
  }
  if (error && !data) {
    return (
      <Alert
        type="error"
        showIcon
        message="会议纪要加载失败"
        action={<Button size="small" onClick={() => refresh()}>重试</Button>}
      />
    )
  }
  if (!data) {
    return (
      <MinutesEmptyState
        meeting={meeting}
        canManage={canManage}
        onImported={() => refresh()}
      />
    )
  }
  return (
    <AudioTranscriptWorkspace
      meeting={meeting}
      minutes={data}
      canManage={canManage}
      onRefresh={() => refresh()}
    />
  )
}
