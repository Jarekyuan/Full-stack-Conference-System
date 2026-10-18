/**
 * src/components/CancelMeetingModal.tsx
 * 取消会议弹窗（T6.2）：列表与详情共用，保证两处的文案、原因输入、状态判断完全一致。
 *
 * 权限/状态由调用方决定是否显示按钮；真正的裁决仍在服务端（归属 40004 / 状态 40002 / 取消截止 30005）。
 */
import { useEffect, useState } from 'react'
import { App, Input, Modal } from 'antd'
import { cancelMeeting } from '@/api/meeting'
import { ApiError } from '@/api/types'
import { useAppRequest } from '@/hooks/useAppRequest'

interface CancelMeetingModalProps {
  open: boolean
  meetingId: number | null
  /** ADMIN 代取消时必须填写原因（后端同样校验） */
  requireReason?: boolean
  onClose: () => void
  onSuccess: () => void
}

export default function CancelMeetingModal({
  open,
  meetingId,
  requireReason = false,
  onClose,
  onSuccess,
}: CancelMeetingModalProps) {
  const { message } = App.useApp()
  const [reason, setReason] = useState('')
  const { runAsync: doCancel, loading } = useAppRequest(cancelMeeting, { toastError: false })

  // 每次打开清空上一次的原因（组件常驻，不复位会带到下一场会议）
  useEffect(() => {
    if (open) setReason('')
  }, [open])

  const handleOk = async () => {
    if (meetingId == null) return
    try {
      await doCancel(meetingId, reason.trim() || undefined)
      message.success('会议已取消')
      onSuccess()
      onClose()
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '取消失败，请稍后重试')
    }
  }

  return (
    <Modal
      title="取消会议"
      open={open}
      confirmLoading={loading}
      onCancel={onClose}
      onOk={handleOk}
      okText="确认取消"
      okButtonProps={{ danger: true, disabled: requireReason && !reason.trim() }}
      destroyOnClose
    >
      <p>
        {requireReason
          ? '管理员代取消必须填写原因，取消后时段将被释放并通知组织人：'
          : '取消后时段将被释放，且不可恢复。可填写取消原因（选填）：'}
      </p>
      <Input.TextArea
        rows={3}
        maxLength={200}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="如：议题变更，改期再约"
      />
    </Modal>
  )
}
