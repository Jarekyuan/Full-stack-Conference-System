/**
 * src/pages/MeetingList.tsx
 * 我的会议列表（docs/02 4.1 /meetings）：状态 / 会议室 / 会议日期 / 关键字筛选，分页，
 * 行内“详情 / 编辑 / 取消”（T6.2），点行进详情。
 *
 * 数据：`GET /meetings?scope=mine`（"我组织的或我参与的"），返回已带 roomName/organiserName
 * 等展示字段（后端 VO 按场景裁剪，前端不再二次查询）。
 *
 * 操作列的状态门槛与详情页一致（真正的裁决仍在服务端）：
 *   · 编辑：组织人 + 20 已预约（R-12；改到需审批会议室会回退到 10）
 *   · 取消：组织人 + 10/20（30 进行中禁止；R-6 取消截止由服务端判）
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, DatePicker, Input, Select, Space, Table, type TableColumnsType } from 'antd'
import { useDebounceFn } from 'ahooks'
import { useNavigate } from 'react-router-dom'
import dayjs, { type Dayjs } from 'dayjs'
import { listMeetings, type MeetingListItem } from '@/api/meeting'
import { listRooms } from '@/api/room'
import MeetingStatusTag, { MEETING_STATUS_META } from '@/components/MeetingStatusTag'
import CancelMeetingModal from '@/components/CancelMeetingModal'
import MeetingEditDrawer from '@/components/MeetingEditDrawer'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'

const PAGE_SIZE = 10
/** 状态筛选项：从 MeetingStatusTag 的元数据派生，状态文案只维护一处 */
const STATUS_OPTIONS = Object.entries(MEETING_STATUS_META).map(([value, meta]) => ({
  value: Number(value),
  label: meta.text,
}))

interface ListPayload {
  list: MeetingListItem[]
  total: number
}

