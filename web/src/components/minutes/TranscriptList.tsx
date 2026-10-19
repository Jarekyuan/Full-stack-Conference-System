/**
 * 语音转写分段列表：时间戳 + 发言人 + 文本。
 * - 文本在 ASR 分段内再按标点切成子句，播放时只高亮当前子句（不是整段）；
 * - 点击子句按估算时间跳转；子句时间由整段起止按字符数比例分配（ASR 只给段级时间）；
 * - 键盘聚焦文本后 Enter/Space 选中（优先当前子句）；
 * - 有管理权限时点击发言人标签打开重命名弹窗；
 * - 行级 memo：播放推进只重渲染高亮变化的那一行；
 * - 虚拟滚动：只挂载可视区 + overscan 行，2 万段转写也不会产生 2 万个 DOM 节点。
 *   行高由文本换行决定，因此用 measureElement 动态测量，而不是假设固定行高。
 */
import { memo, useEffect, useMemo, useRef } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { Empty, Tag } from 'antd'
import type { MinutesSpeaker, TranscriptSegment } from '@/api/minutes'
import {
  formatOffset,
  splitSegmentIntoSentences,
  type TimedSentence,
} from '@/utils/transcriptSync'

interface Props {
  segments: TranscriptSegment[]
  speakers: MinutesSpeaker[]
  activeId: number | null
  /** 当前子句下标；仅 activeId 对应的那一行使用 */
  activeSentenceIndex: number | null
  onSelect: (segment: TranscriptSegment, sentenceIndex: number, startMs: number) => void
}

interface RowProps {
  segment: TranscriptSegment
  speaker: MinutesSpeaker | undefined
  active: boolean
  activeSentenceIndex: number | null
  onSelect: (segment: TranscriptSegment, sentenceIndex: number, startMs: number) => void
}

const TranscriptRow = memo(function TranscriptRow({
  segment,
  speaker,
  active,
  activeSentenceIndex,
  onSelect,
}: RowProps) {
  const color = speaker?.color ?? '#7D8CFF'
  const phrases = useMemo<TimedSentence[]>(() => {
    const split = splitSegmentIntoSentences(segment)
    // 纯空白/异常数据兜底，保证点击仍能定位到整段起点
    return split.length > 0
      ? split
      : [{ text: segment.text, startMs: segment.startMs, endMs: segment.endMs }]
  }, [segment])

  const selectSentence = (index: number) => {
    const sentence = phrases[index] ?? phrases[0]
    if (sentence) onSelect(segment, index, sentence.startMs)
  }

  return (
    <div
      data-segment-id={segment.id}
      onClick={() => selectSentence(0)}
      className={`mb-1 cursor-pointer rounded border-l-4 px-2 py-1.5 transition-colors ${
        active
          ? ''
          : 'border-transparent hover:bg-slate-50 dark:hover:bg-slate-800/60'
      }`}
      style={active ? { borderLeftColor: color } : undefined}
    >
      <div className="mb-1 flex items-center gap-2">
        <span
          className="rounded px-1.5 py-0.5 text-xs font-medium"
          style={{ color, backgroundColor: `${color}1A` }}
        >
          {speaker?.displayName ?? `说话人 ${segment.speakerKey}`}
        </span>
        <Tag className="m-0 text-[11px]">{formatOffset(segment.startMs)}</Tag>
      </div>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation()
          const target = (e.target as HTMLElement).closest<HTMLElement>('[data-sentence-index]')
          const index = target ? Number(target.dataset.sentenceIndex) : 0
          selectSentence(Number.isFinite(index) ? index : 0)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            e.stopPropagation()
            selectSentence(active && activeSentenceIndex != null ? activeSentenceIndex : 0)
          }
        }}
        className="w-full rounded text-left text-sm leading-6 text-slate-800 focus-visible:ring-2 focus-visible:ring-blue-400 dark:text-slate-200"
      >
        {phrases.map((sentence, index) => (
          <span
            key={index}
            data-sentence-index={index}
            className={
              active && index === activeSentenceIndex
                ? 'box-decoration-clone rounded bg-blue-100 px-0.5 text-slate-900 transition-colors dark:bg-blue-900/60 dark:text-slate-100'
                : undefined
            }
          >
            {sentence.text}
          </span>
        ))}
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

/** 单行估算高度（两行文本 + 时间戳行）；真实高度由 measureElement 校正 */
const ESTIMATED_ROW_HEIGHT = 84
const OVERSCAN_ROWS = 8

function TranscriptList({
  segments,
  speakers,
  activeId,
  activeSentenceIndex,
  onSelect,
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

  const activeIndex = useMemo(
    () => (activeId == null ? -1 : segments.findIndex((segment) => segment.id === activeId)),
    [activeId, segments],
  )

  const virtualizer = useVirtualizer({
    count: segments.length,
    getScrollElement: () => containerRef.current,
    estimateSize: () => ESTIMATED_ROW_HEIGHT,
    getItemKey: (index) => segments[index]?.id ?? index,
    overscan: OVERSCAN_ROWS,
  })

  // 重新导入/切换会议会替换 segments：清掉旧行高缓存，避免新文本沿用旧测量值。
  useEffect(() => {
    virtualizer.measure()
  }, [segments, virtualizer])

  // 播放推进时让当前段保持在可视区；只滚到 index，不操作 DOM，
  // 未渲染的行也能通过 total size 估算出正确位置。
  useEffect(() => {
    if (activeIndex < 0) return
    virtualizer.scrollToIndex(activeIndex, { align: 'auto', behavior: 'smooth' })
  }, [activeIndex, virtualizer])

  // 当前段很长时，仅滚到段首仍可能看不到正在播放的子句；再把子句滚进视口。
  useEffect(() => {
    if (activeId == null || activeSentenceIndex == null) return
    const container = containerRef.current
    if (!container) return
    const row = container.querySelector<HTMLElement>(`[data-segment-id="${activeId}"]`)
    const sentence = row?.querySelector<HTMLElement>(
      `[data-sentence-index="${activeSentenceIndex}"]`,
    )
    if (!sentence) return
    const box = container.getBoundingClientRect()
    const rect = sentence.getBoundingClientRect()
    if (rect.top < box.top || rect.bottom > box.bottom) {
      sentence.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  }, [activeId, activeSentenceIndex])

  if (segments.length === 0) {
    return <Empty description="暂无语音转写内容" />
  }

  return (
    <div
      ref={containerRef}
      className="max-h-[560px] overflow-y-auto pr-2"
      data-testid="transcript-scroll"
    >
      <div className="relative w-full" style={{ height: `${virtualizer.getTotalSize()}px` }}>
        {virtualizer.getVirtualItems().map((virtualItem) => {
          const segment = segments[virtualItem.index]
          if (!segment) return null
          return (
            <div
              key={virtualItem.key}
              data-index={virtualItem.index}
              ref={virtualizer.measureElement}
              className="absolute left-0 top-0 w-full"
              style={{ transform: `translateY(${virtualItem.start}px)` }}
            >
              <TranscriptRow
                segment={segment}
                speaker={speakerMap.get(segment.speakerKey)}
                active={segment.id === activeId}
                activeSentenceIndex={segment.id === activeId ? activeSentenceIndex : null}
                onSelect={onSelect}
              />
            </div>
          )
        })}
      </div>
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
    prev.activeSentenceIndex === next.activeSentenceIndex &&
    prev.onSelect === next.onSelect &&
    speakerListSignature(prev.speakers) === speakerListSignature(next.speakers),
)
