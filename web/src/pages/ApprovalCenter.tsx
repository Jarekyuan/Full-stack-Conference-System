/**
 * src/pages/ApprovalCenter.tsx
 * 审批中心（docs/02 4.1 /approvals，仅 APPROVER/ADMIN 可见）：两个 Tab——待审批 / 已处理。
 *
 * 三个要点：
 * ① **审批失败不是异常，是设计内的业务分支**：待审批不占时段（R-4），所以审批通过时要复检，
 *    撞上冲突回 50001 → 弹冲突详情 + 提示"请拒绝并让申请人改期"，而不是当成系统错误。
 * ② 入口只对 APPROVER/ADMIN 显示（MainLayout 的菜单 + router 的 RequireRole 守卫），
 *    但**裁决在服务端**（@PreAuthorize）：绕过前端直接调接口只会拿到 403。
 * ③ 拒绝对 body 的 comment 有必填要求（后端 10001），抽屉里先拦一道，省一次往返。
 */
import { useCallback, useMemo, useState } from 'react'
import { Button, Card, Modal, Table, Tabs, Tag, type TableColumnsType } from 'antd'
import dayjs from 'dayjs'
import { listHistory, listPending, type ApprovalHistoryItem, type ApprovalPendingItem } from '@/api/approval'
import type { MeetingConflict } from '@/api/meeting'
import ApprovalDrawer from '@/components/ApprovalDrawer'
import MeetingStatusTag from '@/components/MeetingStatusTag'
import { useAppRequest } from '@/hooks/useAppRequest'

const PAGE_SIZE = 10