export default function MeetingList() {
  const navigate = useNavigate()
  const currentUserId = useUserStore((s) => s.user?.id)
  const [statuses, setStatuses] = useState<number[]>([])
  const [keyword, setKeyword] = useState('')
  const [roomId, setRoomId] = useState<number | undefined>(undefined)
  const [dateRange, setDateRange] = useState<[Dayjs, Dayjs] | null>(null)
  const [page, setPage] = useState(1)
  /** 编辑/取消成功后靠它触发重查（与筛选条件一起进入 queryKey） */
  const [refreshKey, setRefreshKey] = useState(0)
  const [editId, setEditId] = useState<number | null>(null)
  const [cancelId, setCancelId] = useState<number | null>(null)

  const statusParam = statuses.join(',')
  const startDate = dateRange?.[0]?.format('YYYY-MM-DD')
  const endDate = dateRange?.[1]?.format('YYYY-MM-DD')
  const queryKey = `${statusParam}|${keyword}|${roomId ?? ''}|${startDate ?? ''}|${endDate ?? ''}|${page}|${refreshKey}`

  // 这里**刻意不做**"响应键比对"守卫：ahooks 的 Fetch 自带 count 守卫，过期响应本就不会覆盖新结果，
  // 再比对一遍只会让翻页/改筛选的瞬间表格闪成"暂无数据 + 共 0 条"（评审 R9）。
  // 保留上一条数据、由 Table 的 loading 遮罩过渡，才是列表该有的 keepPreviousData 语义。
  const { data, loading } = useAppRequest<ListPayload>(
    async () => {
      const result = await listMeetings({
        scope: 'mine',
        status: statusParam || undefined,
        roomId,
        startDate,
        endDate,
        keyword: keyword || undefined,
        page,
        size: PAGE_SIZE,
      })
      return { list: result.list, total: result.total }
    },
    { manual: false, refreshDeps: [queryKey] },
  )

  // 取消/编辑把当前页最后一条移走时，total 可能缩到当前页之外——自动回退，避免停在空页
  useEffect(() => {
    if (!data) return
    const maxPage = Math.max(1, Math.ceil(data.total / PAGE_SIZE))
    if (page > maxPage) setPage(maxPage)
  }, [data, page])

  // 会议室下拉：**包含维护中的房间**——历史会议仍要能按房间筛出来
  const { data: rooms } = useAppRequest(() => listRooms(), { manual: false })
  const roomOptions = (rooms ?? []).map((room) => ({
    value: room.id,
    label: `${room.name}${room.status === 1 ? '（维护中）' : ''}`,
  }))

  // 关键字防抖 500ms（与项目其他输入型筛选一致），并回到第 1 页
  const { run: applyKeyword } = useDebounceFn((value: string) => {
    setKeyword(value.trim())
    setPage(1)
  }, { wait: 500 })

  const refreshList = useCallback(() => setRefreshKey((k) => k + 1), [])

  const columns: TableColumnsType<MeetingListItem> = useMemo(
    () => [
      // T6.2：主题列定宽，避免长标题把整张表撑得特别长（超出省略，悬浮看全文）
      { title: '会议主题', dataIndex: 'title', width: 240, ellipsis: { showTitle: true } },
      { title: '会议室', dataIndex: 'roomName', width: 120 },
      {
        title: '会议时间',
        width: 330,
        // 用户明确要“起止都带完整日期”：2026-10-15 16:15 到 2026-10-15 16:30
        render: (_, r) =>
          `${dayjs(r.startTime).format('YYYY-MM-DD HH:mm')} 到 ${dayjs(r.endTime).format('YYYY-MM-DD HH:mm')}`,
      },
      { title: '状态', width: 100, render: (_, r) => <MeetingStatusTag status={r.status} /> },
      { title: '参会人', dataIndex: 'participantCount', width: 80 },
      {
        title: '操作',
        width: 200,
        render: (_, r) => {
          const isOrganiser = r.organiserId === currentUserId
          const canEdit = isOrganiser && r.status === 20
          const canCancel = isOrganiser && (r.status === 10 || r.status === 20)
          return (
            <Space size={0}>
              <Button
                type="link"
                size="small"
                // 阻止冒泡：否则与行 onClick 在同一 tick 内 push 两次同一 URL，历史栈多一条重复记录
                onClick={(e) => {
                  e.stopPropagation()
                  navigate(`/meetings/${r.id}`)
                }}
              >
                详情
              </Button>
              {canEdit && (
                <Button type="link" size="small" onClick={(e) => { e.stopPropagation(); setEditId(r.id) }}>
                  编辑
                </Button>
              )}
              {canCancel && (
                <Button
                  type="link"
                  size="small"
                  danger
                  onClick={(e) => { e.stopPropagation(); setCancelId(r.id) }}
                >
                  取消
                </Button>
              )}
            </Space>
          )
        },
      },
    ],
    [navigate, currentUserId],
  )

  const handleStatusChange = useCallback((values: number[]) => {
    setStatuses(values)
    setPage(1) // 换筛选条件必须回到第一页，否则可能停在超出范围的页码上
  }, [])

  return (
    <Card title="我的会议">
      <Space className="mb-4" wrap>
        <Select
          mode="multiple"
          allowClear
          placeholder="按状态筛选"
          style={{ minWidth: 220 }}
          options={STATUS_OPTIONS}
          value={statuses}
          onChange={handleStatusChange}
        />
        <Select
          allowClear
          placeholder="按会议室筛选"
          style={{ width: 180 }}
          options={roomOptions}
          value={roomId}
          onChange={(v) => {
            setRoomId(v ?? undefined)
            setPage(1)
          }}
        />
        <DatePicker.RangePicker
          value={dateRange}
          allowClear
          format="YYYY-MM-DD"
          placeholder={['会议日期起', '会议日期止']}
          onChange={(v) => {
            if (v && v[0] && v[1]) {
              setDateRange([v[0], v[1]])
            } else {
              setDateRange(null)
            }
            setPage(1)
          }}
        />
        <Input.Search
          allowClear
          placeholder="搜索会议主题"
          style={{ width: 220 }}
          onChange={(e) => applyKeyword(e.target.value)}
        />
      </Space>

      <Table<MeetingListItem>
        rowKey="id"
        columns={columns}
        dataSource={data?.list ?? []}
        loading={loading}
        scroll={{ x: 1100 }}
        onRow={(record) => ({
          onClick: () => navigate(`/meetings/${record.id}`),
          // O21：键盘用户也能进详情（行内按钮仍走自己的键盘事件）
          tabIndex: 0,
          onKeyDown: (e) => {
            // 行内按钮/链接自己的 Enter/Space 不触发整行跳转
            if (e.target !== e.currentTarget) return
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              navigate(`/meetings/${record.id}`)
            }
          },
        })}
        rowClassName="cursor-pointer"
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total: data?.total ?? 0,
          showSizeChanger: false,
          showTotal: (total) => `共 ${total} 条`,
          onChange: setPage,
        }}
      />

      <MeetingEditDrawer
        open={editId !== null}
        meetingId={editId}
        onClose={() => setEditId(null)}
        onSuccess={refreshList}
      />
      <CancelMeetingModal
        open={cancelId !== null}
        meetingId={cancelId}
        onClose={() => setCancelId(null)}
        onSuccess={refreshList}
      />
    </Card>
  )
}
