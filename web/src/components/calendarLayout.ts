/**
 * src/components/calendarLayout.ts
 * 重叠会议的分泳道布局（T6.1）：同一会议室同一时段可能有多场会议（尤其“待审批”不占时段，
 * 可以和多场已预约并存），绝对定位会让它们完全盖在一起。这里把**互相重叠的会议聚成一簇**，
 * 簇内贪心分配 lane，前端按 `lane/lanes` 把宽度均分。
 *
 * 纯函数：不依赖 React/DOM，便于用单测钉住“相邻不算重叠”“不同簇互不挤压”等边界。
 */
import dayjs from 'dayjs'
import type { CalendarMeeting } from '@/api/meeting'

export interface MeetingLane {
  /** 0 起 */
  lane: number
  /** 本簇一共几条泳道（用于算宽度） */
  lanes: number
}

/**
 * 计算每条会议的泳道。返回 meetingId → { lane, lanes }。
 *
 * 算法：
 * ① 按开始时间排序，把**时间上连通**的会议聚成一簇（当前会议的开始 >= 簇内最大结束 → 新簇）；
 * ② 簇内贪心：依次放进“最早空出来的泳道”（该泳道上一场结束 <= 本场开始）；
 * ③ 簇的泳道数 = 簇内用到的最大泳道数 + 1，簇内所有会议按同一分母均分宽度。
 */
export function layoutMeetings(meetings: CalendarMeeting[]): Map<number, MeetingLane> {
  const sorted = [...meetings].sort((a, b) => {
    const delta = dayjs(a.startTime).valueOf() - dayjs(b.startTime).valueOf()
    return delta !== 0 ? delta : a.meetingId - b.meetingId
  })

  const result = new Map<number, MeetingLane>()
  let cluster: CalendarMeeting[] = []
  let clusterEnd = -Infinity

  const flush = () => {
    if (cluster.length === 0) return
    const laneEnds: number[] = []
    const laneById = new Map<number, number>()

    for (const meeting of cluster) {
      const start = dayjs(meeting.startTime).valueOf()
      const end = dayjs(meeting.endTime).valueOf()
      // 找最早空出来的泳道：laneEnd <= start 表示上一场在它开始前已经结束（相邻不算重叠）
      let lane = laneEnds.findIndex((laneEnd) => laneEnd <= start)
      if (lane === -1) {
        lane = laneEnds.length
        laneEnds.push(end)
      } else {
        laneEnds[lane] = end
      }
      laneById.set(meeting.meetingId, lane)
    }

    const lanes = laneEnds.length
    for (const meeting of cluster) {
      result.set(meeting.meetingId, { lane: laneById.get(meeting.meetingId)!, lanes })
    }
    cluster = []
    clusterEnd = -Infinity
  }

  for (const meeting of sorted) {
    const start = dayjs(meeting.startTime).valueOf()
    // 与当前簇完全不重叠（开始 >= 簇内最大结束）→ 先收掉上一簇
    if (cluster.length > 0 && start >= clusterEnd) {
      flush()
    }
    cluster.push(meeting)
    clusterEnd = Math.max(clusterEnd, dayjs(meeting.endTime).valueOf())
  }
  flush()

  return result
}