export default function ApprovalCenter() {
  const [tab, setTab] = useState<'pending' | 'history'>('pending')
  const [page, setPage] = useState(1)
  /** 正在审批的待办项（抽屉）；null = 抽屉关闭 */
  const [active, setActive] = useState<ApprovalPendingItem | null>(null)
  /** 50001 复检冲突详情 */
  const [conflict, setConflict] = useState<MeetingConflict[] | null>(null)
  /** 审批动作完成后的刷新信号（列表是"一次性取回"的，刷新靠改依赖触发重查） */
  const [refreshKey, setRefreshKey] = useState(0)

  const pending = useAppRequest(() => listPending(page, PAGE_SIZE), {
    manual: false,
    ready: tab === 'pending',
    refreshDeps: [page, tab, refreshKey],
  })
  const history = useAppRequest(() => listHistory(page, PAGE_SIZE), {
    manual: false,
    ready: tab === 'history',
    refreshDeps: [page, tab, refreshKey],
  })

  const handleTabChange = useCallback((key: string) => {
    setTab(key as 'pending' | 'history')
    setPage(1) // 换 Tab 必须回第一页，否则可能停在超出范围的页码上
  }, [])

  /** 审批动作闭环：关抽屉 → 刷新列表（抽屉自己负责调接口与错误处理） */
  const handleDone = useCallback(() => {
    setActive(null)
    setRefreshKey((k) => k + 1)
  }, [])

  /** 复检冲突：关抽屉 + 刷新 + 弹详情（该申请仍是 10，需要审批人拒绝后让申请人改期） */
  const handleConflict = useCallback((conflicts: MeetingConflict[]) => {
    setActive(null)
    setRefreshKey((k) => k + 1)
    setConflict(conflicts)
  }, [])

  const pendingColumns: TableColumnsType<ApprovalPendingItem> = useMemo(
    () => [
      { title: '会议主题', dataIndex: 'title', ellipsis: true },
      { title: '申请人', dataIndex: 'organiserName', width: 100 },
      { title: '会议室', dataIndex: 'roomName', width: 110 },
      {
        title: '会议时间',
        width: 210,
        render: (_, r) => `${dayjs(r.startTime).format('MM-DD HH:mm')} ~ ${dayjs(r.endTime).format('HH:mm')}`,
      },
      { title: '用途', dataIndex: 'remark', ellipsis: true, render: (v: string | null) => v || '（未填）' },
      {
        title: '操作',
        width: 90,
        render: (_, r) => (
          <Button
            type="primary"
            size="small"
            onClick={(e) => {
              e.stopPropagation() // 别和行点击（开抽屉）撞在一起——同一次点击触发两次会出两个抽屉
              setActive(r)
            }}
          >
            审批
          </Button>
        ),
      },
    ],
    [],
  )

  const historyColumns: TableColumnsType<ApprovalHistoryItem> = useMemo(
    () => [
      { title: '会议主题', dataIndex: 'title', ellipsis: true },
      { title: '申请人', dataIndex: 'organiserName', width: 100 },
      { title: '会议室', dataIndex: 'roomName', width: 110 },
      {
        title: '会议时间',
        width: 210,
        render: (_, r) => `${dayjs(r.startTime).format('MM-DD HH:mm')} ~ ${dayjs(r.endTime).format('HH:mm')}`,
      },
      {
        title: '我的处理',
        width: 100,
        render: (_, r) =>
          r.action === 'PASS' ? <Tag color="green">已通过</Tag> : <Tag color="red">已拒绝</Tag>,
      },
      {
        title: '会议现状',
        width: 100,
        // 处理时的结论 ≠ 会议现在什么样：通过之后还可能被取消/结束（后端特意回了 meetingStatus）
        render: (_, r) => <MeetingStatusTag status={r.meetingStatus} />,
      },
      { title: '审批意见', dataIndex: 'comment', ellipsis: true, render: (v: string | null) => v || '（无）' },
      {
        title: '处理时间',
        width: 150,
        render: (_, r) => dayjs(r.handledAt).format('YYYY-MM-DD HH:mm'),
      },
    ],
    [],
  )

  return (
    <Card title="审批中心">
      <Tabs
        activeKey={tab}
        onChange={handleTabChange}
        items={[
          {
            key: 'pending',
            label: `待审批${pending.data?.total ? `（${pending.data.total}）` : ''}`,
            children: (
              <Table<ApprovalPendingItem>
                rowKey="meetingId"
                columns={pendingColumns}
                dataSource={pending.data?.list ?? []}
                loading={pending.loading}
                onRow={(record) => ({ onClick: () => setActive(record) })}
                rowClassName="cursor-pointer"
                pagination={{
                  current: page,
                  pageSize: PAGE_SIZE,
                  total: pending.data?.total ?? 0,
                  showSizeChanger: false,
                  showTotal: (total) => `共 ${total} 条`,
                  onChange: setPage,
                }}
              />
            ),
          },
          {
            key: 'history',
            label: '已处理',
            children: (
              <Table<ApprovalHistoryItem>
                rowKey="approvalId"
                columns={historyColumns}
                dataSource={history.data?.list ?? []}
                loading={history.loading}
                pagination={{
                  current: page,
                  pageSize: PAGE_SIZE,
                  total: history.data?.total ?? 0,
                  showSizeChanger: false,
                  showTotal: (total) => `共 ${total} 条`,
                  onChange: setPage,
                }}
              />
            ),
          },
        ]}
      />

      <ApprovalDrawer item={active} onClose={() => setActive(null)} onDone={handleDone} onConflict={handleConflict} />

      <Modal
        title="该时段已被占用，无法通过审批"
        open={!!conflict}
        onCancel={() => setConflict(null)}
        onOk={() => setConflict(null)}
        okText="知道了"
        cancelButtonProps={{ style: { display: 'none' } }}
      >
        <p>待审批期间这个时段被别人订走了。请拒绝该申请（申请人可改期后重新提交）：</p>
        {conflict?.map((c) => (
          <div key={c.meetingId} className="py-0.5">
            <b>{c.title}</b>　{c.startTime.slice(11, 16)}-{c.endTime.slice(11, 16)}　{c.organiserName}
          </div>
        ))}
      </Modal>
    </Card>
  )
}
