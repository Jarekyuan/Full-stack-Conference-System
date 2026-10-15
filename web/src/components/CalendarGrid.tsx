/**
 * src/components/CalendarGrid.tsx
 * 日历网格（02 文档 4.2 核心页面）：行 = 会议室，列 = 7 天；
 * 每格内一条 08:00–20:00 时间轴，会议块按 start/end 百分比定位（块高 = 时长）。
 * 交互：点格子空白 → 按点击高度反推时间（吸附 30 分钟，默认 1 小时）上抛 onEmptyClick；
 *      点会议块 → 上抛 onMeetingClick（且阻止冒泡，避免误开"新增预约"）。
 * 性能埋点：CalendarGrid / RoomRow 双层 memo —— 行数据未变时不重渲染（docs/05 T2.F1 ④）。
 * 纯展示组件：自身不持有数据请求与时刻状态，只收 props、上抛事件。
 */
import { memo } from 'react'
import dayjs from 'dayjs'
import type { Room } from '@/api/room'
import type { CalendarDay, CalendarMeeting } from '@/api/meeting'

/** 时间轴窗口（业务时间）：超出窗口的会议块裁剪到边界 */
const DAY_START_MINUTES = 8 * 60
const DAY_END_MINUTES = 20 * 60
const TOTAL_MINUTES = DAY_END_MINUTES - DAY_START_MINUTES
/** 点击吸附粒度 / 默认会议时长 */
const SNAP_MINUTES = 30
const DEFAULT_DURATION_MINUTES = 60
/** 单元格高度（px）——各行等高，时间轴比例因此一致 */
const CELL_HEIGHT = 240
/** 刻度标签（首/中/末三点，够读且不噪） */
const RULER_MINUTES = [DAY_START_MINUTES, (DAY_START_MINUTES + DAY_END_MINUTES) / 2, DAY_END_MINUTES]
/** 列模板：会议室名 | 时间刻度 | 7 天 */
const COLUMNS = '120px 52px repeat(7, minmax(96px, 1fr))'
/** 状态配色（docs/04 5.1：10 橙 / 20 蓝 / 30 绿） */
const STATUS_BLOCK_CLASS: Record<number, string> = {
  10: 'bg-amber-100 border-amber-400 text-amber-900',
  20: 'bg-blue-100 border-blue-400 text-blue-900',
  30: 'bg-emerald-100 border-emerald-400 text-emerald-900',
}
const FALLBACK_BLOCK_CLASS = 'bg-gray-100 border-gray-400 text-gray-700'

/** 空集合常量：保证"无数据"时引用稳定，否则每次渲染新建数组会让行级 memo 失效 */
const EMPTY_DAYS: CalendarDay[] = []
const EMPTY_MEETINGS: CalendarMeeting[] = []

/** 点击空白格带出的预填信息（交页面组装成接口入参；分钟数已吸附并裁进时间轴窗口） */
export interface EmptySlot {
  roomId: number
  roomName: string
  /** yyyy-MM-dd */
  date: string
  /** 距 00:00 的分钟数 */
  startMinutes: number
  endMinutes: number
}

interface CalendarGridProps {
  rooms: Room[]
  /** 7 个日期（yyyy-MM-dd，周一 → 周日） */
  dates: string[]
  /** roomId → 该会议室本周 7 天日程（后端已补齐 7 天并按索引对齐） */
  daysByRoom: Map<number, CalendarDay[]>
  onEmptyClick: (slot: EmptySlot) => void
  onMeetingClick: (meeting: CalendarMeeting) => void
}

/** 距 00:00 的分钟数；跨天会议（结束在次日）按时间轴末端处理，这里只取时刻 */
function toMinutes(time: string): number {
  const t = dayjs(time)
  return t.hour() * 60 + t.minute()
}

function clampMinutes(minutes: number): number {
  return Math.min(Math.max(minutes, DAY_START_MINUTES), DAY_END_MINUTES)
}

/** 会议块的 top/height 百分比（裁剪到时间轴窗口内） */
function blockStyle(meeting: CalendarMeeting) {
  const start = clampMinutes(toMinutes(meeting.startTime))
  const end = dayjs(meeting.endTime).isSame(dayjs(meeting.startTime), 'day')
    ? clampMinutes(toMinutes(meeting.endTime))
    : DAY_END_MINUTES
  const top = ((start - DAY_START_MINUTES) / TOTAL_MINUTES) * 100
  const height = (Math.max(end - start, 0) / TOTAL_MINUTES) * 100
  return { top: `${top}%`, height: `${height}%` }
}

function MeetingBlock({ meeting, onClick }: { meeting: CalendarMeeting; onClick: (m: CalendarMeeting) => void }) {
  const cls = STATUS_BLOCK_CLASS[meeting.status] ?? FALLBACK_BLOCK_CLASS
  return (
    <div
      className={`absolute inset-x-0.5 overflow-hidden rounded border-l-2 px-1 text-[11px] leading-tight ${cls}`}
      style={blockStyle(meeting)}
      title={`${dayjs(meeting.startTime).format('HH:mm')}-${dayjs(meeting.endTime).format('HH:mm')} ${meeting.title}（${meeting.organiserName}）`}
      onClick={(e) => {
        e.stopPropagation() // 关键：点会议块不能冒泡成"点空白格 → 发起预约"
        onClick(meeting)
      }}
    >
      <div className="truncate font-medium">{meeting.title}</div>
      <div className="truncate opacity-70">{dayjs(meeting.startTime).format('HH:mm')}</div>
    </div>
  )
}

