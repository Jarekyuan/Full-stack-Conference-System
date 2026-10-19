/**
 * 纪要工作台：播放器 + 转写联动 + AI 纪要 + 会议洞察 + 导出。
 * - 转写与音频各自维护 loading/error/重试；会议切换时作废旧请求，避免旧响应覆盖新会议；
 * - 音频地址过期最多自动重签一次，之后由用户点“重试”；换源后用 loadedmetadata 恢复播放位置；
 * - 生成按钮只保留在 MinutesAiBar 一处；生成后用接口返回的 aiStatus 维持 busy，直到父级刷新出
 *   新的终态（刷新失败时不会在旧状态上误放开按钮）；
 * - seek / 重命名回调保持引用稳定，避免破坏 TranscriptRow 的 memo。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, App, Button, Card, Space, Spin, Tabs, Tag, Typography } from 'antd'
import { Download, RefreshCw } from 'lucide-react'
import type { MeetingDetail } from '@/api/meeting'
import {
  exportMinutes,
  generateMinutes,
  getAudioUrl,
  getTranscript,
  renameSpeaker,
  type MinutesDetail,
  type MinutesSpeaker,
  type TranscriptSegment,
} from '@/api/minutes'
import { ApiError } from '@/api/types'
import { findActiveSegment } from '@/utils/transcriptSync'
import AudioPlayer from './AudioPlayer'
import MinutesAiBar from './MinutesAiBar'
import MinutesInsights from './MinutesInsights'
import MinutesSummary from './MinutesSummary'
import SpeakerRenameModal from './SpeakerRenameModal'
import TranscriptList from './TranscriptList'
import { describeMinutesError, minutesAiStatusMeta } from './minutesUi'

interface Props {
  meeting: MeetingDetail
  minutes: MinutesDetail
  canManage: boolean
  /** 刷新纪要（返回 Promise，生成后需等新状态落地再解除 busy） */
  onRefresh: () => Promise<unknown>
}

