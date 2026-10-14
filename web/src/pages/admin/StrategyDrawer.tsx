/**
 * src/pages/admin/StrategyDrawer.tsx
 * 预约策略配置抽屉（ADMIN）：全量更新某会议室的策略字段。
 * 表单用 RHF + zod，校验范围对齐后端 StrategyDTO（advanceDays 1-365、maxDuration 15-1440 等）。
 * 打开时用 RoomVO 自带的策略字段预填，保存后回调父组件刷新列表。
 */
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Button, Drawer, Form, InputNumber, Switch, Typography } from 'antd'
import { updateStrategy, type Room, type StrategyInput } from '@/api/room'
import { useAppRequest } from '@/hooks/useAppRequest'

const strategySchema = z.object({
  needApproval: z.number().int().min(0).max(1),
  advanceDays: z.number().int().min(1).max(365),
  maxDurationMinutes: z.number().int().min(15).max(1440),
  cancelDeadlineMinutes: z.number().int().min(0).max(1440),
  bufferMinutes: z.number().int().min(0).max(60),
  allowRecurring: z.number().int().min(0).max(1),
})

type StrategyForm = z.infer<typeof strategySchema>

interface StrategyDrawerProps {
  open: boolean
  room: Room | null
  onClose: () => void
  onSaved: () => void
}

export default function StrategyDrawer({ open, room, onClose, onSaved }: StrategyDrawerProps) {
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<StrategyForm>({
    resolver: zodResolver(strategySchema),
    defaultValues: {
      needApproval: 0,
      advanceDays: 30,
      maxDurationMinutes: 240,
      cancelDeadlineMinutes: 30,
      bufferMinutes: 0,
      allowRecurring: 1,
    },
  })

  // 打开抽屉时用会议室现有策略预填
  useEffect(() => {
    if (open && room) {
      reset({
        needApproval: room.needApproval,
        advanceDays: room.advanceDays,
        maxDurationMinutes: room.maxDurationMinutes,
        cancelDeadlineMinutes: room.cancelDeadlineMinutes,
        bufferMinutes: room.bufferMinutes,
        allowRecurring: room.allowRecurring,
      })
    }
  }, [open, room, reset])

  const { runAsync: save, loading } = useAppRequest(updateStrategy)

  const onSubmit = async (values: StrategyForm) => {
    if (!room) return
    const input: StrategyInput = { ...values }
    await save(room.id, input)
    onSaved()
    onClose()
  }

  return (
    <Drawer
      title={room ? `预约策略 - ${room.name}` : '预约策略'}
      open={open}
      onClose={onClose}
      width={400}
      extra={
        <Button type="primary" loading={loading} onClick={handleSubmit(onSubmit)}>
          保存
        </Button>
      }
    >
      <Form layout="vertical">
        <Form.Item
          label="需要审批"
          validateStatus={errors.needApproval ? 'error' : ''}
          help={errors.needApproval?.message}
        >
          <Controller
            name="needApproval"
            control={control}
            render={({ field }) => (
              <Switch checked={field.value === 1} onChange={(v) => field.onChange(v ? 1 : 0)} />
            )}
          />
        </Form.Item>

        <Form.Item
          label="提前预约天数（1-365）"
          validateStatus={errors.advanceDays ? 'error' : ''}
          help={errors.advanceDays?.message}
        >
          <Controller
            name="advanceDays"
            control={control}
            render={({ field }) => (
              <InputNumber {...field} min={1} max={365} className="w-full" />
            )}
          />
        </Form.Item>

        <Form.Item
          label="单场最长时长（分钟，15-1440）"
          validateStatus={errors.maxDurationMinutes ? 'error' : ''}
          help={errors.maxDurationMinutes?.message}
        >
          <Controller
            name="maxDurationMinutes"
            control={control}
            render={({ field }) => (
              <InputNumber {...field} min={15} max={1440} className="w-full" />
            )}
          />
        </Form.Item>

        <Form.Item
          label="取消截止（分钟，0-1440）"
          validateStatus={errors.cancelDeadlineMinutes ? 'error' : ''}
          help={errors.cancelDeadlineMinutes?.message}
        >
          <Controller
            name="cancelDeadlineMinutes"
            control={control}
            render={({ field }) => (
              <InputNumber {...field} min={0} max={1440} className="w-full" />
            )}
          />
        </Form.Item>

        <Form.Item
          label="锁定缓冲（分钟，0-60）"
          validateStatus={errors.bufferMinutes ? 'error' : ''}
          help={errors.bufferMinutes?.message}
        >
          <Controller
            name="bufferMinutes"
            control={control}
            render={({ field }) => (
              <InputNumber {...field} min={0} max={60} className="w-full" />
            )}
          />
        </Form.Item>

        <Form.Item
          label="允许周期会议"
          validateStatus={errors.allowRecurring ? 'error' : ''}
          help={errors.allowRecurring?.message}
        >
          <Controller
            name="allowRecurring"
            control={control}
            render={({ field }) => (
              <Switch checked={field.value === 1} onChange={(v) => field.onChange(v ? 1 : 0)} />
            )}
          />
        </Form.Item>
      </Form>

      <Typography.Text type="secondary" className="block mt-2">
        策略在创建会议室时已按默认值初始化，此处为全量覆盖更新。
      </Typography.Text>
    </Drawer>
  )
}
