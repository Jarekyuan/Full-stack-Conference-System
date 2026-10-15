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
import { Alert, Button, Card, Space, Spin } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { getRoomCalendar, type CalendarDay, type CalendarMeeting } from '@/api/meeting'
import { listRooms, type Room } from '@/api/room'
import { CalendarGrid, type EmptySlot } from '@/components/CalendarGrid'
import BookingDrawer, { type BookingPrefill } from '@/pages/BookingDrawer'
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
  /** 取数失败的会议室（逐房间容错，见下方 allSettled 说明） */
  failedRoomIds: number[]
}

export default function Calendar() {
  const navigate = useNavigate()
  const [week, setWeek] = useState<Dayjs>(() => mondayOf(dayjs()))
  const [bookingOpen, setBookingOpen] = useState(false)
  const [prefill, setPrefill] = useState<BookingPrefill | null>(null)
  const weekStr = week.format('YYYY-MM-DD')

  const { data, loading, refresh } = useAppRequest<WeekPayload>(
    async () => {
      const rooms = await listRooms({ status: 0 })
      // 用 allSettled 而非 all：6 个会议室里只要 1 个失败（网络抖动、会议室刚被删 → 30001），
      // Promise.all 会让整个 service reject → 新一周的网格**完全不渲染**、只剩空白 + 一条 toast，
      // 用户会以为"这一周没有任何会议"（评审 R14）。失败的房间单独标记，其余照常画。
      const settled = await Promise.allSettled(rooms.map((room) => getRoomCalendar(room.id, weekStr)))
      const daysByRoom = new Map<number, CalendarDay[]>()
      const failedRoomIds: number[] = []
      settled.forEach((result, index) => {
        if (result.status === 'fulfilled') {
          daysByRoom.set(result.value.roomId, result.value.days)
        } else {
          failedRoomIds.push(rooms[index].id)
        }
      })
      return { week: weekStr, rooms, daysByRoom, failedRoomIds }
    },
    { manual: false, refreshDeps: [weekStr] },
  )

  // 竞态守卫：周对不上的（过期）响应一律视为未加载，宁可多转一会儿圈也不画旧数据
  const payload = data && data.week === weekStr ? data : undefined

  const dates = useMemo(
    () => Array.from({ length: 7 }, (_, i) => week.add(i, 'day').format('YYYY-MM-DD')),
    [week],
  )

  // 点空格 → 打开预约 Drawer（T2.F2），把日期 + 吸附后的分钟数组装成接口时间格式
  const handleEmptyClick = useCallback((slot: EmptySlot) => {
    const day = dayjs(slot.date)
    setPrefill({
      roomId: slot.roomId,
      startTime: day.add(slot.startMinutes, 'minute').format('YYYY-MM-DD HH:mm:ss'),
      endTime: day.add(slot.endMinutes, 'minute').format('YYYY-MM-DD HH:mm:ss'),
    })
    setBookingOpen(true)
  }, [])

  // 点会议块 → 进详情页（docs/02 4.2 原型图："已预约块，点击看详情"）
  const handleMeetingClick = useCallback(
    (meeting: CalendarMeeting) => navigate(`/meetings/${meeting.meetingId}`),
    [navigate],
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
          {payload && payload.failedRoomIds.length > 0 && (
            <Alert
              type="warning"
              showIcon
              className="mb-3"
              message={`部分会议室日程加载失败：${payload.failedRoomIds
                .map((id) => payload.rooms.find((r) => r.id === id)?.name ?? id)
                .join('、')}——切周可重试`}
            />
          )}
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

      <BookingDrawer
        open={bookingOpen}
        prefill={prefill}
        rooms={payload?.rooms ?? []}
        onClose={() => setBookingOpen(false)}
        onSuccess={refresh}
      />
    </Card>
  )
}
