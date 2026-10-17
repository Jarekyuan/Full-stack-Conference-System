/**
 * src/pages/BookingDrawer.tsx
 * 预约表单 Drawer（T2.F2 核心）：主题 / 会议室 / 时间范围 / 参会人 / 备注。
 *
 * 三个关键机制：
 * ① **实时冲突提示**：时间或会议室变化 → 防抖 500ms 调 availability；结果带"请求键"，
 *    与当前表单值不一致的过期响应不显示（快速改时间不会看到旧时段的结论）。
 *    availability 只读不加锁，是 UI 辅助，最终以提交时校验为准（docs/04 7.4）。
 * ② **幂等提交**：抽屉打开时生成一次 UUID 放 X-Idempotency-Token，本次表单的所有重试复用它；
 *    重复提交后端回 10006 → request.ts 静默解包成 undefined（不抛异常）→ 成功回调必须判空。
 * ③ **提交时冲突（40001）**：可能是被并发抢了时段，弹 Modal 展示 data.conflicts 详情。
 * 表单用 RHF + zod，校验对齐后端 MeetingCreateDTO。
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import dayjs, { type Dayjs } from 'dayjs'
import { App, Alert, Button, DatePicker, Drawer, Form, Input, Modal, Select, Switch, Typography } from 'antd'
import {
  checkAvailability,
  createMeeting,
  createRecurring,
  type AvailabilityItem,
  type MeetingConflict,
  type MeetingCreateResult,
  type RecurringConflict,
  type RecurringCreateResult,
} from '@/api/meeting'
import type { Room } from '@/api/room'
import DeptUserPicker from '@/components/DeptUserPicker'
import { useAppRequest } from '@/hooks/useAppRequest'
import { ApiError } from '@/api/types'
import { API_TIME_FORMAT, bookingSchema, type BookingForm } from '@/pages/bookingSchema'

/** 冲突提示的防抖窗口（docs/05 T2.F2） */
const CONFLICT_CHECK_DEBOUNCE_MS = 500

/**
 * 幂等 token。优先 `crypto.randomUUID()`，但它只在 **secure context**（https / localhost）存在——
 * 若改成"局域网 IP + http"给面试官演示，直接调用会抛 TypeError，预约流程整条不可用。
 * 这里不承担安全职责（只是去重标识），退化到时间戳+随机串即可。
 */
