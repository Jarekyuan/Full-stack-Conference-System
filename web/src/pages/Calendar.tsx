/**
 * src/pages/Calendar.tsx
 * 日历预约页（02 文档 4.2 核心页面）：顶部周切换器 + 会议室 × 7 天网格。
 * 数据编排：listRooms() 取可用会议室 → 对每个会议室并发取该周日历
 *   （calendar 接口是**单会议室**维度，页面是多会议室，故 Promise.all 扇出）。
 * 竞态处理：请求结果里带上"本次请求的周"，渲染前与当前周比对——
 *   快速切周时过期响应即使后到也不会画进网格（docs/02 4.2 明确要求）。
 * 本任务（T2.F1）做到"点空格带出预填时间"；预约 Drawer 表单由 T2.F2 接入。
 */
import { useCallback, useMemo, useState } from 'react'
import { App, Button, Card, Space, Spin } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { getRoomCalendar, type CalendarDay, type CalendarMeeting } from '@/api/meeting'
import { listRooms, type Room } from '@/api/room'
import { CalendarGrid, type EmptySlot } from '@/components/CalendarGrid'
import { useAppRequest } from '@/hooks/useAppRequest'

/** 取该日期所在周的周一（dayjs day(): 0=周日 … 6=周六） */
function mondayOf(date: Dayjs): Dayjs {
  const day = date.day()
  return date.startOf('day').subtract(day === 0 ? 6 : day - 1, 'day')
}

interface WeekPayload {
  /** 本次请求的周（周一 yyyy-MM-dd）——渲染前与当前周比对，充当竞态守卫 */
  week: string
  rooms: Room[]
  daysByRoom: Map<number, CalendarDay[]>
}

export default function Calendar() {
  const { message } = App.useApp()
  const [week, setWeek] = useState<Dayjs>(() => mondayOf(dayjs()))
  const weekStr = week.format('YYYY-MM-DD')

  const { data, loading } = useAppRequest<WeekPayload>(
    async () => {
      const rooms = await listRooms({ status: 0 })
      const calendars = await Promise.all(rooms.map((room) => getRoomCalendar(room.id, weekStr)))
      return { week: weekStr, rooms, daysByRoom: new Map(calendars.map((c) => [c.roomId, c.days])) }
    },
    { manual: false, refreshDeps: [weekStr] },
  )

  // 竞态守卫：周对不上的（过期）响应一律视为未加载，宁可多转一会儿圈也不画旧数据
  const payload = data && data.week === weekStr ? data : undefined

  const dates = useMemo(
    () => Array.from({ length: 7 }, (_, i) => week.add(i, 'day').format('YYYY-MM-DD')),
    [week],
  )

  const handleEmptyClick = useCallback(
    (slot: EmptySlot) => {
      const start = dayjs(slot.date).add(slot.startMinutes, 'minute').format('HH:mm')
      const end = dayjs(slot.date).add(slot.endMinutes, 'minute').format('HH:mm')
      message.info(`已带出预填时间：${slot.roomName}　${slot.date} ${start}~${end}（预约表单见 T2.F2）`)
    },
    [message],
  )

  const handleMeetingClick = useCallback(
    (meeting: CalendarMeeting) => message.info(`「${meeting.title}」——会议详情见 T2.F3`),
    [message],
  )

  return (
    <Card
      title="日历预约"
      extra={
        <Space>
          <Button
            size="small"
            aria-label="上一周"
            icon={<ChevronLeft size={14} />}
            onClick={() => setWeek((w) => w.subtract(7, 'day'))}
          />
          <Button size="small" onClick={() => setWeek(mondayOf(dayjs()))}>
            本周
          </Button>
          <Button
            size="small"
            aria-label="下一周"
            icon={<ChevronRight size={14} />}
            onClick={() => setWeek((w) => w.add(7, 'day'))}
          />
          <span className="text-sm text-gray-500">
            {week.format('YYYY-MM-DD')} ~ {week.add(6, 'day').format('MM-DD')}
          </span>
        </Space>
      }
    >
      <Spin spinning={loading}>
        <div className="min-h-[280px]">
          {payload && (
            <CalendarGrid
              rooms={payload.rooms}
              dates={dates}
              daysByRoom={payload.daysByRoom}
              onEmptyClick={handleEmptyClick}
              onMeetingClick={handleMeetingClick}
            />
          )}
        </div>
      </Spin>
    </Card>
  )
}
