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
import MeetingStatusTag, { MEETING_STATUS_META } from '@/components/MeetingStatusTag'
import { useAppRequest } from '@/hooks/useAppRequest'

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
  const [statuses, setStatuses] = useState<number[]>([])
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)

  const statusParam = statuses.join(',')
  const queryKey = `${statusParam}|${keyword}|${page}`

  // 这里**刻意不做**"响应键比对"守卫：ahooks 的 Fetch 自带 count 守卫，过期响应本就不会覆盖新结果，
  // 再比对一遍只会让翻页/改筛选的瞬间表格闪成"暂无数据 + 共 0 条"（评审 R9）。
  // 保留上一条数据、由 Table 的 loading 遮罩过渡，才是列表该有的 keepPreviousData 语义。
  const { data, loading } = useAppRequest<ListPayload>(
    async () => {
      const result = await listMeetings({
        scope: 'mine',
        status: statusParam || undefined,
        keyword: keyword || undefined,
        page,
        size: PAGE_SIZE,
      })
      return { list: result.list, total: result.total }
    },
    { manual: false, refreshDeps: [queryKey] },
  )

  // 关键字防抖 500ms（与项目其他输入型筛选一致），并回到第 1 页
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
          <Button
            type="link"
            size="small"
            // 阻止冒泡：否则与行 onClick 在同一 tick 内 push 两次同一 URL，历史栈多一条重复记录，
            // 详情页按浏览器后退会"第一次没反应"（评审 R13）
            onClick={(e) => {
              e.stopPropagation()
              navigate(`/meetings/${r.id}`)
            }}
          >
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
        dataSource={data?.list ?? []}
        loading={loading}
        onRow={(record) => ({ onClick: () => navigate(`/meetings/${record.id}`) })}
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
    </Card>
  )
}
