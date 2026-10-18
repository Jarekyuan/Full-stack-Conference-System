/**
 * 转写分段列表：时间戳 + 发言人 + 文本。
 * - 点击/回车选中分段（父组件据此跳转播放位置）；
 * - 当前播放分段高亮，并在滚出可视区时平滑滚回（自动滚动）；
 * - 有管理权限时点击发言人标签打开重命名弹窗。
 */
import { useEffect, useRef } from 'react'
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

export default function TranscriptList({
  segments,
  speakers,
  activeId,
  canManage,
  onSelect,
  onRename,
}: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const speakerMap = new Map(speakers.map((speaker) => [speaker.speakerKey, speaker]))

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
      {segments.map((segment) => {
        const speaker = speakerMap.get(segment.speakerKey)
        const color = speaker?.color ?? '#7D8CFF'
        const active = segment.id === activeId
        return (
          <div
            key={segment.id}
            data-segment-id={segment.id}
            role="button"
            tabIndex={0}
            onClick={() => onSelect(segment)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onSelect(segment)
              }
            }}
            className={`mb-1 cursor-pointer rounded px-2 py-1.5 outline-none transition-colors ${
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
                disabled={!canManage || !speaker}
                className="rounded px-1.5 py-0.5 text-xs font-medium disabled:cursor-default"
                style={{ color, backgroundColor: `${color}1A` }}
              >
                {speaker?.displayName ?? `说话人 ${segment.speakerKey}`}
              </button>
              <Tag className="m-0 text-[11px]">{formatOffset(segment.startMs)}</Tag>
            </div>
            <div className="text-sm leading-6 text-slate-800 dark:text-slate-200">
              {segment.text}
            </div>
          </div>
        )
      })}
    </div>
  )
}
