/**
 * 纪要工作台：播放器 + 转写联动 + AI 纪要 + 会议洞察 + 导出。
 * - 转写分段与音频 URL 独立加载；音频地址过期（403/加载失败）时重新签发并恢复播放位置；
 * - seek / 重命名回调保持引用稳定，避免破坏 TranscriptRow 的 memo；
 * - 记录播放位置：onTimeUpdate 用二分查找定位当前分段，点击转写反向跳转；
 * - 生成/导出失败用 antd message 提示，成功后调用 onRefresh 让面板重查（AI 生成走轮询）。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, App, Button, Card, Space, Tabs, Tag, Typography } from 'antd'
import { Download, RefreshCw, Sparkles } from 'lucide-react'
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

interface Props {
  meeting: MeetingDetail
  minutes: MinutesDetail
  canManage: boolean
  onRefresh: () => void
}

const AI_TEXT: Record<number, string> = {
  0: '未生成',
  10: '待生成',
  20: '生成中',
  30: '已生成',
  40: '生成失败',
}

export default function AudioTranscriptWorkspace({
  meeting,
  minutes,
  canManage,
  onRefresh,
}: Props) {
  const { message } = App.useApp()
  const audioRef = useRef<HTMLAudioElement>(null)
  const [segments, setSegments] = useState<TranscriptSegment[]>([])
  const [audioUrl, setAudioUrl] = useState<string | null>(null)
  const [activeId, setActiveId] = useState<number | null>(null)
  const [renaming, setRenaming] = useState<MinutesSpeaker | null>(null)
  const [generating, setGenerating] = useState(false)
  const [exporting, setExporting] = useState(false)

  useEffect(() => {
    let cancelled = false
    getTranscript(meeting.id)
      .then((result) => {
        if (!cancelled) setSegments(result.segments)
      })
      .catch(() => {
        if (!cancelled) message.error('转写加载失败')
      })
    return () => {
      cancelled = true
    }
  }, [meeting.id, message])

  const loadAudioUrl = useCallback(async () => {
    try {
      const result = await getAudioUrl(meeting.id)
      setAudioUrl(result.url)
    } catch {
      message.error('音频加载失败，请稍后重试')
    }
  }, [meeting.id, message])

  useEffect(() => {
    void loadAudioUrl()
  }, [loadAudioUrl])

  const handleAudioError = useCallback(() => {
    const position = audioRef.current?.currentTime ?? 0
    void getAudioUrl(meeting.id)
      .then((result) => {
        setAudioUrl(result.url)
        window.setTimeout(() => {
          if (audioRef.current) audioRef.current.currentTime = position
        }, 300)
      })
      .catch(() => message.error('音频地址已过期，请刷新页面'))
  }, [meeting.id, message])

  // 引用稳定：TranscriptRow 是 memo 的，播放推进时不应因回调换引用而全列表重渲染
  const seek = useCallback((segment: TranscriptSegment) => {
    const audio = audioRef.current
    if (!audio) return
    audio.currentTime = segment.startMs / 1000
    setActiveId(segment.id)
  }, [])

  const handleGenerate = useCallback(async () => {
    setGenerating(true)
    try {
      await generateMinutes(meeting.id)
      message.success('已提交生成，请稍候')
      setActiveId(null)
      onRefresh()
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '提交生成失败')
    } finally {
      setGenerating(false)
    }
  }, [meeting.id, message, onRefresh])

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
        onRefresh()
      } catch (e) {
        message.error(e instanceof ApiError ? e.message : '更新失败')
      }
    },
    [meeting.id, message, onRefresh, renaming],
  )

  const aiBusy = minutes.aiStatus === 10 || minutes.aiStatus === 20

  return (
    <Card
      title={
        <Space>
          <span>会议纪要</span>
          <Tag
            color={
              minutes.aiStatus === 30 ? 'green' : minutes.aiStatus === 40 ? 'red' : 'blue'
            }
          >
            AI {AI_TEXT[minutes.aiStatus] ?? minutes.aiStatus}
          </Tag>
        </Space>
      }
      extra={
        <Space>
          <Button icon={<RefreshCw size={15} />} onClick={onRefresh} aria-label="刷新纪要">
            刷新
          </Button>
          <Button icon={<Download size={15} />} loading={exporting} onClick={handleExport}>
            导出 Word
          </Button>
          {canManage && (
            <Button
              type="primary"
              icon={<Sparkles size={15} />}
              loading={generating}
              disabled={aiBusy}
              onClick={handleGenerate}
            >
              {minutes.aiStatus === 0 ? '生成纪要' : '重新生成'}
            </Button>
          )}
        </Space>
      }
    >
      {audioUrl ? (
        <AudioPlayer
          url={audioUrl}
          audioRef={audioRef}
          onTimeUpdate={(ms) => setActiveId(findActiveSegment(segments, ms))}
          onError={handleAudioError}
        />
      ) : (
        <Alert type="warning" showIcon message="音频暂不可用，转写与 AI 纪要仍可查看" />
      )}
      <Tabs
        className="mt-4"
        items={[
          {
            key: 'transcript',
            label: '转写',
            children: (
              <TranscriptList
                segments={segments}
                speakers={minutes.speakers}
                activeId={activeId}
                canManage={canManage}
                onSelect={seek}
                onRename={setRenaming}
              />
            ),
          },
          {
            key: 'summary',
            label: 'AI 纪要',
            children: (
              <Space direction="vertical" className="w-full" size="middle">
                <MinutesAiBar
                  aiStatus={minutes.aiStatus}
                  aiError={minutes.aiError}
                  canManage={canManage}
                  loading={generating}
                  onGenerate={handleGenerate}
                />
                <MinutesSummary
                  summary={minutes.summary}
                  aiStatus={minutes.aiStatus}
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
      <Typography.Text type="secondary" className="mt-2 block text-xs">
        播放时高亮当前段落；点击转写文本可跳转到对应录音位置。
      </Typography.Text>
      <SpeakerRenameModal
        open={renaming !== null}
        speaker={renaming}
        onClose={() => setRenaming(null)}
        onSubmit={handleRename}
      />
    </Card>
  )
}
