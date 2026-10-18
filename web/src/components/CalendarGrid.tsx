/**
 * src/components/CalendarGrid.tsx
 * 日历网格（02 文档 4.2 核心页面）：行 = 会议室，列 = 7 天；
 * 每格内一条 08:00–20:00 时间轴，会议块按 start/end 百分比定位（块高 = 时长）。
 *
 * T6.1 的六个展示/交互改动：
 * ① 行高 240→360、每小时网格线、每 2 小时刻度；首末刻度贴边，不再跨行重叠；
 * ② 过去时段盖灰罩 + 点击兜底拦截（吸附可能把边界点成过去）；
 * ③ 会议块按“关系 + 状态”四色（calendarAppearance），并分泳道避免重叠（calendarLayout）；
 * ④ 块内固定两行：时间+主题 / 预订人；待审批额外提示（审批人显示“待你审批”）；
 * ⑤ 会议室悬浮卡展示全部信息（含是否需审批）；
 * ⑥ 空状态文案可配置（会议室关键字过滤后无匹配时用）。
 *
 * 交互不变：点空格 → 按点击高度反推时间（吸附 30 分钟，默认 1 小时）上抛 onEmptyClick；
 * 点会议块 → 上抛 onMeetingClick（阻止冒泡）；空集合用模块级常量保引用稳定。
 */
import { memo } from 'react'
import dayjs from 'dayjs'
import { Divider, Popover, Tag } from 'antd'
import type { Room } from '@/api/room'
import type { CalendarDay, CalendarMeeting } from '@/api/meeting'
import { calendarCategory, CALENDAR_CATEGORY_META } from '@/components/calendarAppearance'
import { layoutMeetings, type MeetingLane } from '@/components/calendarLayout'
import {
  blockGeometry,
  DAY_END_MINUTES,
  DAY_START_MINUTES,
  pastRatio,
  slotFromClickRatio,
  TOTAL_MINUTES,
} from '@/components/calendarGeometry'

/** 单元格高度（px）——各行等高，时间轴比例因此一致；360 让 30 分钟块也能放下两行文字 */
const CELL_HEIGHT = 360
/** 刻度：每 2 小时一个标签（08/10/12/14/16/18/20） */
const RULER_MINUTES = Array.from(
  { length: (DAY_END_MINUTES - DAY_START_MINUTES) / 120 + 1 },
  (_, i) => DAY_START_MINUTES + i * 120,
)
/** 小时网格线：09:00–19:00（08/20 是行的上下边界，不重复画） */
const HOUR_LINES = Array.from({ length: 11 }, (_, i) => DAY_START_MINUTES + (i + 1) * 60)
/** 列模板：会议室名 | 时间刻度 | 7 天 */
const COLUMNS = '140px 56px repeat(7, minmax(104px, 1fr))'

const EMPTY_DAYS: CalendarDay[] = []
const EMPTY_MEETINGS: CalendarMeeting[] = []

const pct = (minutes: number) => `${((minutes - DAY_START_MINUTES) / TOTAL_MINUTES) * 100}%`

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
  /** 这些房间的日历加载失败：行标“加载失败”，空格不可点（O4） */
  failedRoomIds?: number[]
  currentUserId?: number
  /** 审批人（APPROVER/ADMIN）：待审批块显示“待你审批”而不是“待审批” */
  canApprove?: boolean
  emptyText?: string
  onEmptyClick: (slot: EmptySlot) => void
  onMeetingClick: (meeting: CalendarMeeting) => void
}

/** 会议室悬浮卡：RoomVO 的全部字段（含预约策略），“待审批提示”的另一半在这里 */
function RoomInfoCard({ room }: { room: Room }) {
  return (
    <div className="w-72 space-y-2 text-xs">
      <div className="flex items-center gap-2 text-sm font-medium">
        <span>{room.name}</span>
        <Tag color={room.status === 0 ? 'green' : 'default'} className="m-0">
          {room.status === 0 ? '可用' : '维护中'}
        </Tag>
      </div>
      <div>分组：{room.groupName}</div>
      <div>容量：{room.capacity} 人</div>
      <div>位置：{room.location || '—'}</div>
      <div>
        设备：
        {(room.deviceTags ?? []).length > 0
          ? (room.deviceTags ?? []).map((tag) => (
              <Tag key={tag} className="mr-1">
                {tag}
              </Tag>
            ))
          : '—'}
      </div>
      <Divider className="my-2" />
      <div className="flex flex-wrap gap-1">
        <Tag color={room.needApproval === 1 ? 'orange' : 'green'}>
          {room.needApproval === 1 ? '需审批' : '免审批'}
        </Tag>
        <Tag>提前 {room.advanceDays} 天</Tag>
        <Tag>单场 ≤ {room.maxDurationMinutes} 分钟</Tag>
        <Tag>取消截止 {room.cancelDeadlineMinutes} 分钟</Tag>
        <Tag>缓冲 {room.bufferMinutes} 分钟</Tag>
        <Tag>{room.allowRecurring === 1 ? '支持周期' : '不支持周期'}</Tag>
      </div>
      {(room.images ?? []).length > 0 && (
        <div className="flex gap-1 pt-1">
          {(room.images ?? []).slice(0, 3).map((src) => (
            <img key={src} src={src} alt="" className="h-12 w-16 rounded object-cover" />
          ))}
        </div>
      )}
    </div>
  )
}

