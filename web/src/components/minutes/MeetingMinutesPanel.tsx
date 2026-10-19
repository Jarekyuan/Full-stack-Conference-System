/**
 * 会议纪要 Tab 编排：加载 / 空态 / 上传 / 工作台 + AI 状态轮询。
 * - 轮询采用"完成后再排下一次"的 setTimeout，慢请求不会叠加；失败也不终止循环；
 * - 关闭 useAppRequest 的自动 toast，加载失败统一由内联 Alert 呈现（含 10003 无权限文案）；
 * - 用 meetingId 守卫丢弃切换会议期间到达的旧响应。
 */
import { useEffect } from 'react'
import { Alert, Button, Card, Spin } from 'antd'
import type { MeetingDetail } from '@/api/meeting'
import { getMinutes } from '@/api/minutes'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'
import { describeMinutesError } from './minutesUi'
import AudioTranscriptWorkspace from './AudioTranscriptWorkspace'
import MinutesEmptyState from './MinutesEmptyState'

/** AI 生成状态：10 排队中 / 20 生成中（docs/04 §10.2） */
const AI_STATUS_PENDING = 10
const AI_STATUS_PROCESSING = 20
const POLL_INTERVAL_MS = 3000

interface Props {
  meeting: MeetingDetail
}

export default function MeetingMinutesPanel({ meeting }: Props) {
  const currentUser = useUserStore((state) => state.user)
  const isAdmin = (currentUser?.roles ?? []).includes('ADMIN')
  const { data, loading, error, refreshAsync } = useAppRequest(
    () => getMinutes(meeting.id),
    { manual: false, toastError: false, refreshDeps: [meeting.id] },
  )

  // 切换会议后，上一个会议的响应可能晚到：meetingId 不匹配一律视为无数据。
  const minutes = data && data.meetingId === meeting.id ? data : null
  const aiStatus = minutes?.aiStatus
  const asrStatus = minutes?.asrStatus
  const polling =
    asrStatus === AI_STATUS_PENDING ||
    asrStatus === AI_STATUS_PROCESSING ||
    aiStatus === AI_STATUS_PENDING ||
    aiStatus === AI_STATUS_PROCESSING

  useEffect(() => {
    if (!polling) return
    let cancelled = false
    let timer: number | undefined
    const tick = async () => {
      try {
        await refreshAsync()
      } catch {
        // 单次轮询失败不终止循环；错误由下方 Alert 展示
      } finally {
        if (!cancelled) timer = window.setTimeout(() => void tick(), POLL_INTERVAL_MS)
      }
    }
    timer = window.setTimeout(() => void tick(), POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [polling, refreshAsync])

  const reload = () => {
    void refreshAsync().catch(() => {
      // 失败会写入 error 状态，由 Alert 展示
    })
  }

  /** 工作台需要等待刷新真正结束（生成后保持 busy 到新状态返回），失败交给调用方处理 */
  const reloadAsync = () => refreshAsync().then(() => undefined)

  const canManage =
    minutes?.permissions?.canManage ?? (meeting.organiserId === currentUser?.id || isAdmin)
  const errorInfo = error ? describeMinutesError(error, '会议纪要加载失败') : null

  if (loading && !minutes) {
    return (
      <Card>
        <Spin />
      </Card>
    )
  }
  if (errorInfo && !minutes) {
    return (
      <Alert
        type="error"
        showIcon
        message={errorInfo.message}
        action={<Button size="small" onClick={reload}>重试</Button>}
      />
    )
  }
  if (!minutes) {
    return (
      <MinutesEmptyState meeting={meeting} canManage={canManage} onImported={reload} />
    )
  }
  return (
    <>
      {errorInfo && (
        <Alert
          className="mb-3"
          type="warning"
          showIcon
          message="会议纪要刷新失败"
          description={errorInfo.message}
          action={<Button size="small" onClick={reload}>重试</Button>}
        />
      )}
      <AudioTranscriptWorkspace
        meeting={meeting}
        minutes={minutes}
        canManage={canManage}
        onRefresh={reloadAsync}
      />
    </>
  )
}
