/**
 * 「组织与用户」页的部门新增/编辑弹窗（从 OrgUsers.tsx 拆出，O14）。
 * 只负责表单；刷新树/成员列表由页面通过 onSuccess 编排。
 */
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { App, Input, InputNumber, Modal, Space, Switch, Typography } from 'antd'
import { createDept, updateDept, type DeptNode } from '@/api/dept'
import { useAppRequest } from '@/hooks/useAppRequest'

const { Text } = Typography

const deptSchema = z.object({
  name: z.string().trim().min(1, '请输入部门名称').max(50, '最长 50 字符'),
  sortOrder: z.number({ invalid_type_error: '请输入排序' }).int('请输入整数').min(0, '不能小于 0').max(9999),
  status: z.number().int().min(0).max(1),
})
type DeptForm = z.infer<typeof deptSchema>

export default function DeptFormModal({
  open,
  dept,
  parent,
  onClose,
  onSuccess,
}: {
  open: boolean
  /** 非空 = 编辑 */
  dept: DeptNode | null
  /** 新增时的上级；null = 根部门 */
  parent: DeptNode | null
  onClose: () => void
  onSuccess: (createdId?: number) => void
}) {
  const { message } = App.useApp()
  const { runAsync: doCreate, loading: creating } = useAppRequest(createDept)
  const { runAsync: doUpdate, loading: updating } = useAppRequest(updateDept)

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<DeptForm>({
    resolver: zodResolver(deptSchema),
    defaultValues: { name: '', sortOrder: 0, status: 1 },
  })

  useEffect(() => {
    if (!open) return
    reset({
      name: dept?.name ?? '',
      sortOrder: dept?.sortOrder ?? 0,
      status: dept?.status ?? 1,
    })
  }, [open, dept, reset])

  const onSubmit = async (values: DeptForm) => {
    try {
      if (dept) {
        await doUpdate(dept.id, {
          name: values.name,
          sortOrder: values.sortOrder,
          status: values.status,
        })
        message.success('部门已更新')
        onSuccess()
      } else {
        const id = await doCreate({
          name: values.name,
          parentId: parent?.id ?? 0,
          sortOrder: values.sortOrder,
        })
        message.success('部门已创建')
        onSuccess(id)
      }
      onClose()
    } catch {
      // 失败已由 useAppRequest 统一 toast；保留弹窗让用户改
    }
  }

  return (
    <Modal
      open={open}
      title={dept ? `编辑部门 - ${dept.name}` : parent ? `新增子部门 - ${parent.name}` : '新增根部门'}
      onCancel={onClose}
      onOk={handleSubmit(onSubmit)}
      confirmLoading={creating || updating}
      okText="保存"
      cancelText="取消"
      destroyOnClose
    >
      <div className="space-y-4 pt-2">
        <div>
          <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">上级部门</div>
          <Text type="secondary">{dept ? '父级不可变（本次不改上级）' : parent?.name ?? '根节点'}</Text>
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">部门名称</div>
          <Controller
            name="name"
            control={control}
            render={({ field }) => <Input {...field} placeholder="如：研发部" maxLength={50} />}
          />
          {errors.name && <div className="mt-1 text-xs text-red-500 dark:text-red-400">{errors.name.message}</div>}
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">同级排序（小的在前）</div>
          <Controller
            name="sortOrder"
            control={control}
            render={({ field }) => (
              <InputNumber
                value={field.value}
                onChange={(v) => field.onChange(v ?? 0)}
                onBlur={field.onBlur}
                min={0}
                max={9999}
                className="w-full"
              />
            )}
          />
          {errors.sortOrder && (
            <div className="mt-1 text-xs text-red-500 dark:text-red-400">{errors.sortOrder.message}</div>
          )}
        </div>
        {dept && (
          <div>
            <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">状态</div>
            <Controller
              name="status"
              control={control}
              render={({ field }) => (
                <Space>
                  <Switch
                    checked={field.value === 1}
                    onChange={(checked) => field.onChange(checked ? 1 : 0)}
                  />
                  <Text type="secondary">{field.value === 1 ? '启用' : '停用'}</Text>
                </Space>
              )}
            />
          </div>
        )}
      </div>
    </Modal>
  )
}
