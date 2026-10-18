/**
 * src/pages/NotificationList.tsx
 * 通知中心（T4.F1 + T6.2）：分页列表 + 单条已读 / 全部已读 + 选择删除 / 全部删除。
 *
 * 点击行为（T6.2，按类型分流）：
 *   APPROVAL_TODO   → 先查会议状态；仍是 10 才进审批中心，否则提示“已审核/不存在”
 *   MEETING_CREATED → 会议列表（不需要直接进详情）
 *   其余（审批结果/提醒/变更/取消）→ 只标已读，不跳转
 *
 * 与 WS 的分工：本页负责“拉全量、可翻页、可操作”；铃铛角标由 WS 实时更新（见 MainLayout + useWebSocket）。
 * 两处都读同一个 store 里的未读数——一处改、处处生效。
 */
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, App, Button, Checkbox, Empty, List, Space, Tag, Typography } from 'antd'
import { CheckCheck, Trash2 } from 'lucide-react'
import { getMeeting } from '@/api/meeting'
import { notificationTarget } from '@/components/notificationRouting'
import { ApiError } from '@/api/types'
import {
  deleteAllNotifications,
  deleteNotifications,
  fetchNotifications,
  fetchUnreadCount,
  markAllRead,
  markRead,
  type NotificationItem,
} from '@/api/notification'
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
  const { message, modal } = App.useApp()
  const setUnreadCount = useNotificationStore((s) => s.setUnreadCount)
  const tick = useNotificationStore((s) => s.tick)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  /** 改数据后靠它触发重查（与 ApprovalCenter 同一套约定：manual:false + refreshDeps） */
  const [refreshKey, setRefreshKey] = useState(0)
  /** 当前页选中的通知 id（跨页不保留：删除是页面内的动作，翻页后选中旧页的 id 容易误删） */
  const [selected, setSelected] = useState<Set<number>>(new Set())

  const { data, loading, error } = useAppRequest(() => fetchNotifications(page, size), {
    manual: false,
    refreshDeps: [page, size, refreshKey, tick],
  })

  const list = data?.list ?? []
  const allSelected = list.length > 0 && list.every((item) => selected.has(item.id))
  const someSelected = selected.size > 0 && !allSelected

  /** 操作完刷新列表 + 用 REST 的权威值刷新角标（不靠"本地 -1"推算，见 notificationStore 注释） */
  const reload = useCallback(async () => {
    setRefreshKey((k) => k + 1)
    const versionBefore = useNotificationStore.getState().version
    try {
      const { unreadCount } = await fetchUnreadCount()
      setUnreadCount(unreadCount, versionBefore)
    } catch {
      /* 角标刷新失败不影响列表 */
    }
  }, [setUnreadCount])

  // 翻页/改页大小后清空选择，避免跨页误删
  useEffect(() => {
    setSelected(new Set())
  }, [page, size])

  // 删到当前页空了（比如删掉最后一页的最后一条）→ 自动回退到最后一页
  useEffect(() => {
    if (!data) return
    const maxPage = Math.max(1, Math.ceil(data.total / size))
    if (page > maxPage) setPage(maxPage)
  }, [data, page, size])

  /**
   * 点击一条通知：先标记已读（未读时），再按类型决定去哪。
   * 错误处理：60001（别人的/不存在的通知）静默 + 刷新列表（docs/04 3.3）。
   */
  const handleClick = async (item: NotificationItem) => {
    try {
      if (item.readFlag === 0) {
        await markRead(item.id)
        await reload()
      }
    } catch (e) {
      const code = (e as { code?: number }).code
      if (code === 60001) {
        await reload()
      } else {
        message.error('操作失败，请稍后重试')
        return
      }
    }

    // T6.2：只有“待审批”和“预约创建”有明确的去向；其余是信息型通知，只标已读
    const target = notificationTarget(item.type)
    if (target === 'approvals-check') {
      if (!item.meetingId) {
        message.warning('该会议已审核或不存在')
        return
      }
      try {
        const meeting = await getMeeting(item.meetingId)
        if (meeting.status !== 10) {
          message.warning('该会议已审核或不存在')
          return
        }
        navigate('/approvals')
      } catch {
        message.warning('该会议已审核或不存在')
      }
      return
    }
    if (target === 'meetings') {
      navigate('/meetings')
    }
  }

  const handleReadAll = async () => {
    try {
      const { updated } = await markAllRead()
      message.success(updated > 0 ? `已把 ${updated} 条标为已读` : '没有未读通知')
      await reload()
    } catch {
      message.error('操作失败，请稍后重试')
    }
  }

  const toggle = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const toggleAll = () => {
    if (allSelected) {
      setSelected(new Set())
    } else {
      setSelected(new Set(list.map((item) => item.id)))
    }
  }

  const handleDeleteSelected = () => {
    if (selected.size === 0) return
    modal.confirm({
      title: `删除选中的 ${selected.size} 条通知？`,
      content: '删除后不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const { deleted } = await deleteNotifications([...selected])
          message.success(`已删除 ${deleted} 条通知`)
          setSelected(new Set())
          await reload()
        } catch (e) {
          message.error(e instanceof ApiError ? e.message : '删除失败，请稍后重试')
          throw e // 保持弹窗打开，用户可重试或取消
        }
      },
    })
  }

  const handleDeleteAll = () => {
    modal.confirm({
      title: '删除全部通知？',
      content: '将删除你的所有通知，删除后不可恢复。',
      okText: '全部删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const { deleted } = await deleteAllNotifications()
          message.success(`已删除 ${deleted} 条通知`)
          setSelected(new Set())
          setPage(1)
          await reload()
        } catch (e) {
          message.error(e instanceof ApiError ? e.message : '删除失败，请稍后重试')
          throw e
        }
      },
    })
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-medium">通知中心</h2>
        <Space wrap>
          <Checkbox
            checked={allSelected}
            indeterminate={someSelected}
            disabled={list.length === 0}
            onChange={toggleAll}
          >
            全选本页
          </Checkbox>
          <Button
            danger
            icon={<Trash2 size={16} />}
            disabled={selected.size === 0}
            onClick={handleDeleteSelected}
          >
            删除选中{selected.size > 0 ? `（${selected.size}）` : ''}
          </Button>
          <Button danger icon={<Trash2 size={16} />} disabled={list.length === 0} onClick={handleDeleteAll}>
            全部删除
          </Button>
          <Button icon={<CheckCheck size={16} />} onClick={handleReadAll} disabled={list.length === 0}>
            全部已读
          </Button>
        </Space>
      </div>

      {error && (
        <Alert
          type="error"
          showIcon
          className="mb-3"
          message="通知加载失败，请检查通知服务后重试"
          action={
            <Button size="small" onClick={() => void reload()}>
              重试
            </Button>
          }
        />
      )}
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
            setSize(s)
          },
        }}
        renderItem={(item) => {
          const meta = TYPE_META[item.type] ?? { label: item.type, color: 'default' }
          const unread = item.readFlag === 0
          return (
            <List.Item
              tabIndex={0}
              className={`cursor-pointer rounded px-3 hover:bg-slate-50 dark:hover:bg-slate-800 ${unread ? 'bg-blue-50/40 dark:bg-blue-950/30' : ''}`}
              onClick={() => void handleClick(item)}
              onKeyDown={(e) => {
                // 只有条目本身获得焦点时才处理；内部勾选框/按钮保留自己的键盘行为
                if (e.target !== e.currentTarget) return
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  void handleClick(item)
                }
              }}
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
              <div className="flex w-full items-start gap-3">
                <Checkbox
                  checked={selected.has(item.id)}
                  onClick={(e) => e.stopPropagation()}
                  onChange={() => toggle(item.id)}
                />
                <List.Item.Meta
                  className="min-w-0 flex-1"
                  title={
                    <Space size={8}>
                      <Tag color={meta.color}>{meta.label}</Tag>
                      <span className={unread ? 'font-medium' : ''}>{item.title}</span>
                    </Space>
                  }
                  description={
                    <div>
                      <div className="text-slate-600 dark:text-slate-300">{item.content}</div>
                      <Text type="secondary" className="text-xs">
                        {item.createdAt}
                      </Text>
                    </div>
                  }
                />
              </div>
            </List.Item>
          )
        }}
      />
    </div>
  )
}
