/**
 * 语音文本联动的纯函数（与组件解耦，便于单测）。
 * 时间统一用相对音频开始的毫秒，和后端 meeting_transcript_segment 一致。
 */

export interface TimedSegment {
  id: number
  startMs: number
  endMs: number
}

/** 一个 ASR 分段内按标点切出的子句；时间按字符数比例估算 */
export interface TimedSentence {
  text: string
  startMs: number
  endMs: number
}

/**
 * 按中文/英文逗号、句号、问号、叹号、分号、省略号切子句，标点保留在前一子句。
 * ASR 只给整段起止时间，因此子句时间是按字符数比例分配的近似值。
 */
const SENTENCE_PATTERN = /[^，。！？；,;!?…]+[，。！？；,;!?…]*|[，。！？；,;!?…]+/gu

export function splitSentences(text: string): string[] {
  if (!text || !text.trim()) return []
  return (text.match(SENTENCE_PATTERN) ?? [])
    .map((sentence) => sentence.trim())
    .filter(Boolean)
}

/** 把分段文本切成带估算时间的子句，供逐句高亮/点击定位使用。 */
export function splitSegmentIntoSentences(
  segment: TimedSegment & { text: string },
): TimedSentence[] {
  const sentences = splitSentences(segment.text)
  if (sentences.length === 0) return []
  const weights = sentences.map((sentence) =>
    Math.max(1, Array.from(sentence.replace(/[，。！？；,;!?…\s]/gu, '')).length),
  )
  const totalWeight = weights.reduce((sum, weight) => sum + weight, 0)
  const duration = Math.max(0, segment.endMs - segment.startMs)
  let elapsed = 0
  return sentences.map((text, index) => {
    const startMs = segment.startMs + Math.round((duration * elapsed) / totalWeight)
    elapsed += weights[index]
    const endMs = index === sentences.length - 1
      ? segment.endMs
      : segment.startMs + Math.round((duration * elapsed) / totalWeight)
    return { text, startMs, endMs: Math.max(startMs, endMs) }
  })
}

function findActiveSegmentEntry<T extends TimedSegment>(
  segments: T[],
  currentMs: number,
): T | null {
  if (segments.length === 0 || currentMs < segments[0].startMs) return null

  let low = 0
  let high = segments.length - 1
  let candidate = -1
  while (low <= high) {
    const mid = Math.floor((low + high) / 2)
    if (segments[mid].startMs <= currentMs) {
      candidate = mid
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  if (candidate < 0) return null
  for (let i = candidate; i >= 0; i -= 1) {
    if (currentMs < segments[i].endMs) return segments[i]
  }
  return null
}

/** 当前播放时间对应的子句（ASR 分段内再按标点细分）。 */
export function findActiveSentence<T extends TimedSegment & { text: string }>(
  segments: T[],
  currentMs: number,
): { segmentId: number; sentenceIndex: number; startMs: number; endMs: number } | null {
  const segment = findActiveSegmentEntry(segments, currentMs)
  if (!segment) return null
  const sentences = splitSegmentIntoSentences(segment)
  if (sentences.length === 0) return null
  let sentenceIndex = 0
  for (let i = 0; i < sentences.length; i += 1) {
    if (sentences[i].startMs <= currentMs) sentenceIndex = i
    else break
  }
  const sentence = sentences[sentenceIndex]
  return {
    segmentId: segment.id,
    sentenceIndex,
    startMs: sentence.startMs,
    endMs: sentence.endMs,
  }
}

/**
 * 找到 currentMs 所在的分段：
 * - 按 startMs 升序二分，取最后一个 startMs <= currentMs；
 * - 若该段未结束（currentMs < endMs）返回其 id；
 * - 否则向前回退，找最近的仍覆盖 currentMs 的段（多段重叠时，
 *   取覆盖 currentMs 的段中 startMs 最大者）；
 * - 没有段覆盖 currentMs（段间空档/首段之前/末段之后）返回 null。
 *
 * 正常转写分段按时间顺序且互不重叠，候选段一次命中；回退只发生在
 * 上游产生了重叠分段的异常数据时。
 *
 * 调用方必须保证 segments 按 startMs 升序传入；无序输入的行为未定义。
 */
export function findActiveSegment(
  segments: TimedSegment[],
  currentMs: number,
): number | null {
  return findActiveSegmentEntry(segments, currentMs)?.id ?? null
}

/** 毫秒 → HH:mm:ss（用于转写时间戳与导出预览）；非法数值按 0 处理 */
export function formatOffset(ms: number): string {
  if (!Number.isFinite(ms)) return '00:00:00'
  const total = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return [hours, minutes, seconds].map((v) => String(v).padStart(2, '0')).join(':')
}
