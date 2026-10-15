/**
 * src/pages/MeetingDetail.tsx
 * 会议详情（docs/02 4.1 /meetings/:id）：基本信息 + 参会人 + **状态时间线** + 取消操作。
 * 取消按钮的显示条件：**仅组织人**（数据权限，后端还会再校验归属 40004），
 * 且状态处于可取消区间（状态机只允许 10/20 → CANCEL，30 进行中禁止）——
 * 前端只是"不给无意义的入口"，真正的裁决在状态机与 R-6 取消截止（超时回 30005）。
 * 取消原因可选（docs/04：POST /meetings/{id}/cancel，body 可空）。
 */
import { useCallback, useState } from 'react'
import { App, Button, Card, Descriptions, Input, Modal, Space, Spin, Timeline, Typography } from 'antd'
import dayjs from 'dayjs'
import { useNavigate, useParams } from 'react-router-dom'
import { cancelMeeting, getMeeting } from '@/api/meeting'
import MeetingStatusTag, { meetingEventText, meetingStatusText } from '@/components/MeetingStatusTag'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'
import { ApiError } from '@/api/types'

/** 状态机允许取消的状态（10 待审批 / 20 已预约；30 进行中禁止，见 docs/01 迁移矩阵） */
const CANCELLABLE_STATUS = [10, 20]

export default function MeetingDetail() {
  const { id } = useParams<{ id: string }>()
  const meetingId = Number(id)
  const navigate = useNavigate()
  const { message } = App.useApp()
  const currentUser = useUserStore((s) => s.user)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [reason, setReason] = useState('')

  const { data: detail, loading, refresh } = useAppRequest(() => getMeeting(meetingId), {
    manual: false,
    refreshDeps: [meetingId],
  })

  const { runAsync: doCancel, loading: cancelling } = useAppRequest(cancelMeeting, { toastError: false })

  const handleCancel = useCallback(async () => {
    try {
      await doCancel(meetingId, reason.trim() || undefined)
      message.success('会议已取消')
      setCancelOpen(false)
      setReason('')
      refresh() // 取消后状态即变 50，刷新详情与时间线
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '取消失败，请稍后重试')
    }
  }, [doCancel, meetingId, reason, message, refresh])

  if (loading && !detail) {
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
  const canCancel = isOrganiser && CANCELLABLE_STATUS.includes(detail.status)

  return (
    <Card
      title={
        <Space>
          {detail.title}
          <MeetingStatusTag status={detail.status} />
        </Space>
      }
      extra={<Button onClick={() => navigate('/meetings')}>返回列表</Button>}
    >
      <Descriptions column={2} size="small" bordered className="mb-4">
        <Descriptions.Item label="会议室">{detail.roomName}</Descriptions.Item>
        <Descriptions.Item label="组织人">{detail.organiserName}</Descriptions.Item>
        <Descriptions.Item label="开始时间">{dayjs(detail.startTime).format('YYYY-MM-DD HH:mm')}</Descriptions.Item>
        <Descriptions.Item label="结束时间">{dayjs(detail.endTime).format('YYYY-MM-DD HH:mm')}</Descriptions.Item>
        <Descriptions.Item label="参会人">
          {detail.participants.length
            ? detail.participants.map((p) => p.realName).join('、')
            : '（无）'}
        </Descriptions.Item>
        <Descriptions.Item label="创建时间">{dayjs(detail.createdAt).format('YYYY-MM-DD HH:mm')}</Descriptions.Item>
        <Descriptions.Item label="备注" span={2}>
          {detail.remark || '（无）'}
        </Descriptions.Item>
        {detail.cancelReason && (
          <Descriptions.Item label="取消原因" span={2}>
            {detail.cancelReason}
            {detail.cancelTime && `（${dayjs(detail.cancelTime).format('YYYY-MM-DD HH:mm')}）`}
          </Descriptions.Item>
        )}
      </Descriptions>

      <Typography.Title level={5}>状态时间线</Typography.Title>
      <Timeline
        className="mt-4"
        items={detail.logs.map((log) => ({
          children: (
            <div>
              <b>{meetingEventText(log.event)}</b>
              <span className="ml-2 text-gray-500">
                {meetingStatusText(log.fromStatus)} → {meetingStatusText(log.toStatus)}
              </span>
              <div className="text-xs text-gray-400">
                {dayjs(log.createdAt).format('YYYY-MM-DD HH:mm')}　{log.operatorName ?? '系统'}
                {log.remark ? `　${log.remark}` : ''}
              </div>
            </div>
          ),
        }))}
      />

      {canCancel && (
        <Button danger onClick={() => setCancelOpen(true)}>
          取消会议
        </Button>
      )}

      <Modal
        title="取消会议"
        open={cancelOpen}
        confirmLoading={cancelling}
        onCancel={() => setCancelOpen(false)}
        onOk={handleCancel}
        okText="确认取消"
        okButtonProps={{ danger: true }}
      >
        <p>取消后时段将被释放，且不可恢复。可填写取消原因（选填）：</p>
        <Input.TextArea
          rows={3}
          maxLength={200}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="如：议题变更，改期再约"
        />
      </Modal>
    </Card>
  )
}