function MeetingBlock({
  meeting,
  lane,
  currentUserId,
  canApprove,
  onClick,
}: {
  meeting: CalendarMeeting
  lane?: MeetingLane
  currentUserId?: number
  canApprove?: boolean
  onClick: (m: CalendarMeeting) => void
}) {
  const { category, expired } = calendarCategory(meeting, currentUserId)
  const meta = CALENDAR_CATEGORY_META[category]
  const { style } = blockGeometry(meeting)
  const startText = dayjs(meeting.startTime).format('HH:mm')
  const endText = dayjs(meeting.endTime).format('HH:mm')
  const pendingText = meeting.status === 10 ? (canApprove ? '待你审批' : '待审批') : null

  // 泳道：只有重叠簇（lanes > 1）才均分宽度；单条时留 2px 缝隙即可
  const laneStyle =
    lane && lane.lanes > 1
      ? {
          left: `calc(${(lane.lane / lane.lanes) * 100}% + 1px)`,
          width: `calc(${(1 / lane.lanes) * 100}% - 2px)`,
        }
      : { left: '2px', right: '2px' }

  return (
    <div
      data-testid={`meeting-block-${meeting.meetingId}`}
      data-category={category}
      className={`absolute cursor-pointer overflow-hidden rounded border-l-2 px-1 text-[11px] leading-tight ${meta.blockClass} ${
        expired ? 'opacity-60' : ''
      }`}
      style={{ ...style, ...laneStyle }}
      title={`${startText}-${endText} ${meeting.title}（预订人：${meeting.organiserName}）${
        pendingText ? ` · ${pendingText}` : ''
      }`}
      onClick={(e) => {
        e.stopPropagation() // 关键：点会议块不能冒泡成“点空白格 → 发起预约”
        onClick(meeting)
      }}
    >
      <div className="truncate font-medium">
        {startText} {meeting.title}
      </div>
      {/* 第二行固定展示预订人（T6.1）；1 小时块在 360px 行高下也有约 30px，两行足够 */}
      <div className="truncate opacity-80">预订人：{meeting.organiserName}</div>
      {pendingText && <div className="truncate font-semibold">{pendingText}</div>}
    </div>
  )
}

function DayCell({
  room,
  date,
  meetings,
  currentUserId,
  canApprove,
  failed,
  onEmptyClick,
  onMeetingClick,
}: {
  room: Room
  date: string
  meetings: CalendarMeeting[]
  currentUserId?: number
  canApprove?: boolean
  /** 房间日历加载失败：禁用空格点击，避免基于“空数据”发起预约 */
  failed?: boolean
  onEmptyClick: (slot: EmptySlot) => void
  onMeetingClick: (meeting: CalendarMeeting) => void
}) {
  const ratio = pastRatio(date)
  const lanes = layoutMeetings(meetings)

  const handleEmptyClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const slot = slotFromClickRatio((e.clientY - rect.top) / rect.height)
    // 兜底拦截：灰罩只负责“看起来不可点”，真正的规则在这里——吸附可能把边界点成过去
    const start = dayjs(date).add(slot.startMinutes, 'minute')
    if (!start.isAfter(dayjs())) {
      return
    }
    onEmptyClick({
      roomId: room.id,
      roomName: room.name,
      date,
      ...slot,
    })
  }

  return (
    <div
      data-testid={`day-cell-${room.id}-${date}`}
      className={`relative border-l border-gray-100 dark:border-slate-800 ${ratio >= 1 ? 'bg-gray-50 dark:bg-slate-950' : 'hover:bg-blue-50/40 dark:hover:bg-blue-950/30'}`}
      style={{ height: CELL_HEIGHT }}
      onClick={failed ? undefined : handleEmptyClick}
    >
      {HOUR_LINES.map((m) => (
        <div
          key={m}
          className="pointer-events-none absolute inset-x-0 border-t border-gray-100 dark:border-slate-800"
          style={{ top: pct(m) }}
        />
      ))}
      {ratio > 0 && (
        <div
          data-testid={`past-overlay-${room.id}-${date}`}
          className="pointer-events-none absolute inset-x-0 top-0 bg-gray-100/80 dark:bg-slate-950/80"
          style={{ height: `${ratio * 100}%` }}
        />
      )}
      {meetings.map((m) => (
        <MeetingBlock
          key={m.meetingId}
          meeting={m}
          lane={lanes.get(m.meetingId)}
          currentUserId={currentUserId}
          canApprove={canApprove}
          onClick={onMeetingClick}
        />
      ))}
    </div>
  )
}

/**
 * 时间轴：每行一份，靠同一 time→% 映射天然对齐。
 * 只有**最后一行**显示 20:00：否则每行底的 20:00 会和下一行顶的 08:00 贴着行边界叠在一起
 * （2026-10-17 截图走查发现，正是用户说的“左侧时间轴重合”）。
 */
