import { Descriptions, Timeline, Typography } from 'antd'
import dayjs from 'dayjs'
import type { MeetingDetail } from '@/api/meeting'
import MeetingStatusTag, { meetingEventText, meetingStatusText } from '@/components/MeetingStatusTag'

export default function MeetingDetailInfo({ detail }: { detail: MeetingDetail }) {
  return (
    <>
      <Descriptions column={2} size="small" bordered className="mb-4">
        <Descriptions.Item label="状态"><MeetingStatusTag status={detail.status} /></Descriptions.Item>
        <Descriptions.Item label="会议室">{detail.roomName}</Descriptions.Item>
        <Descriptions.Item label="组织人">{detail.organiserName}</Descriptions.Item>
        <Descriptions.Item label="创建时间">{dayjs(detail.createdAt).format('YYYY-MM-DD HH:mm')}</Descriptions.Item>
        <Descriptions.Item label="开始时间">{dayjs(detail.startTime).format('YYYY-MM-DD HH:mm')}</Descriptions.Item>
        <Descriptions.Item label="结束时间">{dayjs(detail.endTime).format('YYYY-MM-DD HH:mm')}</Descriptions.Item>
        <Descriptions.Item label="参会人" span={2}>
          {detail.participants.length ? detail.participants.map((p) => p.realName).join('、') : '（无）'}
        </Descriptions.Item>
        <Descriptions.Item label="备注" span={2}>{detail.remark || '（无）'}</Descriptions.Item>
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
              <span className="ml-2 text-gray-500 dark:text-slate-400">
                {meetingStatusText(log.fromStatus)} → {meetingStatusText(log.toStatus)}
              </span>
              <div className="text-xs text-gray-400 dark:text-slate-500">
                {dayjs(log.createdAt).format('YYYY-MM-DD HH:mm')}　{log.operatorName ?? '系统'}
                {log.remark ? `　${log.remark}` : ''}
              </div>
            </div>
          ),
        }))}
      />
    </>
  )
}
