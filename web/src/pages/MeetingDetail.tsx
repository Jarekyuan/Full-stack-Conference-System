/**
 * src/pages/MeetingDetail.tsx
 * 会议详情（docs/02 4.1 /meetings/:id）：基本信息 + 参会人 + **状态时间线** + 随状态变化的操作区。
 *
 * 操作按钮的显示条件（**仅组织人** + 状态门槛），前端只是"不给无意义的入口"：
 *   · 取消会议：10/20（状态机只允许这两态；30 进行中禁止）＋ R-6 取消截止（超时回 30005）
 *   · 延长会议 / 提前结束（T3.F2）：**仅进行中（30）**
 * 真正的裁决都在服务端（40004 归属 / 40002 状态 / 40006 超上限 / 40001 冲突）。
 *
 * 延长的上限由**服务端**算（R-15：min(下一场开始 − 缓冲, 当日 20:00)），前端只负责展示——
 * 这条规则涉及三个量的取小，前端再算一遍就是重写一遍规则，迟早不一致。
 */
import { useCallback, useEffect, useState } from 'react'
import { App, Button, Card, InputNumber, Modal, Space, Spin, Tabs, Typography } from 'antd'
import dayjs from 'dayjs'
import { useNavigate, useParams } from 'react-router-dom'
import {
  extendMeeting,
  finishMeeting,
  getExtendLimit,
  getMeeting,
  type ExtendLimit,
} from '@/api/meeting'
import CancelMeetingModal from '@/components/CancelMeetingModal'
import MeetingDetailInfo from '@/components/meeting-detail/MeetingDetailInfo'
import MeetingEditDrawer from '@/components/MeetingEditDrawer'
import MeetingMinutesPanel from '@/components/minutes/MeetingMinutesPanel'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'
import { ApiError } from '@/api/types'

/** 状态机允许取消的状态（10 待审批 / 20 已预约；30 进行中禁止，见 docs/01 迁移矩阵） */
const CANCELLABLE_STATUS = [10, 20]
/** 进行中：延长/提前结束的唯一入口状态 */
const ONGOING_STATUS = 30