function TimeAxis({ showEnd = false }: { showEnd?: boolean }) {
  const labels = showEnd ? RULER_MINUTES : RULER_MINUTES.filter((m) => m < DAY_END_MINUTES)
  return (
    <div className="relative border-r border-gray-100 dark:border-slate-800" style={{ height: CELL_HEIGHT }}>
      {labels.map((m, index) => {
        const isFirst = index === 0
        const isLast = index === labels.length - 1
        return (
          <div
            key={m}
            className="absolute right-1 text-[10px] leading-none text-gray-400 dark:text-slate-400"
            style={{
              top: pct(m),
              transform: isFirst ? undefined : isLast && showEnd ? 'translateY(-100%)' : 'translateY(-50%)',
            }}
          >
            {String(m / 60).padStart(2, '0')}:00
          </div>
        )
      })}
    </div>
  )
}

function RoomRowInner({
  room,
  dates,
  days,
  currentUserId,
  canApprove,
  isLastRoom,
  failed,
  onEmptyClick,
  onMeetingClick,
}: {
  room: Room
  dates: string[]
  days: CalendarDay[]
  currentUserId?: number
  canApprove?: boolean
  /** 最后一行才显示 20:00（避免与下一行 08:00 在行边界重叠） */
  isLastRoom?: boolean
  /** 该房间日历加载失败：禁点空格，避免在“看起来空”的格子上发起预约 */
  failed?: boolean
  onEmptyClick: (slot: EmptySlot) => void
  onMeetingClick: (meeting: CalendarMeeting) => void
}) {
  return (
    <div className="grid items-stretch" style={{ gridTemplateColumns: COLUMNS }} data-testid={`room-row-${room.id}`}>
      <Popover content={<RoomInfoCard room={room} />} placement="right" mouseEnterDelay={0.15}>
        <div className="flex cursor-help flex-col justify-center border-r border-gray-100 dark:border-slate-800 px-2">
          <div className="truncate text-sm font-medium">{room.name}</div>
          <div className="truncate text-xs text-gray-400 dark:text-slate-400">
            {failed ? <span className="text-red-500 dark:text-red-400">日程加载失败</span> : room.location}
          </div>
        </div>
      </Popover>
      <TimeAxis showEnd={isLastRoom} />
      {dates.map((date, i) => (
        <DayCell
          key={date}
          room={room}
          date={date}
          meetings={days[i]?.meetings ?? EMPTY_MEETINGS}
          currentUserId={currentUserId}
          canApprove={canApprove}
          failed={failed}
          onEmptyClick={onEmptyClick}
          onMeetingClick={onMeetingClick}
        />
      ))}
    </div>
  )
}

/** 行级组件化：数据未变的行在切周刷新/父组件重渲染时直接跳过 */
const RoomRow = memo(RoomRowInner)

function CalendarGridInner({
  rooms,
  dates,
  daysByRoom,
  failedRoomIds,
  currentUserId,
  canApprove,
  emptyText,
  onEmptyClick,
  onMeetingClick,
}: CalendarGridProps) {
  return (
    <div className="overflow-x-auto">
      <div className="min-w-[900px] rounded border border-gray-200 dark:border-slate-700 bg-white dark:bg-slate-900">
        {/* 表头：周几 + 日期 */}
        <div className="grid border-b border-gray-200 dark:border-slate-700 bg-gray-50 dark:bg-slate-800" style={{ gridTemplateColumns: COLUMNS }}>
          <div className="px-2 py-2 text-xs font-medium text-gray-500 dark:text-slate-400">会议室</div>
          <div className="px-2 py-2 text-xs font-medium text-gray-500 dark:text-slate-400">时间</div>
          {dates.map((date) => {
            const d = dayjs(date)
            const isToday = d.isSame(dayjs(), 'day')
            return (
              <div key={date} className="border-l border-gray-100 dark:border-slate-800 px-2 py-2 text-center">
                <div className={`text-xs ${isToday ? 'font-medium text-blue-600 dark:text-blue-400' : 'text-gray-500 dark:text-slate-400'}`}>
                  周{'日一二三四五六'[d.day()]}
                </div>
                <div className={`text-xs ${isToday ? 'text-blue-600 dark:text-blue-400' : 'text-gray-400 dark:text-slate-400'}`}>{d.format('MM-DD')}</div>
              </div>
            )
          })}
        </div>
        {rooms.length === 0 ? (
          <div className="py-10 text-center text-sm text-gray-400 dark:text-slate-400">{emptyText ?? '暂无可用会议室'}</div>
        ) : (
          rooms.map((room, index) => (
            <RoomRow
              key={room.id}
              room={room}
              dates={dates}
              days={daysByRoom.get(room.id) ?? EMPTY_DAYS}
              currentUserId={currentUserId}
              canApprove={canApprove}
              isLastRoom={index === rooms.length - 1}
              failed={failedRoomIds?.includes(room.id)}
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
