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
})