function DayCell({
  room,
  date,
  meetings,
  onEmptyClick,
  onMeetingClick,
}: {
  room: Room
  date: string
  meetings: CalendarMeeting[]
  onEmptyClick: (slot: EmptySlot) => void
  onMeetingClick: (meeting: CalendarMeeting) => void
}) {
  const handleEmptyClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const raw = DAY_START_MINUTES + ((e.clientY - rect.top) / rect.height) * TOTAL_MINUTES
    const snapped = Math.round(raw / SNAP_MINUTES) * SNAP_MINUTES
    const start = clampMinutes(snapped)
    onEmptyClick({
      roomId: room.id,
      roomName: room.name,
      date,
      startMinutes: start,
      endMinutes: Math.min(start + DEFAULT_DURATION_MINUTES, DAY_END_MINUTES),
    })
  }

  return (
    <div
      className="relative border-l border-gray-100 hover:bg-blue-50/50"
      style={{ height: CELL_HEIGHT }}
      onClick={handleEmptyClick}
    >
      {RULER_MINUTES.slice(1).map((m) => (
        <div
          key={m}
          className="pointer-events-none absolute inset-x-0 border-t border-dashed border-gray-100"
          style={{ top: `${((m - DAY_START_MINUTES) / TOTAL_MINUTES) * 100}%` }}
        />
      ))}
      {meetings.map((m) => (
        <MeetingBlock key={m.meetingId} meeting={m} onClick={onMeetingClick} />
      ))}
    </div>
  )
}

function RoomRowInner({
  room,
  dates,
  days,
  onEmptyClick,
  onMeetingClick,
}: {
  room: Room
  dates: string[]
  days: CalendarDay[]
  onEmptyClick: (slot: EmptySlot) => void
  onMeetingClick: (meeting: CalendarMeeting) => void
}) {
  return (
    <div className="grid items-stretch" style={{ gridTemplateColumns: COLUMNS }}>
      <div className="flex flex-col justify-center border-r border-gray-100 px-2">
        <div className="truncate text-sm font-medium">{room.name}</div>
        <div className="truncate text-xs text-gray-400">{room.location}</div>
      </div>
      {/* 时间刻度：每行各带一份，靠同一 time→% 映射天然对齐 */}
      <div className="relative border-r border-gray-100" style={{ height: CELL_HEIGHT }}>
        {RULER_MINUTES.map((m) => (
          <div
            key={m}
            className="absolute right-1 -translate-y-1/2 text-[10px] text-gray-400"
            style={{ top: `${((m - DAY_START_MINUTES) / TOTAL_MINUTES) * 100}%` }}
          >
            {String(m / 60).padStart(2, '0')}:00
          </div>
        ))}
      </div>
      {dates.map((date, i) => (
        <DayCell
          key={date}
          room={room}
          date={date}
          meetings={days[i]?.meetings ?? EMPTY_MEETINGS}
          onEmptyClick={onEmptyClick}
          onMeetingClick={onMeetingClick}
        />
      ))}
    </div>
  )
}

/** 行级组件化：数据未变的行在切周刷新/父组件重渲染时直接跳过 */
const RoomRow = memo(RoomRowInner)

function CalendarGridInner({ rooms, dates, daysByRoom, onEmptyClick, onMeetingClick }: CalendarGridProps) {
  return (
    <div className="overflow-x-auto">
      <div className="min-w-[860px] rounded border border-gray-200 bg-white">
        {/* 表头：周几 + 日期 */}
        <div className="grid border-b border-gray-200 bg-gray-50" style={{ gridTemplateColumns: COLUMNS }}>
          <div className="px-2 py-2 text-xs font-medium text-gray-500">会议室</div>
          <div className="px-2 py-2 text-xs font-medium text-gray-500">时间</div>
          {dates.map((date) => {
            const d = dayjs(date)
            const isToday = d.isSame(dayjs(), 'day')
            return (
              <div key={date} className="border-l border-gray-100 px-2 py-2 text-center">
                <div className={`text-xs ${isToday ? 'font-medium text-blue-600' : 'text-gray-500'}`}>
                  周{'日一二三四五六'[d.day()]}
                </div>
                <div className={`text-xs ${isToday ? 'text-blue-600' : 'text-gray-400'}`}>{d.format('MM-DD')}</div>
              </div>
            )
          })}
        </div>
        {rooms.length === 0 ? (
          <div className="py-10 text-center text-sm text-gray-400">暂无可用会议室</div>
        ) : (
          rooms.map((room) => (
            <RoomRow
              key={room.id}
              room={room}
              dates={dates}
              days={daysByRoom.get(room.id) ?? EMPTY_DAYS}
              onEmptyClick={onEmptyClick}
              onMeetingClick={onMeetingClick}
            />
          ))
        )}
      </div>
    </div>
  )
}

export const CalendarGrid = memo(CalendarGridInner)
