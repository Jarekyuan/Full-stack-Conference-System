/**
 * 语音文本联动的纯函数（与组件解耦，便于单测）。
 * 时间统一用相对音频开始的毫秒，和后端 meeting_transcript_segment 一致。
 */

export interface TimedSegment {
  id: number
  startMs: number
  endMs: number
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
 */
export function findActiveSegment(
  segments: TimedSegment[],
  currentMs: number,
): number | null {
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
    if (currentMs < segments[i].endMs) return segments[i].id
  }
  return null
}

/** 毫秒 → HH:mm:ss（用于转写时间戳与导出预览） */
export function formatOffset(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return [hours, minutes, seconds].map((v) => String(v).padStart(2, '0')).join(':')
}
