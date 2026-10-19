/**
 * 转写分段列表：时间戳 + 发言人 + 文本。
 * - 点击分段任意位置或键盘聚焦文本后 Enter/Space 选中（父组件据此跳转播放位置）；
 * - 当前播放分段高亮，并在滚出可视区时平滑滚回（自动滚动）；
 * - 有管理权限时点击发言人标签打开重命名弹窗；
 * - 行级 memo：播放推进只重渲染高亮变化的两行。
 */
import { memo, useEffect, useMemo, useRef } from 'react'
import { Empty, Tag } from 'antd'
import type { MinutesSpeaker, TranscriptSegment } from '@/api/minutes'
import { formatOffset } from '@/utils/transcriptSync'

interface Props {
  segments: TranscriptSegment[]
  speakers: MinutesSpeaker[]
  activeId: number | null
  canManage: boolean
  onSelect: (segment: TranscriptSegment) => void
  onRename: (speaker: MinutesSpeaker) => void
}

interface RowProps {
  segment: TranscriptSegment
  speaker: MinutesSpeaker | undefined
  active: boolean
  canManage: boolean
  onSelect: (segment: TranscriptSegment) => void
  onRename: (speaker: MinutesSpeaker) => void
}

const TranscriptRow = memo(function TranscriptRow({
  segment,
  speaker,
  active,
  canManage,
  onSelect,
  onRename,
}: RowProps) {
  const color = speaker?.color ?? '#7D8CFF'
  return (
    <div
      data-segment-id={segment.id}
      onClick={() => onSelect(segment)}
      className={`mb-1 cursor-pointer rounded px-2 py-1.5 transition-colors ${
        active
          ? 'border-l-4 bg-blue-50 dark:bg-blue-950/40'
          : 'border-l-4 border-transparent hover:bg-slate-50 dark:hover:bg-slate-800/60'
      }`}
      style={active ? { borderLeftColor: color } : undefined}
    >
      <div className="mb-1 flex items-center gap-2">
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation()
            if (canManage && speaker) onRename(speaker)
          }}
          onKeyDown={(e) => e.stopPropagation()}
          disabled={!canManage || !speaker}
          aria-label={speaker ? `重命名发言人 ${speaker.displayName}` : undefined}
          className="rounded px-1.5 py-0.5 text-xs font-medium focus-visible:ring-2 focus-visible:ring-blue-400 disabled:cursor-default"
          style={{ color, backgroundColor: `${color}1A` }}
        >
          {speaker?.displayName ?? `说话人 ${segment.speakerKey}`}
        </button>
        <Tag className="m-0 text-[11px]">{formatOffset(segment.startMs)}</Tag>
      </div>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation()
          onSelect(segment)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            e.stopPropagation()
            onSelect(segment)
          }
        }}
        className="w-full rounded text-left text-sm leading-6 text-slate-800 focus-visible:ring-2 focus-visible:ring-blue-400 dark:text-slate-200"
      >
        {segment.text}
      </button>
    </div>
  )
})

/** speakers 的内容签名：轮询每次返回新数组，用签名判断是否真的变化 */
function speakerListSignature(speakers: MinutesSpeaker[]): string {
  return speakers
    .map((speaker) => `${speaker.speakerKey}\u0000${speaker.displayName}\u0000${speaker.color}`)
    .join('\u0001')
}

function TranscriptList({
  segments,
  speakers,
  activeId,
  canManage,
  onSelect,
  onRename,
}: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  // 轮询每 3s 返回全新的 speakers 数组；按内容签名缓存 Map，
  // 才能让 memo 行在轮询期间保持 props 引用稳定（播放高亮时只重渲染两行）。
  const speakerSignature = speakerListSignature(speakers)
  const speakerMap = useMemo(
    () => new Map(speakers.map((speaker) => [speaker.speakerKey, speaker])),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 按内容签名而不是数组引用缓存
    [speakerSignature],
  )

  useEffect(() => {
    if (activeId == null || !containerRef.current) return
    const element = containerRef.current.querySelector<HTMLElement>(
      `[data-segment-id="${activeId}"]`,
    )
    if (!element) return
    const box = containerRef.current.getBoundingClientRect()
    const rect = element.getBoundingClientRect()
    if (rect.top < box.top || rect.bottom > box.bottom) {
      element.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  }, [activeId])

  if (segments.length === 0) {
    return <Empty description="暂无转写内容" />
  }

  return (
    <div ref={containerRef} className="max-h-[560px] overflow-y-auto pr-2">
      {segments.map((segment) => (
        <TranscriptRow
          key={segment.id}
          segment={segment}
          speaker={speakerMap.get(segment.speakerKey)}
          active={segment.id === activeId}
          canManage={canManage}
          onSelect={onSelect}
          onRename={onRename}
        />
      ))}
    </div>
  )
}

/**
 * 列表级 memo：minutes 轮询会带着全新的 speakers 数组重渲染父组件，
 * 但 segments/回调/高亮都没变时没必要重新 map 整个转写列表。
 */
export default memo(
  TranscriptList,
  (prev, next) =>
    prev.segments === next.segments &&
    prev.activeId === next.activeId &&
    prev.canManage === next.canManage &&
    prev.onSelect === next.onSelect &&
    prev.onRename === next.onRename &&
    speakerListSignature(prev.speakers) === speakerListSignature(next.speakers),
)