export default function AudioTranscriptWorkspace({
  meeting,
  minutes,
  canManage,
  onRefresh,
}: Props) {
  const { message, modal } = App.useApp()
  const audioRef = useRef<HTMLAudioElement>(null)

  // 转写：token 用于丢弃切会议/卸载后才返回的旧响应
  const [segments, setSegments] = useState<TranscriptSegment[]>([])
  const [transcriptLoading, setTranscriptLoading] = useState(true)
  const [transcriptError, setTranscriptError] = useState<unknown>(null)
  const transcriptRequestRef = useRef(0)

  // 音频：url/loading/error + 一次自动重签标记 + 重签后的待恢复播放位置
  const hasAudio = minutes.audio != null
  const [audioUrl, setAudioUrl] = useState<string | null>(null)
  const audioUrlRef = useRef<string | null>(null)
  const [audioLoading, setAudioLoading] = useState(false)
  const [audioError, setAudioError] = useState<string | null>(null)
  const audioResignedRef = useRef(false)
  const audioRequestRef = useRef(0)
  const pendingSeekRef = useRef<number | null>(null)

  const [activeId, setActiveId] = useState<number | null>(null)
  const [renaming, setRenaming] = useState<MinutesSpeaker | null>(null)
  const [generating, setGenerating] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [refreshing, setRefreshing] = useState(false)

  // 生成接口返回的 aiStatus：父级刷新失败时仍能保持“生成中”，避免旧状态误放开按钮
  const [pendingAiStatus, setPendingAiStatus] = useState<number | null>(null)
  const generateBaselineRef = useRef<string | null>(null)
  const sawPendingAiRef = useRef(false)
  const effectiveAiStatus = pendingAiStatus ?? minutes.aiStatus
  const aiMeta = minutesAiStatusMeta(effectiveAiStatus)

  const loadTranscript = useCallback(async () => {
    const token = ++transcriptRequestRef.current
    setTranscriptLoading(true)
    setTranscriptError(null)
    try {
      const result = await getTranscript(meeting.id)
      if (transcriptRequestRef.current === token) setSegments(result.segments)
    } catch (e) {
      if (transcriptRequestRef.current === token) setTranscriptError(e)
    } finally {
      if (transcriptRequestRef.current === token) setTranscriptLoading(false)
    }
  }, [meeting.id])

  // 切换会议/卸载：清空上一场的分段并让在途请求失效
  useEffect(() => {
    setSegments([])
    setActiveId(null)
    void loadTranscript()
    return () => {
      transcriptRequestRef.current += 1
    }
  }, [loadTranscript])

  const loadAudioUrl = useCallback(
    async (resetResign = true) => {
      const token = ++audioRequestRef.current
      if (!hasAudio) {
        audioUrlRef.current = null
        setAudioUrl(null)
        setAudioError(null)
        setAudioLoading(false)
        return
      }
      setAudioLoading(true)
      setAudioError(null)
      try {
        const result = await getAudioUrl(meeting.id)
        if (audioRequestRef.current !== token) return
        // 手动刷新/首次加载重置“已自动重签”标记；自动重签调用时保留，防止错误循环
        if (resetResign) audioResignedRef.current = false
        const same = audioUrlRef.current === result.url
        // 只有处于错误态的媒体元素才需要手动 load()（同 URL 不会自动重载）；
        // 健康播放中的例行刷新不应调用 load()，否则会从 0 重新播放。
        const mediaErrored = audioRef.current?.error != null
        audioUrlRef.current = result.url
        setAudioUrl(result.url)
        if (same && (mediaErrored || !resetResign)) audioRef.current?.load()
      } catch (e) {
        if (audioRequestRef.current !== token) return
        setAudioError(e instanceof ApiError ? e.message : '音频加载失败，请稍后重试')
      } finally {
        if (audioRequestRef.current === token) setAudioLoading(false)
      }
    },
    [hasAudio, meeting.id],
  )

  // 切换会议/音频可用性变化：重置音频状态并重新取 URL；卸载时作废在途请求
  useEffect(() => {
    audioResignedRef.current = false
    pendingSeekRef.current = null
    audioUrlRef.current = null
    setAudioUrl(null)
    setAudioError(null)
    void loadAudioUrl()
    return () => {
      audioRequestRef.current += 1
      pendingSeekRef.current = null
    }
  }, [loadAudioUrl])

  const retryAudio = useCallback(() => {
    void loadAudioUrl()
  }, [loadAudioUrl])

  const handleAudioError = useCallback(() => {
    if (!hasAudio) return
    if (audioResignedRef.current) {
      setAudioError('音频地址已过期，请点击重试')
      return
    }
    // 每个源只自动重签一次；再失败就停下来等用户操作，避免无限请求 audio-url
    audioResignedRef.current = true
    pendingSeekRef.current = audioRef.current?.currentTime ?? 0
    void loadAudioUrl(false)
  }, [hasAudio, loadAudioUrl])

  const handleAudioLoadedMetadata = useCallback((audio: HTMLAudioElement) => {
    const pending = pendingSeekRef.current
    if (pending == null) return
    pendingSeekRef.current = null
    const duration = audio.duration
    audio.currentTime =
      Number.isFinite(duration) && duration > 0 ? Math.min(pending, duration) : pending
  }, [])

  // 引用稳定：TranscriptRow 是 memo 的，播放推进时不应因回调换引用而全列表重渲染
  const seek = useCallback((segment: TranscriptSegment) => {
    const audio = audioRef.current
    if (!audio) return
    audio.currentTime = segment.startMs / 1000
    setActiveId(segment.id)
  }, [])

  // 父级刷新出新的终态后清除本地 pending；刷新失败/旧状态不满足条件时保持 busy
  useEffect(() => {
    if (pendingAiStatus === null) {
      sawPendingAiRef.current = false
      return
    }
    if (minutes.aiStatus === 10 || minutes.aiStatus === 20) {
      sawPendingAiRef.current = true
      return
    }
    const terminal = minutes.aiStatus === 30 || minutes.aiStatus === 40
    const fresh =
      generateBaselineRef.current === null ||
      minutes.updatedAt !== generateBaselineRef.current
    if (terminal && (fresh || sawPendingAiRef.current)) {
      setPendingAiStatus(null)
      sawPendingAiRef.current = false
      generateBaselineRef.current = null
    }
  }, [minutes.aiStatus, minutes.updatedAt, pendingAiStatus])

  const handleGenerate = useCallback(async () => {
    if (effectiveAiStatus === 30) {
      const confirmed = await new Promise<boolean>((resolve) => {
        modal.confirm({
          title: '重新生成 AI 纪要？',
          content: '重新生成会覆盖当前纪要，并产生一次 AI 调用费用。',
          okText: '重新生成',
          cancelText: '取消',
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        })
      })
      if (!confirmed) return
    }
    setGenerating(true)
    try {
      const result = await generateMinutes(meeting.id)
      generateBaselineRef.current = minutes.updatedAt
      setPendingAiStatus(result.aiStatus)
      message.success('已提交生成，请稍候')
    } catch (e) {
      setGenerating(false)
      message.error(e instanceof ApiError ? e.message : '提交生成失败')
      return
    }
    try {
      await onRefresh()
    } catch {
      message.error('已提交生成，但纪要状态刷新失败，请稍后手动刷新')
    } finally {
      setGenerating(false)
    }
  }, [effectiveAiStatus, meeting.id, message, minutes.updatedAt, modal, onRefresh])

  const handleExport = useCallback(async () => {
    setExporting(true)
    try {
      await exportMinutes(meeting.id)
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '导出失败')
    } finally {
      setExporting(false)
    }
  }, [meeting.id, message])

  const handleRename = useCallback(
    async (displayName: string, color: string) => {
      if (!renaming) return
      try {
        await renameSpeaker(meeting.id, renaming.speakerKey, { displayName, color })
        message.success('发言人已更新')
        setRenaming(null)
        void onRefresh().catch(() => {
          // 刷新失败由面板的 Alert 呈现，不影响这次重命名成功的提示
        })
      } catch (e) {
        message.error(e instanceof ApiError ? e.message : '更新失败')
      }
    },
    [meeting.id, message, onRefresh, renaming],
  )

  const retryTranscript = useCallback(() => {
    void loadTranscript()
  }, [loadTranscript])

  // 头部刷新：纪要 + 转写 + 音频 URL 一起刷新（生成后也靠它把新状态带回来）
  const handleRefresh = useCallback(async () => {
    setRefreshing(true)
    try {
      await Promise.allSettled([
        Promise.resolve().then(() => onRefresh()),
        loadTranscript(),
        loadAudioUrl(),
      ])
    } finally {
      setRefreshing(false)
    }
  }, [loadAudioUrl, loadTranscript, onRefresh])

  const transcriptErrorInfo = transcriptError
    ? describeMinutesError(transcriptError, '转写加载失败')
    : null
  const transcriptErrorText = transcriptErrorInfo?.message || '转写加载失败'

  return (
    <Card
      title={
        <Space>
          <span>会议纪要</span>
          <Tag color={aiMeta.color}>AI {aiMeta.text}</Tag>
        </Space>
      }
      extra={
        <Space>
          <Button
            icon={<RefreshCw size={15} />}
            loading={refreshing}
            disabled={refreshing}
            onClick={() => void handleRefresh()}
            aria-label="刷新纪要"
          >
            刷新
          </Button>
          <Button icon={<Download size={15} />} loading={exporting} onClick={handleExport}>
            导出 Word
          </Button>
        </Space>
      }
    >
      {!hasAudio ? (
        <Alert type="warning" showIcon message="音频暂不可用，转写与 AI 纪要仍可查看" />
      ) : audioError ? (
        <Alert
          type="error"
          showIcon
          message={audioError}
          action={
            <Button size="small" onClick={retryAudio} aria-label="重试加载音频">
              重试
            </Button>
          }
        />
      ) : audioLoading && !audioUrl ? (
        <div className="flex justify-center py-6">
          <Spin />
        </div>
      ) : audioUrl ? (
        <AudioPlayer
          url={audioUrl}
          audioRef={audioRef}
          onTimeUpdate={(ms) => setActiveId(findActiveSegment(segments, ms))}
          onError={handleAudioError}
          onLoadedMetadata={handleAudioLoadedMetadata}
        />
      ) : (
        <div className="flex justify-center py-6">
          <Spin />
        </div>
      )}
      <Tabs
        className="mt-4"
        items={[
          {
            key: 'transcript',
            label: '转写',
            children: (
              <div>
                {transcriptErrorInfo ? (
                  <>
                    <Alert
                      className="mb-3"
                      type="error"
                      showIcon
                      message={transcriptErrorText}
                      action={
                        <Button
                          size="small"
                          onClick={retryTranscript}
                          aria-label="重试加载转写"
                        >
                          重试
                        </Button>
                      }
                    />
                    {segments.length > 0 && (
                      <TranscriptList
                        segments={segments}
                        speakers={minutes.speakers}
                        activeId={activeId}
                        canManage={canManage}
                        onSelect={seek}
                        onRename={setRenaming}
                      />
                    )}
                  </>
                ) : transcriptLoading && segments.length === 0 ? (
                  <div className="flex justify-center py-8">
                    <Spin />
                  </div>
                ) : (
                  <TranscriptList
                    segments={segments}
                    speakers={minutes.speakers}
                    activeId={activeId}
                    canManage={canManage}
                    onSelect={seek}
                    onRename={setRenaming}
                  />
                )}
                <Typography.Text type="secondary" className="mt-2 block text-xs">
                  播放时高亮当前段落；点击转写文本可跳转到对应录音位置。
                </Typography.Text>
              </div>
            ),
          },
          {
            key: 'summary',
            label: 'AI 纪要',
            children: (
              <Space direction="vertical" className="w-full" size="middle">
                <MinutesAiBar
                  aiStatus={effectiveAiStatus}
                  aiError={minutes.aiError}
                  canManage={canManage}
                  loading={generating}
                  onGenerate={handleGenerate}
                />
                <MinutesSummary
                  summary={minutes.summary}
                  aiStatus={effectiveAiStatus}
                  aiError={minutes.aiError}
                />
              </Space>
            ),
          },
          {
            key: 'insights',
            label: '会议洞察',
            children: <MinutesInsights insights={minutes.insights} />,
          },
        ]}
      />
      <SpeakerRenameModal
        open={renaming !== null}
        speaker={renaming}
        onClose={() => setRenaming(null)}
        onSubmit={handleRename}
      />
    </Card>
  )
}
