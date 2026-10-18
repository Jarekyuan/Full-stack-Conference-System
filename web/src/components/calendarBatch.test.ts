/**
 * 日历批量分片单测（T6.3 评审 R11）：
 * 50 是后端硬上限；第 51 间房必须分成两片，而不是整页失败。
 */
import { describe, expect, it } from 'vitest'
import { CALENDAR_ROOM_BATCH_SIZE, chunkRoomIds } from './calendarBatch'

const ids = (n: number) => Array.from({ length: n }, (_, i) => i + 1)

describe('chunkRoomIds', () => {
  it('空数组 → 空分片', () => {
    expect(chunkRoomIds([])).toEqual([])
  })

  it('不超过 50 → 一片', () => {
    expect(chunkRoomIds(ids(50))).toEqual([ids(50)])
  })

  it('51 个 → 50 + 1 两片（不再触发后端 10001）', () => {
    expect(chunkRoomIds(ids(51))).toEqual([ids(50), [51]])
  })

  it('120 个 → 50 + 50 + 20', () => {
    expect(chunkRoomIds(ids(120))).toEqual([ids(50), ids(50).map((i) => i + 50), ids(20).map((i) => i + 100)])
  })

  it('默认分片大小与后端上限一致', () => {
    expect(CALENDAR_ROOM_BATCH_SIZE).toBe(50)
  })

  it('非法 size 直接报错（避免死循环）', () => {
    expect(() => chunkRoomIds([1], 0)).toThrow()
  })
})
