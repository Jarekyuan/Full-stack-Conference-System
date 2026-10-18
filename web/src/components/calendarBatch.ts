/**
 * 日历批量请求的分片（T6.3 评审 R11）。
 *
 * 后端 `GET /rooms/calendar` 明确限制 roomIds 最多 50；前端此前把全部可用会议室一次性塞进去，
 * 第 51 间房时整个批量请求 400/10001，日历页直接空白。这里按 50 分片，页面并发请求后合并。
 */
export const CALENDAR_ROOM_BATCH_SIZE = 50

/** 按 size 切片；ids 为空返回空数组（调用方会走“暂无会议室”分支） */
export function chunkRoomIds(ids: number[], size = CALENDAR_ROOM_BATCH_SIZE): number[][] {
  if (size <= 0) {
    throw new Error('size must be positive')
  }
  const chunks: number[][] = []
  for (let i = 0; i < ids.length; i += size) {
    chunks.push(ids.slice(i, i + size))
  }
  return chunks
}
