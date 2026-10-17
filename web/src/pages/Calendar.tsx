/**
 * src/pages/Calendar.tsx
 * 日历预约页（02 文档 4.2 核心页面）：顶部周切换器 + 会议室 × 7 天网格。
 * 数据编排：listRooms() 取可用会议室 → 对每个会议室并发取该周日历
 *   （calendar 接口是**单会议室**维度，页面是多会议室，故 Promise.all 扇出）。
 * 竞态处理：请求结果里带上"本次请求的周"，渲染前与当前周比对——
 *   快速切周时过期响应即使后到也不会画进网格（docs/02 4.2 明确要求）。
 * 本任务（T2.F1）做到"点空格带出预填时间"；预约 Drawer 表单由 T2.F2 接入。
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Input, Space, Spin, Tooltip } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { ChevronLeft, ChevronRight, RefreshCw, Search } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { getRoomCalendars, type CalendarDay, type CalendarMeeting } from '@/api/meeting'
import { listRooms, type Room } from '@/api/room'
import { CALENDAR_CATEGORY_META, type CalendarCategory } from '@/components/calendarAppearance'
import { CalendarGrid, type EmptySlot } from '@/components/CalendarGrid'
import BookingDrawer, { type BookingPrefill } from '@/pages/BookingDrawer'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'

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

/** 图例顺序：与 calendarAppearance 的优先级一致 */
const LEGEND_CATEGORIES: CalendarCategory[] = ['pending', 'mine', 'invited', 'other']

export default function Calendar() {
  const navigate = useNavigate()
  const user = useUserStore((s) => s.user)
  const [week, setWeek] = useState<Dayjs>(() => mondayOf(dayjs()))
  const [bookingOpen, setBookingOpen] = useState(false)
  const [prefill, setPrefill] = useState<BookingPrefill | null>(null)
  const [roomKeyword, setRoomKeyword] = useState('')
  const weekStr = week.format('YYYY-MM-DD')
  const canApprove = (user?.roles ?? []).some((role) => role === 'APPROVER' || role === 'ADMIN')

  const { data, loading, refresh } = useAppRequest<WeekPayload>(
    async () => {
      const rooms = await listRooms({ status: 0 })
      if (rooms.length === 0) {
        return { week: weekStr, rooms, daysByRoom: new Map<number, CalendarDay[]>(), failedRoomIds: [] }
      }
      // T6.3：一次批量请求取全部会议室（原来 N 个房间 = N 个 /rooms/{id}/calendar 请求）。
      // 后端仍按房间缓存/逐房间容错；failedRoomIds 用于保留“单个房间失败不空白整周”的语义。
      const calendars = await getRoomCalendars(rooms.map((room) => room.id), weekStr)
      const daysByRoom = new Map<number, CalendarDay[]>(
        calendars.items.map((item) => [item.roomId, item.days]),
      )
      return { week: weekStr, rooms, daysByRoom, failedRoomIds: calendars.failedRoomIds }
    },
    { manual: false, refreshDeps: [weekStr] },
  )

  // 竞态守卫：周对不上的（过期）响应一律视为未加载，宁可多转一会儿圈也不画旧数据
  const payload = data && data.week === weekStr ? data : undefined

  // 会议室维护/恢复后，已打开的日历页不会自动知道——回到页面时重查一次（房间列表 + 各房间日程）。
  // 房间列表本身有后端缓存，但 RoomService 的写操作会整体失效缓存，所以这里能拿到最新状态。
  useEffect(() => {
    const onFocus = () => refresh()
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [refresh])

  const dates = useMemo(
    () => Array.from({ length: 7 }, (_, i) => week.add(i, 'day').format('YYYY-MM-DD')),
    [week],
  )

  // 会议室过滤：listRooms 不分页（当前量级很小），前端过滤足够；将来分页再下沉到后端
  const filteredRooms = useMemo(() => {
    const keyword = roomKeyword.trim().toLowerCase()
    const rooms = payload?.rooms ?? []
    if (!keyword) return rooms
    return rooms.filter((room) =>
      [room.name, room.location, ...(room.deviceTags ?? [])]
        .join(' ')
        .toLowerCase()
        .includes(keyword),
    )
  }, [payload, roomKeyword])

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
          <Button size="small" aria-label="刷新" icon={<RefreshCw size={14} />} onClick={() => refresh()} />
          <span className="text-sm text-gray-500 dark:text-slate-400">
            {week.format('YYYY-MM-DD')} ~ {week.add(6, 'day').format('MM-DD')}
          </span>
        </Space>
      }
    >
      {/* 工具栏：会议室关键字过滤 + 四色图例（T6.1） */}
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <Input
          allowClear
          value={roomKeyword}
          onChange={(e) => setRoomKeyword(e.target.value)}
          prefix={<Search size={14} className="text-slate-400 dark:text-slate-500" />}
          placeholder="搜索会议室名称 / 位置 / 设备"
          style={{ width: 280 }}
        />
        <div className="flex flex-wrap items-center gap-4">
          {LEGEND_CATEGORIES.map((category) => {
            const meta = CALENDAR_CATEGORY_META[category]
            return (
              <span key={category} className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                <span className={`inline-block h-2.5 w-2.5 rounded-sm ${meta.dotClass}`} />
                {meta.label}
              </span>
            )
          })}
          <Tooltip title="已过期优先置灰；其余按 待审批 > 我预订 > 邀请我的 > 其他">
            <span className="cursor-help text-xs text-slate-400 dark:text-slate-500">颜色说明</span>
          </Tooltip>
        </div>
      </div>

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
              rooms={filteredRooms}
              dates={dates}
              daysByRoom={payload.daysByRoom}
              currentUserId={user?.id}
              canApprove={canApprove}
              emptyText={roomKeyword.trim() ? '没有匹配的会议室' : '暂无可用会议室'}
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
