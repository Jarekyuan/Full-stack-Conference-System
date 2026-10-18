/**
 * 「组织与用户」页的用户新增/编辑弹窗（从 OrgUsers.tsx 拆出，O14）。
 * 只负责表单；角色/部门选项由页面传入，成功后由页面刷新树与成员表。
 */
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { App, Input, Modal, Select, TreeSelect } from 'antd'
import { createUser, updateUser, type RoleItem, type UserItem } from '@/api/user'
import type { DeptNode } from '@/api/dept'
import { useAppRequest } from '@/hooks/useAppRequest'

const userSchema = z.object({
  username: z.string().trim().min(1, '请输入用户名').max(50, '最长 50 字符'),
  password: z.string().max(32, '最长 32 位').optional(),
  realName: z.string().trim().min(1, '请输入姓名').max(50, '最长 50 字符'),
  email: z
    .string()
    .trim()
    .max(100, '最长 100 字符')
    .refine((v) => v === '' || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v), '邮箱格式不正确')
    .optional(),
  roleIds: z.array(z.number()).min(1, '至少分配一个角色'),
  deptId: z.number().int().positive().nullable().optional(),
})
type UserForm = z.infer<typeof userSchema>

interface DeptTreeOption {
  title: string
  value: number
  children?: DeptTreeOption[]
}

function toDeptTreeOptions(nodes: DeptNode[]): DeptTreeOption[] {
  return nodes.map((n) => ({
    title: n.name,
    value: n.id,
    children: n.children.length > 0 ? toDeptTreeOptions(n.children) : undefined,
  }))
}

export default function UserFormModal({
  open,
  user,
  defaultDeptId,
  roles,
  deptTree,
  currentUserId,
  onClose,
  onSuccess,
}: {
  open: boolean
  /** 非空 = 编辑 */
  user: UserItem | null
  defaultDeptId?: number
  roles: RoleItem[]
  deptTree: DeptNode[]
  currentUserId?: number
  onClose: () => void
  onSuccess: () => void
}) {
  const { message } = App.useApp()
  const { runAsync: doCreate, loading: creating } = useAppRequest(createUser)
  const { runAsync: doUpdate, loading: updating } = useAppRequest(updateUser)

  const {
    control,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<UserForm>({
    resolver: zodResolver(userSchema),
    defaultValues: {
      username: '',
      password: '',
      realName: '',
      email: '',
      roleIds: [],
      deptId: defaultDeptId ?? null,
    },
  })

  // 角色 code → id：编辑回显用的 UserVO 只有 code，下拉需要 id
  const roleIdsOf = (u: UserItem | null): number[] =>
    (u?.roles ?? [])
      .map((code) => roles.find((r) => r.code === code)?.id)
      .filter((id): id is number => id !== undefined)

  useEffect(() => {
    if (!open) return
    reset({
      username: user?.username ?? '',
      password: '',
      realName: user?.realName ?? '',
      email: user?.email ?? '',
      roleIds: roleIdsOf(user),
      deptId: user ? user.deptId : defaultDeptId ?? null,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, user, defaultDeptId, roles, reset])

  const onSubmit = async (values: UserForm) => {
    // 密码只在创建时校验：编辑不碰密码（docs/04 3.1）
    if (!user && (!values.password || values.password.length < 6)) {
      setError('password', { message: '密码长度至少 6 位' })
      return
    }
    try {
      const email = values.email?.trim() || undefined
      const deptId = values.deptId ?? undefined
      if (user) {
        await doUpdate(user.id, {
          realName: values.realName,
          email,
          roleIds: values.roleIds,
          deptId,
        })
        message.success('用户已更新')
      } else {
        await doCreate({
          username: values.username,
          password: values.password!,
          realName: values.realName,
          email,
          roleIds: values.roleIds,
          deptId,
        })
        message.success('用户已创建')
      }
      onSuccess()
      onClose()
    } catch {
      // 已 toast（如 20005 用户名已存在 / 20006 不能移除自己的 ADMIN 角色）
    }
  }

  const isSelf = !!user && user.id === currentUserId

  return (
    <Modal
      open={open}
      title={user ? `编辑用户 - ${user.username}` : '新增用户'}
      onCancel={onClose}
      onOk={handleSubmit(onSubmit)}
      confirmLoading={creating || updating}
      okText="保存"
      cancelText="取消"
      destroyOnClose
    >
      <div className="space-y-4 pt-2">
        <div>
          <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">用户名（登录名）</div>
          <Controller
            name="username"
            control={control}
            render={({ field }) =>
              user ? (
                <Input value={field.value} disabled />
              ) : (
                <Input {...field} placeholder="登录名，创建后不可修改" maxLength={50} />
              )
            }
          />
          {errors.username && (
            <div className="mt-1 text-xs text-red-500 dark:text-red-400">{errors.username.message}</div>
          )}
        </div>
        {!user && (
          <div>
            <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">初始密码</div>
            <Controller
              name="password"
              control={control}
              render={({ field }) => (
                <Input.Password {...field} placeholder="6-32 位" maxLength={32} />
              )}
            />
            {errors.password && (
              <div className="mt-1 text-xs text-red-500 dark:text-red-400">{errors.password.message}</div>
            )}
          </div>
        )}
        <div>
          <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">姓名</div>
          <Controller
            name="realName"
            control={control}
            render={({ field }) => <Input {...field} placeholder="真实姓名" maxLength={50} />}
          />
          {errors.realName && (
            <div className="mt-1 text-xs text-red-500 dark:text-red-400">{errors.realName.message}</div>
          )}
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">邮箱（通知用，可空）</div>
          <Controller
            name="email"
            control={control}
            render={({ field }) => <Input {...field} placeholder="name@example.com" maxLength={100} />}
          />
          {errors.email && <div className="mt-1 text-xs text-red-500 dark:text-red-400">{errors.email.message}</div>}
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">所属部门</div>
          <Controller
            name="deptId"
            control={control}
            render={({ field }) => (
              <TreeSelect
                value={field.value ?? undefined}
                onChange={(v) => field.onChange(v ?? null)}
                treeData={toDeptTreeOptions(deptTree)}
                treeDefaultExpandAll
                allowClear
                placeholder="未分配部门"
                className="w-full"
              />
            )}
          />
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600 dark:text-slate-300">角色（至少一个）</div>
          <Controller
            name="roleIds"
            control={control}
            render={({ field }) => (
              <Select
                mode="multiple"
                value={field.value}
                onChange={field.onChange}
                options={roles.map((r) => ({
                  label: `${r.name}（${r.code}）`,
                  value: r.id,
                  // 编辑自己时 ADMIN 不可取消：后端 20006 兜底，UI 不再给误操作入口（O11）
                  disabled: isSelf && r.code === 'ADMIN',
                }))}
                placeholder="请选择角色"
                className="w-full"
              />
            )}
          />
          {errors.roleIds && (
            <div className="mt-1 text-xs text-red-500 dark:text-red-400">{errors.roleIds.message}</div>
          )}
          {isSelf && (
            <div className="mt-1 text-xs text-amber-600 dark:text-amber-400">
              不能移除自己的 ADMIN 角色（否则会把自己锁在管理端之外）
            </div>
          )}
        </div>
      </div>
    </Modal>
  )
}
