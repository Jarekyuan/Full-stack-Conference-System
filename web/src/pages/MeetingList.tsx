/**
 * src/pages/MeetingList.tsx
 * 我的会议列表（docs/02 4.1 /meetings）：状态 + 关键字筛选、分页、状态色标签，点行进详情。
 * 数据：`GET /meetings?scope=mine`（"我组织的或我参与的"），返回已带 roomName/organiserName
 * 等展示字段（后端 VO 按场景裁剪，前端不再二次查询）。
 * 竞态处理：列表返回体带"本次查询的键"，与当前筛选不一致的过期响应不渲染（与日历页同一套守卫）。
 */
import { useCallback, useMemo, useState } from 'react'
import { Button, Card, Input, Select, Space, Table, type TableColumnsType } from 'antd'
import { useDebounceFn } from 'ahooks'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { listMeetings, type MeetingListItem } from '@/api/meeting'
import MeetingStatusTag from '@/components/MeetingStatusTag'
import { useAppRequest } from '@/hooks/useAppRequest'

const PAGE_SIZE = 10
/** 状态筛选项（照 docs/04 5.1 的六个状态） */
const STATUS_OPTIONS = [
  { value: 10, label: '待审批' },
  { value: 20, label: '已预约' },
  { value: 30, label: '进行中' },
  { value: 40, label: '已结束' },
  { value: 50, label: '已取消' },
  { value: 60, label: '已拒绝' },
]

interface ListPayload {
  /** 本次请求的查询键 —— 渲染前与当前筛选比对，充当竞态守卫 */
  key: string
  list: MeetingListItem[]
  total: number
}

export default function MeetingList() {
  const navigate = useNavigate()
  const [statuses, setStatuses] = useState<number[]>([])
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)

  const statusParam = statuses.join(',')
  const queryKey = `${statusParam}|${keyword}|${page}`

  const { data, loading } = useAppRequest<ListPayload>(
    async () => {
      const result = await listMeetings({
        scope: 'mine',
        status: statusParam || undefined,
        keyword: keyword || undefined,
        page,
        size: PAGE_SIZE,
      })
      return { key: queryKey, list: result.list, total: result.total }
    },
    { manual: false, refreshDeps: [queryKey] },
  )

  const payload = data && data.key === queryKey ? data : undefined

  // 关键字防抖 500ms（与项目其他输入型筛选保持一致），并回到第 1 页
  const { run: applyKeyword } = useDebounceFn((value: string) => {
    setKeyword(value.trim())
    setPage(1)
  }, { wait: 500 })

  const columns: TableColumnsType<MeetingListItem> = useMemo(
    () => [
      { title: '会议主题', dataIndex: 'title', ellipsis: true },
      { title: '会议室', dataIndex: 'roomName', width: 120 },
      {
        title: '会议时间',
        width: 210,
        render: (_, r) => `${dayjs(r.startTime).format('MM-DD HH:mm')} ~ ${dayjs(r.endTime).format('HH:mm')}`,
      },
      { title: '状态', width: 100, render: (_, r) => <MeetingStatusTag status={r.status} /> },
      { title: '参会人', dataIndex: 'participantCount', width: 80 },
      {
        title: '操作',
        width: 90,
        render: (_, r) => (
          <Button type="link" size="small" onClick={() => navigate(`/meetings/${r.id}`)}>
            详情
          </Button>
        ),
      },
    ],
    [navigate],
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
        dataSource={payload?.list ?? []}
        loading={loading}
        onRow={(record) => ({ onClick: () => navigate(`/meetings/${record.id}`) })}
        rowClassName="cursor-pointer"
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total: payload?.total ?? 0,
          showSizeChanger: false,
          showTotal: (total) => `共 ${total} 条`,
          onChange: setPage,
        }}
      />
    </Card>
  )
}
