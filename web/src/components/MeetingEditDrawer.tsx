/**
 * src/components/MeetingEditDrawer.tsx
 * 编辑会议抽屉（T6.2）：列表与详情共用。
 *
 * 约束（与后端一致，docs/04 3.2 / docs/01 R-12）：
 * - 只有组织人、且会议处于 **20 已预约** 时才允许编辑（调用方控制入口）；
 * - 可改：主题 / 会议室 / 起止时间 / 备注；**参会人不在本接口范围**；
 * - 乐观锁：version 从详情接口取回并原样带回；改期到需审批会议室会回退到 10。
 */
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import dayjs, { type Dayjs } from 'dayjs'
import { App, Button, DatePicker, Drawer, Empty, Form, Input, Select, Spin } from 'antd'
import { getMeeting, updateMeeting } from '@/api/meeting'
import { listRooms } from '@/api/room'
import { disabledTimeForToday } from '@/components/datePickerLimits'
import { ApiError } from '@/api/types'
import { useAppRequest } from '@/hooks/useAppRequest'
import { API_TIME_FORMAT } from '@/pages/bookingSchema'

const editSchema = z
  .object({
    title: z.string().trim().min(1, '请填写会议主题').max(100, '主题最长 100 字符'),
    roomId: z.number({ required_error: '请选择会议室', invalid_type_error: '请选择会议室' }),
    timeRange: z.tuple(
      [
        z.custom<Dayjs>((v) => dayjs.isDayjs(v), '请选择会议时间'),
        z.custom<Dayjs>((v) => dayjs.isDayjs(v), '请选择会议时间'),
      ],
      { required_error: '请选择会议时间', invalid_type_error: '请选择会议时间' },
    ),
    remark: z.string().max(500, '备注最长 500 字符').optional(),
  })
  .refine((v) => v.timeRange[0].isBefore(v.timeRange[1]), {
    path: ['timeRange'],
    message: '结束时间必须晚于开始时间',
  })

type MeetingEditForm = z.infer<typeof editSchema>

interface MeetingEditDrawerProps {
  open: boolean
  meetingId: number | null
  onClose: () => void
  onSuccess: () => void
}

export default function MeetingEditDrawer({
  open,
  meetingId,
  onClose,
  onSuccess,
}: MeetingEditDrawerProps) {
  const { message } = App.useApp()
  const {
    data: detail,
    loading: detailLoading,
    run: loadDetail,
    mutate: mutateDetail,
  } = useAppRequest(getMeeting)
  const { data: rooms } = useAppRequest(() => listRooms(), { manual: false })
  const { runAsync: doUpdate, loading: saving } = useAppRequest(updateMeeting, { toastError: false })

  /**
   * R10：ahooks 的 `run` 不会清空旧 data（成功才 setData）；
   * 打开 B 时若 B 的详情请求慢/失败，detail 仍是 A，旧实现会渲染 A 的表单并用 A 的 id/version 提交。
   * 这里用 id 守卫把“不是当前会议的 detail”视为无效，并在打开时主动清空。
   */
  const currentDetail = detail && detail.id === meetingId ? detail : undefined

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<MeetingEditForm>({
    resolver: zodResolver(editSchema),
    defaultValues: { title: '', roomId: undefined, timeRange: undefined, remark: '' },
  })

  useEffect(() => {
    if (open && meetingId != null) {
      mutateDetail(undefined) // 清掉上一场的缓存，避免请求失败时回落到旧表单
      loadDetail(meetingId)
    }
  }, [open, meetingId, loadDetail, mutateDetail])

  useEffect(() => {
    if (!open || !currentDetail) return
    reset({
      title: currentDetail.title,
      roomId: currentDetail.roomId,
      timeRange: [dayjs(currentDetail.startTime), dayjs(currentDetail.endTime)],
      remark: currentDetail.remark ?? '',
    })
  }, [open, currentDetail, reset])

  const onSubmit = async (values: MeetingEditForm) => {
    if (!currentDetail || currentDetail.id !== meetingId) return
    try {
      await doUpdate(currentDetail.id, {
        title: values.title.trim(),
        roomId: values.roomId,
        startTime: values.timeRange[0].format(API_TIME_FORMAT),
        endTime: values.timeRange[1].format(API_TIME_FORMAT),
        remark: values.remark?.trim() || undefined,
        version: currentDetail.version,
      })
      message.success('会议已更新')
      onSuccess()
      onClose()
    } catch (e) {
      // 40001 冲突 / 40002 状态已变 / 30002 维护中：后端 msg 已是人话，直接展示
      message.error(e instanceof ApiError ? e.message : '修改失败，请稍后重试')
    }
  }

  return (
    <Drawer
      title="编辑会议"
      open={open}
      onClose={onClose}
      width={460}
      extra={
        <Button
          type="primary"
          loading={saving}
          disabled={detailLoading || !currentDetail}
          onClick={handleSubmit(onSubmit)}
        >
          保存
        </Button>
      }
    >
      {detailLoading ? (
        <Spin />
      ) : !currentDetail ? (
        <Empty description="会议加载失败">
          <Button onClick={() => meetingId != null && loadDetail(meetingId)}>重试</Button>
        </Empty>
      ) : (
        <Form layout="vertical">
          <Form.Item
            label="会议主题"
            validateStatus={errors.title ? 'error' : ''}
            help={errors.title?.message}
          >
            <Controller
              name="title"
              control={control}
              render={({ field }) => <Input {...field} maxLength={100} placeholder="如：产品评审会" />}
            />
          </Form.Item>

          <Form.Item
            label="会议室"
            validateStatus={errors.roomId ? 'error' : ''}
            help={errors.roomId?.message}
          >
            <Controller
              name="roomId"
              control={control}
              render={({ field }) => (
                <Select
                  value={field.value}
                  onChange={field.onChange}
                  options={(rooms ?? []).map((room) => ({
                    value: room.id,
                    label: `${room.name}${room.location ? `（${room.location}）` : ''}${
                      room.status === 1 ? ' · 维护中' : ''
                    }`,
                  }))}
                />
              )}
            />
          </Form.Item>

          <Form.Item
            label="会议时间"
            validateStatus={errors.timeRange ? 'error' : ''}
            help={errors.timeRange?.message}
          >
            <Controller
              name="timeRange"
              control={control}
              render={({ field }) => (
                <DatePicker.RangePicker
                  showTime={{ format: 'HH:mm', minuteStep: 30 }}
                  format="YYYY-MM-DD HH:mm"
                  className="w-full"
                  value={field.value}
                  onChange={(v) => field.onChange(v)}
                  disabledDate={(current) => current.isBefore(dayjs().startOf('day'))}
                  // C3：与预约 Drawer 共用同一份规则；绝不能返回 disabledSeconds
                  disabledTime={(current) => disabledTimeForToday(current)}
                />
              )}
            />
          </Form.Item>

          <Form.Item label="备注" validateStatus={errors.remark ? 'error' : ''} help={errors.remark?.message}>
            <Controller
              name="remark"
              control={control}
              render={({ field }) => <Input.TextArea {...field} rows={3} maxLength={500} />}
            />
          </Form.Item>
        </Form>
      )}
    </Drawer>
  )
}