function newIdempotencyToken(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `idem-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/** 点日历空格带过来的预填（时间已在 CalendarGrid 里吸附并组装成接口格式） */
export interface BookingPrefill {
  roomId: number
  startTime: string
  endTime: string
}

interface BookingDrawerProps {
  open: boolean
  prefill: BookingPrefill | null
  rooms: Room[]
  onClose: () => void
  onSuccess: () => void
}

export default function BookingDrawer({ open, prefill, rooms, onClose, onSuccess }: BookingDrawerProps) {
  const { message } = App.useApp()
  const [idempotencyToken, setIdempotencyToken] = useState('')
  const [conflictModal, setConflictModal] = useState<MeetingConflict[] | null>(null)
  /** 周期创建的冲突清单（带"哪一场"）——与单场冲突分开渲染，用户才知道改哪天 */
  const [recurringConflicts, setRecurringConflicts] = useState<RecurringConflict[] | null>(null)
  /** 已完成的可用性结论：checkKey 标识它属于哪组 (会议室, 时段) */
  const [availability, setAvailability] = useState<{ checkKey: string; item: AvailabilityItem } | null>(null)

  const {
    control,
    handleSubmit,
    reset,
    watch,
    getValues,
    formState: { errors },
  } = useForm<BookingForm>({
    resolver: zodResolver(bookingSchema),
    defaultValues: { title: '', userIds: [], remark: '', repeatWeekly: false },
  })

  // 只订阅这两个字段：写主题/备注不该触发重渲染
  const [roomId, timeRange, repeatWeekly] = watch(['roomId', 'timeRange', 'repeatWeekly'])

  /**
   * 当前表单值对应的"可用性检查键"。
   * 只有它与 availability.checkKey 相等时才展示结论——过期响应自然被挡在外面（竞态守卫）。
   */
  /** 当前选中的会议室（用于"可提前天数"这类策略提示：Room 里带着策略字段） */
  const selectedRoom = useMemo(() => rooms.find((r) => r.id === roomId), [rooms, roomId])

  const checkKey = useMemo(() => {
    const [start, end] = timeRange ?? []
    if (!roomId || !start || !end || !start.isValid() || !end.isValid()) return ''
    return `${roomId}|${start.format(API_TIME_FORMAT)}|${end.format(API_TIME_FORMAT)}`
  }, [roomId, timeRange])

  const { run: runAvailability } = useAppRequest(checkAvailability, {
    toastError: false, // 实时校验失败不打扰用户（提交时才是关口）
    onSuccess: (data, params) => {
      const query = params[0]
      setAvailability({
        checkKey: `${query.roomIds[0]}|${query.startTime}|${query.endTime}`,
        item: data.items[0],
      })
    },
  })

  // 打开抽屉：按预填值初始化表单 + 生成本次表单的幂等 token
  useEffect(() => {
    if (!open) return
    setIdempotencyToken(newIdempotencyToken())
    setAvailability(null)
    reset({
      title: '',
      userIds: [],
      remark: '',
      roomId: prefill?.roomId,
      timeRange: prefill ? [dayjs(prefill.startTime), dayjs(prefill.endTime)] : undefined,
    })
  }, [open, prefill, reset])

  // 时间/会议室变化 → 防抖查可用性（值从 getValues 现取，避免闭包里的旧值）
  useEffect(() => {
    if (!open) return
    const [start, end] = getValues('timeRange') ?? []
    const roomId = getValues('roomId')
    if (!roomId || !start || !end || !start.isValid() || !end.isValid()) {
      setAvailability(null)
      return
    }
    const timer = setTimeout(() => {
      runAvailability({
        roomIds: [roomId],
        startTime: start.format(API_TIME_FORMAT),
        endTime: end.format(API_TIME_FORMAT),
      })
    }, CONFLICT_CHECK_DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // 只依赖 checkKey：它由 (会议室, 起止时间) 派生，等于把"这三个值变化才重查"压缩成一个依赖，
    // 避免把 runAvailability 之类的函数身份放进依赖导致定时器被反复重置（防抖永不触发）；
    // 值一律从 getValues() 现取，故也不依赖时间/会议室字段本身
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, checkKey])

  const { runAsync: submit, loading } = useAppRequest(createMeeting, { toastError: false })
  const { runAsync: submitRecurringRequest, loading: recurringLoading } = useAppRequest(createRecurring, {
    toastError: false,
  })

  /**
   * 周期创建（T5.4）：把表单值翻译成后端的"每周规则"。
   * dayOfWeek/startMinutes 全部**从开始时间派生**（不让用户另选，避免"周三 10:00 开始、规则却是周二"这种自相矛盾的输入）。
   */
  const submitRecurring = async (values: BookingForm) => {
    const [start, end] = values.timeRange
    const input = {
      title: values.title.trim(),
      roomId: values.roomId,
      // dayjs 的 day() 是 0=周日…6=周六；后端要的是 1=周一…7=周日。
      // （isoWeekday() 需要 isoWeek 插件——为一个数字引插件不划算，换算一行就够）
      dayOfWeek: start.day() === 0 ? 7 : start.day(),
      startMinutes: start.hour() * 60 + start.minute(),
      durationMinutes: end.diff(start, 'minute'),
      startDate: start.format('YYYY-MM-DD'),
      endDate: (values.repeatEndDate as Dayjs).format('YYYY-MM-DD'),
    }
    try {
      const result: RecurringCreateResult | undefined = await submitRecurringRequest(input, idempotencyToken)
      if (!result) {
        message.info('该系列已提交过，请勿重复提交')
        onSuccess()
        onClose()
        return
      }
      message.success(`已创建周期会议：${result.created} 场`)
      onSuccess()
      onClose()
    } catch (e) {
      if (e instanceof ApiError && e.code === 40005) {
        setRecurringConflicts((e.data as { conflicts?: RecurringConflict[] } | null)?.conflicts ?? [])
        return
      }
      // 其余（40007 未开放周期预约 / 30003 超可预约窗口 / 10006 幂等重复…）的 msg 已经是人话，直接弹
      message.error(e instanceof ApiError ? e.message : '创建失败，请稍后重试')
    }
  }

  const onSubmit = async (values: BookingForm) => {
    const input = {
      title: values.title.trim(),
      roomId: values.roomId,
      startTime: values.timeRange[0].format(API_TIME_FORMAT),
      endTime: values.timeRange[1].format(API_TIME_FORMAT),
      remark: values.remark?.trim() || undefined,
      participants: values.userIds.length ? { userIds: values.userIds } : undefined,
    }
    if (values.repeatWeekly) {
      await submitRecurring(values)
      return
    }
    try {
      const result: MeetingCreateResult | undefined = await submit(input, idempotencyToken)
      if (!result) {
        // 10006：request.ts 对幂等重复是静默解包成 undefined，到这里说明刚才已经提交成功了
        message.info('该预约已提交过，请勿重复提交')
        onSuccess()
        onClose()
        return
      }
      message.success(result.msg || '预约成功')
      onSuccess()
      onClose()
    } catch (e) {
      if (e instanceof ApiError && e.code === 40005) {
        // 周期创建的冲突：清单里每一项都带 occurrenceStart（哪一场），逐条列给用户
        setRecurringConflicts((e.data as { conflicts?: RecurringConflict[] } | null)?.conflicts ?? [])
        return
      }
      if (e instanceof ApiError && e.code === 40001) {
        // 提交时冲突：多半是被并发抢了（availability 是只读快照），弹详情
        const conflicts = (e.data as { conflicts?: MeetingConflict[] } | null)?.conflicts ?? []
        setConflictModal(conflicts)
        return
      }
      message.error(e instanceof ApiError ? e.message : '预约失败，请稍后重试')
    }
  }

  /** 关抽屉时清掉校验结论与冲突弹窗，避免下次打开看到上次的残留 */
  const handleClose = useCallback(() => {
    setAvailability(null)
    setConflictModal(null)
    onClose()
  }, [onClose])

  const availabilityView = availability && availability.checkKey === checkKey ? availability.item : null

  return (
    <Drawer
      title="预约会议"
      open={open}
      onClose={handleClose}
      width={460}
      extra={
        <Button type="primary" loading={loading || recurringLoading} onClick={handleSubmit(onSubmit)}>
          提交预约
        </Button>
      }
    >
      <Form layout="vertical">
        <Form.Item label="会议主题" validateStatus={errors.title ? 'error' : ''} help={errors.title?.message}>
          <Controller
            name="title"
            control={control}
            render={({ field }) => <Input {...field} maxLength={100} placeholder="如：产品评审会" />}
          />
        </Form.Item>

        <Form.Item label="会议室" validateStatus={errors.roomId ? 'error' : ''} help={errors.roomId?.message}>
          <Controller
            name="roomId"
            control={control}
            render={({ field }) => (
              <Select
                className="w-full"
                placeholder="请选择会议室"
                value={field.value}
                onChange={field.onChange}
                options={rooms.map((r) => ({
                  value: r.id,
                  label: `${r.name}${r.location ? `（${r.location}）` : ''}${r.needApproval === 1 ? ' · 需审批' : ''}`,
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
                // 过去时间在 UI 层就不可选（T6.1）；后端 validateTime 仍会兜底 30003
                disabledDate={(current) => current.isBefore(dayjs().startOf('day'))}
                disabledTime={(current) => {
                  if (!current || !current.isSame(dayjs(), 'day')) return {}
                  const now = dayjs()
                  return {
                    disabledHours: () => Array.from({ length: now.hour() }, (_, i) => i),
                    disabledMinutes: (hour: number) =>
                      hour === now.hour() ? Array.from({ length: now.minute() }, (_, i) => i) : [],
                    disabledSeconds: () => Array.from({ length: 60 }, (_, i) => i),
                  }
                }}
              />
            )}
          />
        </Form.Item>

        {/* 实时冲突提示：只在结论对应当前表单值时显示 */}
        {availabilityView && (
          <Form.Item>
            {availabilityView.available ? (
              <Alert type="success" showIcon message="该时段可预约" />
            ) : (
              <Alert
                type="error"
                showIcon
                message="该时段与已有会议冲突"
                description={availabilityView.conflicts.map((c) => (
                  <div key={c.meetingId}>
                    {c.startTime.slice(11, 16)}-{c.endTime.slice(11, 16)} {c.title}（{c.organiserName}）
                  </div>
                ))}
              />
            )}
          </Form.Item>
        )}

        {/* 周期会议（T5.4）：勾选后展开"重复到哪天"。星期几不另选——它就是开始时间所属的星期几 */}
        <Form.Item label="每周重复" tooltip="勾选后按本次的开始时间生成【每周一次】的系列会议">
          <Controller
            name="repeatWeekly"
            control={control}
            render={({ field }) => (
              <Switch checked={field.value} onChange={field.onChange} checkedChildren="每周" unCheckedChildren="单次" />
            )}
          />
        </Form.Item>
        {repeatWeekly && (
          <Form.Item
            label="重复到哪天"
            validateStatus={errors.repeatEndDate ? 'error' : ''}
            help={errors.repeatEndDate?.message}
            extra={
              timeRange?.[0]?.isValid()
                ? `每周${'一二三四五六日'[(timeRange[0].day() === 0 ? 7 : timeRange[0].day()) - 1]}，与本次同一时间，最多可提前 ${selectedRoom?.advanceDays ?? 30} 天`
                : '请先选择开始时间'
            }
          >
            <Controller
              name="repeatEndDate"
              control={control}
              render={({ field }) => (
                <DatePicker
                  className="w-full"
                  value={field.value}
                  onChange={field.onChange}
                  // 结束日期不能早于开始日期；也不能超过会议室的可提前天数（服务端同样会校验，这里先挡一道）
                  disabledDate={(d) =>
                    !d ||
                    !timeRange?.[0]?.isValid() ||
                    d.isBefore(timeRange[0].startOf('day')) ||
                    d.isAfter(timeRange[0].add(selectedRoom?.advanceDays ?? 30, 'day'))
                  }
                />
              )}
            />
          </Form.Item>
        )}

        <Form.Item label="参会人">
          <Controller
            name="userIds"
            control={control}
            render={({ field }) => <DeptUserPicker value={field.value} onChange={field.onChange} />}
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

      <Typography.Text type="secondary" className="block">
        提交时服务端会再校验一次冲突（提示为实时快照，可能已被他人抢先预约）。
      </Typography.Text>

      <Modal
        title="时段已被占用"
        open={!!conflictModal}
        onCancel={() => setConflictModal(null)}
        onOk={() => setConflictModal(null)}
        okText="知道了"
        cancelButtonProps={{ style: { display: 'none' } }}
      >
        <p>以下会议已占用该时段，请调整时间或更换会议室：</p>
        {conflictModal?.map((c) => (
          <div key={c.meetingId} className="py-0.5">
            <b>{c.title}</b>　{c.startTime.slice(11, 16)}-{c.endTime.slice(11, 16)}　{c.organiserName}
          </div>
        ))}
      </Modal>

      {/* 周期创建的冲突：逐条带"哪一场"，用户才知道是改整系列还是单独处理 */}
      <Modal
        title="周期会议存在冲突场次"
        open={!!recurringConflicts}
        onCancel={() => setRecurringConflicts(null)}
        onOk={() => setRecurringConflicts(null)}
        okText="知道了"
        cancelButtonProps={{ style: { display: 'none' } }}
      >
        <p className="mb-2 text-slate-500">以下场次与已有会议冲突，调整开始时间或缩短重复区间后重试：</p>
        {recurringConflicts?.map((c) => (
          <div key={`${c.occurrenceStart}-${c.meetingId}`} className="mb-1">
            第 {c.occurrenceStart.slice(0, 10)} 那一场：{c.startTime.slice(11, 16)}-{c.endTime.slice(11, 16)}{' '}
            {c.title}（{c.organiserName}）
          </div>
        ))}
      </Modal>
    </Drawer>
  )
}
