import { describe, expect, it } from 'vitest'
import { findActiveSegment, formatOffset } from './transcriptSync'

const segments = [
  { id: 1, startMs: 0, endMs: 1000 },
  { id: 2, startMs: 2000, endMs: 3000 },
  { id: 3, startMs: 5000, endMs: 6000 },
]

describe('findActiveSegment', () => {
  it('空数组返回 null', () => {
    expect(findActiveSegment([], 100)).toBeNull()
  })

  it('首段之前返回 null', () => {
    expect(findActiveSegment([{ id: 9, startMs: 1000, endMs: 2000 }], 999)).toBeNull()
  })

  it('段内返回 id', () => {
    expect(findActiveSegment(segments, 0)).toBe(1)
    expect(findActiveSegment(segments, 999)).toBe(1)
    expect(findActiveSegment(segments, 2500)).toBe(2)
  })

  it('段间空档返回 null', () => {
    expect(findActiveSegment(segments, 1500)).toBeNull()
    expect(findActiveSegment(segments, 3500)).toBeNull()
  })

  it('末段之后返回 null', () => {
    expect(findActiveSegment(segments, 6000)).toBeNull()
  })

  it('重叠段取 startMs 最大的那一段', () => {
    const overlapping = [
      { id: 1, startMs: 0, endMs: 5000 },
      { id: 2, startMs: 1000, endMs: 3000 },
    ]
    expect(findActiveSegment(overlapping, 2000)).toBe(2)
    expect(findActiveSegment(overlapping, 4000)).toBe(1)
  })

  it('三层嵌套重叠：取当前仍覆盖且 startMs 最大的段', () => {
    const nested = [
      { id: 1, startMs: 0, endMs: 9000 },
      { id: 2, startMs: 1000, endMs: 8000 },
      { id: 3, startMs: 2000, endMs: 3000 },
    ]
    expect(findActiveSegment(nested, 2500)).toBe(3)
    expect(findActiveSegment(nested, 5000)).toBe(2)
    expect(findActiveSegment(nested, 8500)).toBe(1)
    expect(findActiveSegment(nested, 9000)).toBeNull()
  })

  it('startMs 相同的段取数组中靠后的那一段', () => {
    const sameStart = [
      { id: 1, startMs: 1000, endMs: 2000 },
      { id: 2, startMs: 1000, endMs: 3000 },
    ]
    expect(findActiveSegment(sameStart, 1500)).toBe(2)
    expect(findActiveSegment(sameStart, 2500)).toBe(2)
  })

  it('相邻段按半开区间 [startMs, endMs) 处理边界', () => {
    const adjacent = [
      { id: 1, startMs: 0, endMs: 1000 },
      { id: 2, startMs: 1000, endMs: 2000 },
    ]
    expect(findActiveSegment(adjacent, 999)).toBe(1)
    expect(findActiveSegment(adjacent, 1000)).toBe(2)
  })

  it('负时间返回 null', () => {
    expect(findActiveSegment(segments, -1)).toBeNull()
  })

  it('要求输入按 startMs 升序（无序输入行为未定义，此处钉住当前实现）', () => {
    const unsorted = [
      { id: 1, startMs: 5000, endMs: 6000 },
      { id: 2, startMs: 0, endMs: 1000 },
    ]
    // 二分前提不成立：首段起点被当成最小起点，更早的时间直接判空
    expect(findActiveSegment(unsorted, 500)).toBeNull()
  })
})

describe('formatOffset', () => {
  it('格式化为 HH:mm:ss', () => {
    expect(formatOffset(0)).toBe('00:00:00')
    expect(formatOffset(1_030)).toBe('00:00:01')
    expect(formatOffset(3_723_400)).toBe('01:02:03')
  })

  it('不足 1 秒时向下取整', () => {
    expect(formatOffset(999)).toBe('00:00:00')
    expect(formatOffset(1_999)).toBe('00:00:01')
  })

  it('负数按 0 处理', () => {
    expect(formatOffset(-1)).toBe('00:00:00')
  })

  it('超过 24 小时不折返（24h / 25h）', () => {
    expect(formatOffset(86_400_000)).toBe('24:00:00')
    expect(formatOffset(90_000_000)).toBe('25:00:00')
  })

  it('NaN / Infinity 按 0 处理', () => {
    expect(formatOffset(Number.NaN)).toBe('00:00:00')
    expect(formatOffset(Number.POSITIVE_INFINITY)).toBe('00:00:00')
    expect(formatOffset(Number.NEGATIVE_INFINITY)).toBe('00:00:00')
  })
})
