/**
 * src/components/ApprovalDrawer.tsx
 * 审批抽屉（T3.F1）：把"决定批不批"需要的信息集中在一处——主题/申请人/会议室/时间/**用途备注**，
 * 外加两个动作按钮与一个审批意见输入框。
 *
 * 设计取舍：
 * ① 不额外拉一次详情接口：待办列表项已经带齐了决策所需字段（这正是后端把 remark 放进列表的原因）。
 *    想看参会人/时间线这类完整信息，抽屉里给一个"查看完整详情"的跳转入口（详情页公开可查）。
 * ② 拒绝的"理由必填"在**前端先拦一道**（省一次往返），但后端仍会再校验（10001）——
 *    前端校验是体验，不是安全边界。
 * ③ 50001（复检冲突）交给父组件弹详情：抽屉自己不该知道"冲突详情长什么样"这种页面级编排。
 */
import { useCallback, useEffect, useState } from 'react'
import { App, Button, Descriptions, Drawer, Input, Space, Typography } from 'antd'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { passApproval, rejectApproval, type ApprovalPendingItem } from '@/api/approval'
import type { MeetingConflict } from '@/api/meeting'
import { useAppRequest } from '@/hooks/useAppRequest'
import { ApiError } from '@/api/types'

interface ApprovalDrawerProps {
  /** 当前审批的待办项；null = 关闭 */
  item: ApprovalPendingItem | null
  onClose: () => void
  /** 审批成功（父组件据此关抽屉 + 刷新列表） */
  onDone: () => void
  /** 复检冲突（50001）：把冲突详情交给父组件弹窗 */
  onConflict: (conflicts: MeetingConflict[]) => void
}

export default function ApprovalDrawer({ item, onClose, onDone, onConflict }: ApprovalDrawerProps) {
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [comment, setComment] = useState('')
  /** 拒绝时前端先拦的"理由必填"提示 */
  const [commentError, setCommentError] = useState('')

  // 换一条待办 / 重新打开时清空上一次的输入，避免意见串到另一场会议上
  useEffect(() => {
    setComment('')
    setCommentError('')
  }, [item])

  const { runAsync: doPass, loading: passing } = useAppRequest(passApproval, { toastError: false })
  const { runAsync: doReject, loading: rejecting } = useAppRequest(rejectApproval, { toastError: false })

  /**
   * 审批失败的统一收尾。
   * **50002（已被他人处理）必须走"关抽屉 + 刷新"**（docs/04 2.2 的建议）：这条待办在别人手里已经结掉了，
   * 只弹个 toast 的话列表里那行还在，用户会反复点、反复吃同一个错。
   */
  const failWith = useCallback(
    (e: unknown, fallback: string) => {
      if (e instanceof ApiError && e.code === 50002) {
        message.warning('该申请已被其他人处理，列表已刷新')
        onDone()
        return
      }
      message.error(e instanceof ApiError ? e.message : fallback)
    },
    [message, onDone],
  )

  const handlePass = useCallback(async () => {
    if (!item) return
    try {
      // 返回体只带 meetingId/status（后端 ApprovalResultVO），文案不依赖后端
      await doPass(item.meetingId, comment.trim() || undefined)
      message.success('已通过审批，会议进入已预约')
      onDone()
    } catch (e) {
      if (e instanceof ApiError && e.code === 50001) {
        // 设计内的业务分支：待审批期间时段被别人订走了
        const conflicts = (e.data as { conflicts?: MeetingConflict[] } | null)?.conflicts ?? []
        onConflict(conflicts)
        return
      }
      failWith(e, '审批失败，请稍后重试')
    }
  }, [item, comment, doPass, message, onDone, onConflict, failWith])

  const handleReject = useCallback(async () => {
    if (!item) return
    const trimmed = comment.trim()
    if (!trimmed) {
      setCommentError('拒绝必须填写理由')
      return
    }
    try {
      await doReject(item.meetingId, trimmed)
      message.success('已拒绝该申请')
      onDone()
    } catch (e) {
      failWith(e, '操作失败，请稍后重试')
    }
  }, [item, comment, doReject, message, onDone, failWith])

  return (
    <Drawer
      title="审批"
      open={!!item}
      onClose={onClose}
      width={460}
      extra={
        <Space>
          {/* 两个按钮互相禁用：否则"通过"在飞的时候还能点"拒绝"，白吃一次 50002 */}
          <Button loading={rejecting} disabled={passing} danger onClick={handleReject}>
            拒绝
          </Button>
          <Button type="primary" loading={passing} disabled={rejecting} onClick={handlePass}>
            通过
          </Button>
        </Space>
      }
    >
      {item && (
        <>
          <Descriptions column={1} size="small" bordered className="mb-4">
            <Descriptions.Item label="会议主题">{item.title}</Descriptions.Item>
            <Descriptions.Item label="申请人">{item.organiserName}</Descriptions.Item>
            <Descriptions.Item label="会议室">{item.roomName}</Descriptions.Item>
            <Descriptions.Item label="会议时间">
              {dayjs(item.startTime).format('YYYY-MM-DD HH:mm')} ~ {dayjs(item.endTime).format('HH:mm')}
            </Descriptions.Item>
            <Descriptions.Item label="用途">{item.remark || '（未填）'}</Descriptions.Item>
          </Descriptions>

          <Typography.Text type="secondary" className="block mb-1">
            审批意见（通过时选填，拒绝时必填）
          </Typography.Text>
          <Input.TextArea
            rows={3}
            maxLength={500}
            value={comment}
            status={commentError ? 'error' : ''}
            onChange={(e) => {
              setComment(e.target.value)
              if (commentError) setCommentError('')
            }}
            placeholder="如：同意 / 该时段已安排客户接待，请改期"
          />
          {commentError && (
            <Typography.Text type="danger" className="block mt-1">
              {commentError}
            </Typography.Text>
          )}

          <Typography.Text type="secondary" className="block mt-4">
            待审批期间时段不占位，通过时服务端会再复检一次冲突——
            若此时已被占用会提示失败，请拒绝该申请，让申请人改期后重新提交。
          </Typography.Text>

          <Button type="link" className="mt-2 px-0" onClick={() => navigate(`/meetings/${item.meetingId}`)}>
            查看完整详情（参会人 / 状态时间线）
          </Button>
        </>
      )}
    </Drawer>
  )
}