export default function MeetingDetail() {
  const { id } = useParams<{ id: string }>()
  const meetingId = Number(id)
  const navigate = useNavigate()
  const { message } = App.useApp()
  const currentUser = useUserStore((s) => s.user)
  const [tab, setTab] = useState('info')
  const [cancelOpen, setCancelOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  // 延长/提前结束（T3.F2）：上限先向服务端要，再让用户在其中选
  const [extendOpen, setExtendOpen] = useState(false)
  const [limit, setLimit] = useState<ExtendLimit | null>(null)
  const [extendMinutes, setExtendMinutes] = useState(30)
  const [finishOpen, setFinishOpen] = useState(false)

  const { data, loading, refresh } = useAppRequest(() => getMeeting(meetingId), {
    manual: false,
    refreshDeps: [meetingId],
  })

  // 竞态守卫：只有"返回体的 id 就是当前路由的 id"才渲染。
  // 少了这一层，从 /meetings/2 浏览器后退到 /meetings/1 时（同一路由元素、实例复用、不重挂载），
  // 会在会议 1 的 URL 下完整显示会议 2 的标题/参会人/备注——比"空窗"更糟（评审 R10）。
  const detail = data && data.id === meetingId ? data : undefined

  // 换会议（含浏览器后退切换同一路由的不同 id）时，把所有弹窗状态清掉：
  // 否则上一场的"上限/取消原因"会留在新会议的弹窗里（同一路由元素复用、组件不重挂载）
  useEffect(() => {
    setTab('info')
    setCancelOpen(false)
    setEditOpen(false)
    setExtendOpen(false)
    setFinishOpen(false)
    setLimit(null)
  }, [meetingId])

  // 延长/结束的三个请求：toastError 关掉——错误由调用点按场景给文案（40006/40002 的含义各不相同）
  const { runAsync: fetchLimit, loading: limitLoading } = useAppRequest(getExtendLimit, { toastError: true })
  const { runAsync: doExtend, loading: extending } = useAppRequest(extendMeeting, { toastError: false })
  const { runAsync: doFinish, loading: finishing } = useAppRequest(finishMeeting, { toastError: false })

  /** 打开延长弹窗：先取上限（服务端算的 R-15），拿到再展示——避免让用户在"不知道能延多久"时填数字 */
  const handleOpenExtend = useCallback(async () => {
    setLimit(null)
    setExtendOpen(true)
    try {
      const data = await fetchLimit(meetingId)
      setLimit(data)
      setExtendMinutes(Math.min(30, Math.max(data.maxDelayMinutes, 1)))
    } catch {
      // 上限取不到就别让用户白填：关掉弹窗（错误已由 useAppRequest 统一 toast）
      setExtendOpen(false)
    }
  }, [fetchLimit, meetingId])

  const handleExtend = useCallback(async () => {
    try {
      await doExtend(meetingId, extendMinutes)
      message.success(`已延长 ${extendMinutes} 分钟`)
      setExtendOpen(false)
      refresh() // 延长后 end_time 与时间线都变了
    } catch (e) {
      if (e instanceof ApiError && e.code === 40006) {
        // 上限在我打开弹窗之后变了（有人订了紧随其后的时段）——docs/04 2.2 要求"更新弹窗上限"，
        // 否则用户只能关掉重开；后端 40006 的 data 里带的就是**锁内刚算出来**的新上限，直接用
        const max = (e.data as { maxDelayMinutes?: number } | null)?.maxDelayMinutes
        if (typeof max === 'number') {
          setLimit({ maxDelayMinutes: max, reason: '刚有人订了紧随其后的时段，上限已更新' })
          setExtendMinutes((m) => Math.min(Math.max(max, 1), m))
        }
        message.warning(e.message)
        return
      }
      message.error(e instanceof ApiError ? e.message : '延长失败，请稍后重试')
    }
  }, [doExtend, meetingId, extendMinutes, message, refresh])

  const handleFinish = useCallback(async () => {
    try {
      await doFinish(meetingId)
      message.success('会议已结束，剩余时段已释放')
      setFinishOpen(false)
      refresh()
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '操作失败，请稍后重试')
    }
  }, [doFinish, meetingId, message, refresh])

  // 路由 A→B 的首帧：data 还是 A、loading 尚未被 effect 置 true，但 detail 已被 id 守卫置空。
  // 此时应转圈，而不是闪一下“会议不存在”（O9）。
  if (!detail && (loading || (data && data.id !== meetingId))) {
    return (
      <Card>
        <Spin />
      </Card>
    )
  }
  if (!detail) {
    return (
      <Card>
        <Typography.Text type="secondary">会议不存在或无权查看</Typography.Text>
      </Card>
    )
  }

  const isOrganiser = !!currentUser && currentUser.id === detail.organiserId
  const isAdmin = (currentUser?.roles ?? []).includes('ADMIN')
  /** O15：组织人可取消；ADMIN 可代取消（必须填原因，后端同样校验） */
  const adminCancel = isAdmin && !isOrganiser
  const canCancel = (isOrganiser || isAdmin) && CANCELLABLE_STATUS.includes(detail.status)
  /** 仅组织人 + 20 已预约可编辑（R-12；改到需审批会议室会回退到 10） */
  const canEdit = isOrganiser && detail.status === 20
  /** 进行中 + 组织人 = 可延长/提前结束（30 是唯一入口，见 docs/01 迁移矩阵） */
  const canOperate = isOrganiser && detail.status === ONGOING_STATUS

  return (
    <Card
      title={detail.title}
      extra={<Button onClick={() => navigate('/meetings')}>返回列表</Button>}
    >
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'info', label: '基本信息', children: <MeetingDetailInfo detail={detail} /> },
          { key: 'minutes', label: '会议纪要', children: <MeetingMinutesPanel meeting={detail} /> },
        ]}
      />

      <Space>
        {canEdit && (
          <Button type="primary" onClick={() => setEditOpen(true)}>
            编辑会议
          </Button>
        )}
        {canCancel && (
          <Button danger onClick={() => setCancelOpen(true)}>
            {adminCancel ? '代取消会议' : '取消会议'}
          </Button>
        )}
        {/* 进行中才有"延长/提前结束"（状态机只允许 30 出发，见 docs/01 迁移矩阵） */}
        {canOperate && (
          <>
            <Button type="primary" onClick={handleOpenExtend} loading={limitLoading}>
              延长会议
            </Button>
            <Button onClick={() => setFinishOpen(true)}>提前结束</Button>
          </>
        )}
      </Space>

      <CancelMeetingModal
        open={cancelOpen}
        meetingId={meetingId}
        requireReason={adminCancel}
        onClose={() => setCancelOpen(false)}
        onSuccess={refresh}
      />
      <MeetingEditDrawer
        open={editOpen}
        meetingId={meetingId}
        onClose={() => setEditOpen(false)}
        onSuccess={refresh}
      />

      {/* 延长：上限与理由都来自服务端（R-15 的 min(下一场−缓冲, 当日 20:00) 不在前端重算） */}
      <Modal
        title="延长会议"
        open={extendOpen}
        confirmLoading={extending}
        onCancel={() => setExtendOpen(false)}
        onOk={handleExtend}
        okText="确认延长"
        okButtonProps={{ disabled: !limit || limit.maxDelayMinutes < 1 }}
      >
        {limit ? (
          <>
            <p>
              当前结束时间 <b>{dayjs(detail.endTime).format('HH:mm')}</b>，{limit.reason}。
            </p>
            <p className="mb-2">
              最多可延长 <b>{limit.maxDelayMinutes}</b> 分钟
              {limit.maxDelayMinutes < 1 && '——已无延长空间（下一场会议紧随其后或已到当日 20:00）'}
            </p>
            <Space>
              <span>延长</span>
              <InputNumber
                min={1}
                max={Math.max(limit.maxDelayMinutes, 1)}
                step={5}
                value={extendMinutes}
                onChange={(v) => setExtendMinutes(v ?? 1)}
                disabled={limit.maxDelayMinutes < 1}
              />
              <span>分钟</span>
            </Space>
          </>
        ) : (
          <Spin />
        )}
      </Modal>

      <Modal
        title="提前结束"
        open={finishOpen}
        confirmLoading={finishing}
        onCancel={() => setFinishOpen(false)}
        onOk={handleFinish}
        okText="确认结束"
      >
        <p>
          结束后会议进入「已结束」，剩余时段立即释放（别人可订）。此操作不可撤销。
        </p>
      </Modal>
    </Card>
  )
}
