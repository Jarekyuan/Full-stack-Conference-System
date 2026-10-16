/**
 * src/pages/NotificationList.tsx
 * 通知中心（T4.F1）：分页列表 + 单条已读 / 全部已读（对照 docs/04 3.3）。
 *
 * 与 WS 的分工：本页负责"拉全量、可翻页、可操作"；铃铛角标由 WS 实时更新（见 MainLayout + useWebSocket）。
 * 两处都读同一个 store 里的未读数——一处改、处处生效。
 */
import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App, Button, Empty, List, Space, Tag, Typography } from 'antd'
import { CheckCheck } from 'lucide-react'
import { fetchNotifications, markAllRead, markRead, type NotificationItem } from '@/api/notification'
import { fetchUnreadCount } from '@/api/notification'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useNotificationStore } from '@/stores/notificationStore'

const { Text } = Typography

/** 通知类型 → 中文标签与配色（与 docs/04 5.3 一一对应） */
const TYPE_META: Record<string, { label: string; color: string }> = {
  MEETING_CREATED: { label: '预约创建', color: 'blue' },
  APPROVAL_TODO: { label: '待审批', color: 'orange' },
  APPROVAL_RESULT: { label: '审批结果', color: 'green' },
  MEETING_REMIND: { label: '会议提醒', color: 'purple' },
  MEETING_UPDATED: { label: '会议变更', color: 'cyan' },
  MEETING_CANCELLED: { label: '会议取消', color: 'default' },
}

export default function NotificationList() {
  const navigate = useNavigate()
  const { message } = App.useApp()
  const setUnreadCount = useNotificationStore((s) => s.setUnreadCount)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  /** 改数据后靠它触发重查（与 ApprovalCenter 同一套约定：manual:false + refreshDeps） */
  const [refreshKey, setRefreshKey] = useState(0)

  const { data, loading } = useAppRequest(() => fetchNotifications(page, size), {
    manual: false,
    refreshDeps: [page, size, refreshKey],
  })

  /** 操作完刷新列表 + 用 REST 的权威值刷新角标（不靠"本地 -1"推算，见 notificationStore 注释） */
  const reload = useCallback(async () => {
    setRefreshKey((k) => k + 1)
    try {
      const { unreadCount } = await fetchUnreadCount()
      setUnreadCount(unreadCount)
    } catch {
      /* 角标刷新失败不影响列表 */
    }
  }, [setUnreadCount])

  const handleRead = async (item: NotificationItem) => {
    if (item.readFlag === 1) {
      if (item.meetingId) navigate(`/meetings/${item.meetingId}`)
      return
    }
    await markRead(item.id)
    await reload()
  }

  const handleReadAll = async () => {
    const { updated } = await markAllRead()
    message.success(updated > 0 ? `已把 ${updated} 条标为已读` : '没有未读通知')
    await reload()
  }

  const list = data?.list ?? []

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-medium">通知中心</h2>
        <Button icon={<CheckCheck size={16} />} onClick={handleReadAll} disabled={list.length === 0}>
          全部已读
        </Button>
      </div>

      <List
        loading={loading}
        dataSource={list}
        locale={{ emptyText: <Empty description="还没有通知" /> }}
        pagination={{
          current: page,
          pageSize: size,
          total: data?.total ?? 0,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(p)
            setSize(s) // 走 refreshDeps 自动重查，不再手动 run
          },
        }}
        renderItem={(item) => {
          const meta = TYPE_META[item.type] ?? { label: item.type, color: 'default' }
          const unread = item.readFlag === 0
          return (
            <List.Item
              className={`cursor-pointer rounded px-3 hover:bg-slate-50 ${unread ? 'bg-blue-50/40' : ''}`}
              onClick={() => void handleRead(item)}
              actions={[
                unread ? (
                  <Text type="secondary" className="text-xs">
                    未读
                  </Text>
                ) : (
                  <Text type="secondary" className="text-xs">
                    已读
                  </Text>
                ),
              ]}
            >
              <List.Item.Meta
                title={
                  <Space size={8}>
                    <Tag color={meta.color}>{meta.label}</Tag>
                    <span className={unread ? 'font-medium' : ''}>{item.title}</span>
                  </Space>
                }
                description={
                  <div>
                    <div className="text-slate-600">{item.content}</div>
                    <Text type="secondary" className="text-xs">
                      {item.createdAt}
                    </Text>
                  </div>
                }
              />
            </List.Item>
          )
        }}
      />
    </div>
  )
}
