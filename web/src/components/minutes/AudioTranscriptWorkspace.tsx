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

  // 音频：url/loading/error + 自动重签计数 + 待恢复的播放位置/播放状态
  const hasAudio = minutes.audio != null
  const audioFileId = minutes.audio?.fileId ?? null
  const [audioUrl, setAudioUrl] = useState<string | null>(null)
  const audioUrlRef = useRef<string | null>(null)
  const [audioLoading, setAudioLoading] = useState(false)
  const [audioError, setAudioError] = useState<string | null>(null)
  const audioErrorRef = useRef<string | null>(null)
  /** 当前源已自动重签的次数：0 可用，>=1 表示已用完自动重签机会 */
  const audioResignedRef = useRef(0)
  const audioRequestRef = useRef(0)
  const pendingSeekRef = useRef<{ position: number; wasPlaying: boolean } | null>(null)

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
    async (force = false) => {
      if (!hasAudio) {
        audioUrlRef.current = null
        audioErrorRef.current = null
        setAudioUrl(null)
        setAudioError(null)
        setAudioLoading(false)
        return
      }
      // 健康源且未强制刷新时不重取：换 src 会把播放进度重置到 0
      if (!force && audioUrlRef.current && audioErrorRef.current === null) return
      const token = ++audioRequestRef.current
      setAudioLoading(true)
      // 不在请求开始时清错误：重试按钮需要保持 loading，旧源在途抛出的错误也由成功回调统一清理
      try {
        const result = await getAudioUrl(meeting.id)
        if (audioRequestRef.current !== token) return
        const same = audioUrlRef.current === result.url
        audioUrlRef.current = result.url
        setAudioUrl(result.url)
        if (same) {
          // 同 URL 不会自动重载；错误提示保留到 loadedmetadata 再清
          audioRef.current?.load()
        } else {
          // 新 src 会随渲染加载；同时清掉在途请求期间旧源抛出的错误
          audioErrorRef.current = null
          setAudioError(null)
        }
      } catch (e) {
        if (audioRequestRef.current !== token) return
        const msg = e instanceof ApiError ? e.message : '音频加载失败，请稍后重试'
        audioErrorRef.current = msg
        setAudioError(msg)
      } finally {
        if (audioRequestRef.current === token) setAudioLoading(false)
      }
    },
    [hasAudio, meeting.id],
  )

  // 切换会议/音频文件变化：重置音频状态并重新取 URL；卸载时作废在途请求
  useEffect(() => {
    audioResignedRef.current = 0
    pendingSeekRef.current = null
    audioUrlRef.current = null
    audioErrorRef.current = null
    setAudioUrl(null)
    setAudioError(null)
    void loadAudioUrl(true)
    return () => {
      audioRequestRef.current += 1
      pendingSeekRef.current = null
    }
  }, [audioFileId, loadAudioUrl])

  /** 记录播放位置；allowResume=true 时按媒体播放状态决定恢复后是否继续播放 */
  const capturePendingSeek = useCallback((allowResume: boolean) => {
    const audio = audioRef.current
    const current = audio?.currentTime
    const fallback = pendingSeekRef.current?.position ?? 0
    pendingSeekRef.current = {
      position: typeof current === 'number' && Number.isFinite(current) ? current : fallback,
      wasPlaying: allowResume && audio != null ? !audio.paused && !audio.ended : false,
    }
  }, [])

  const retryAudio = useCallback(() => {
    capturePendingSeek(false)
    // 手动重试重新武装自动重签：下一次换源后仍允许一次自动重签
    audioResignedRef.current = 0
    void loadAudioUrl(true)
  }, [capturePendingSeek, loadAudioUrl])

  const handleAudioError = useCallback(() => {
    if (!hasAudio) return
    if (audioResignedRef.current >= 1) {
      // 自动重签后仍失败：停在错误态，位置留给手动重试
      capturePendingSeek(false)
      audioErrorRef.current = '音频地址已过期，请点击重试'
      setAudioError('音频地址已过期，请点击重试')
      return
    }
    // 每个源只自动重签一次；再失败就停下来等用户操作，避免无限请求 audio-url
    audioResignedRef.current += 1
    capturePendingSeek(true)
    void loadAudioUrl(true)
  }, [capturePendingSeek, hasAudio, loadAudioUrl])

  const handleAudioLoadedMetadata = useCallback((audio: HTMLAudioElement) => {
    // 源可正常解码：清除旧源错误，并重新武装下一次自动重签
    audioResignedRef.current = 0
    if (audioErrorRef.current !== null) {
      audioErrorRef.current = null
      setAudioError(null)
    }
    const pending = pendingSeekRef.current
    if (!pending) return
    pendingSeekRef.current = null
    const duration = audio.duration
    audio.currentTime =
      Number.isFinite(duration) && duration > 0
        ? Math.min(pending.position, duration)
        : pending.position
    if (pending.wasPlaying) {
      void audio.play().catch(() => {
        // 浏览器拦截自动播放时保持暂停，用户可手动继续
      })
    }
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
    // 10/20 之外都算已落定（0/30/40/未知状态），但必须确认数据是提交后刷新到的
    const settled = minutes.aiStatus !== 10 && minutes.aiStatus !== 20
    const fresh =
      generateBaselineRef.current === null ||
      minutes.updatedAt !== generateBaselineRef.current
    if (settled && (fresh || sawPendingAiRef.current)) {
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

  // 头部刷新：纪要 + 转写必刷；音频只在没有地址或处于错误态时重取，
  // 健康播放中的源保持 src 不动，避免进度被重置到 0。
  const handleRefresh = useCallback(async () => {
    setRefreshing(true)
    try {
      const tasks: Promise<unknown>[] = [
        Promise.resolve().then(() => onRefresh()),
        loadTranscript(),
      ]
      if (hasAudio && (!audioUrlRef.current || audioErrorRef.current !== null)) {
        capturePendingSeek(true)
        tasks.push(loadAudioUrl(true))
      }
      await Promise.allSettled(tasks)
    } finally {
      setRefreshing(false)
    }
  }, [capturePendingSeek, hasAudio, loadAudioUrl, loadTranscript, onRefresh])

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
        <Space wrap>
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
      ) : (
        <>
          {audioError && (
            <Alert
              className="mb-3"
              type="error"
              showIcon
              message={audioError}
              action={
                <Button
                  size="small"
                  loading={audioLoading}
                  disabled={audioLoading}
                  onClick={retryAudio}
                  aria-label="重试加载音频"
                >
                  重试
                </Button>
              }
            />
          )}
          {audioUrl ? (
            <AudioPlayer
              url={audioUrl}
              audioRef={audioRef}
              onTimeUpdate={(ms) => setActiveId(findActiveSegment(segments, ms))}
              onError={handleAudioError}
              onLoadedMetadata={handleAudioLoadedMetadata}
            />
          ) : audioLoading ? (
            <div className="flex justify-center py-6">
              <Spin />
            </div>
          ) : null}
        </>
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
                <MinutesSummary summary={minutes.summary} aiStatus={effectiveAiStatus} />
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
