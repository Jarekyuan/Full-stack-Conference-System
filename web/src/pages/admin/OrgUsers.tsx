/**
 * src/pages/admin/OrgUsers.tsx
 * 「组织与用户」页（ADMIN，T1.20）：左侧部门树 + 右侧成员表，**一页**完成
 * “部门树维护 + 用户绑定部门/角色 + 启停”。
 *
 * 三个取舍（对应 docs/05 T1.20 的定稿）：
 * ① **单页而非两页**：部门与成员是一件事——给用户改部门时，左边树就在手边；
 *    拆成“用户管理 / 部门管理”两页会让两棵树重复，绑定关系要来回切页。
 * ② **树节点带直属人数**（后端 `/depts/tree` 的 `userCount`）：不这么做，前端要为每个节点各发一次
 *    计数请求（N+1），或者拿分页列表在客户端统计（翻页后就不准了）。
 * ③ **“含子部门”交给后端展开**（`includeChildren=true`）：前端过滤只能过滤当前页，
 *    分页场景下必然算错；后端在内存里展开部门树后传 `IN`，才是完整结果。
 */
import { useEffect, useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  App,
  Button,
  Checkbox,
  Dropdown,
  Empty,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Tree,
  TreeSelect,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { DataNode } from 'antd/es/tree'
import { MoreHorizontal, Plus } from 'lucide-react'
import { createDept, deleteDept, fetchDeptTree, updateDept, type DeptNode } from '@/api/dept'
import {
  createUser,
  listRoles,
  listUsers,
  updateUser,
  updateUserStatus,
  type RoleItem,
  type UserItem,
} from '@/api/user'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'

const { Text } = Typography

const ROLE_COLOR: Record<string, string> = { ADMIN: 'red', APPROVER: 'blue', USER: 'default' }

// ———————————————————— 部门表单 ————————————————————

const deptSchema = z.object({
  name: z.string().trim().min(1, '请输入部门名称').max(50, '最长 50 字符'),
  sortOrder: z.number({ invalid_type_error: '请输入排序' }).int('请输入整数').min(0, '不能小于 0').max(9999),
  status: z.number().int().min(0).max(1),
})
type DeptForm = z.infer<typeof deptSchema>

function DeptFormModal({
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
          <div className="mb-1 text-sm text-slate-600">上级部门</div>
          <Text type="secondary">{dept ? '父级不可变（本次不改上级）' : parent?.name ?? '根节点'}</Text>
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600">部门名称</div>
          <Controller
            name="name"
            control={control}
            render={({ field }) => <Input {...field} placeholder="如：研发部" maxLength={50} />}
          />
          {errors.name && <div className="mt-1 text-xs text-red-500">{errors.name.message}</div>}
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600">同级排序（小的在前）</div>
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
            <div className="mt-1 text-xs text-red-500">{errors.sortOrder.message}</div>
          )}
        </div>
        {dept && (
          <div>
            <div className="mb-1 text-sm text-slate-600">状态</div>
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

// ———————————————————— 用户表单 ————————————————————

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

function UserFormModal({
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
          <div className="mb-1 text-sm text-slate-600">用户名（登录名）</div>
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
            <div className="mt-1 text-xs text-red-500">{errors.username.message}</div>
          )}
        </div>
        {!user && (
          <div>
            <div className="mb-1 text-sm text-slate-600">初始密码</div>
            <Controller
              name="password"
              control={control}
              render={({ field }) => (
                <Input.Password {...field} placeholder="6-32 位" maxLength={32} />
              )}
            />
            {errors.password && (
              <div className="mt-1 text-xs text-red-500">{errors.password.message}</div>
            )}
          </div>
        )}
        <div>
          <div className="mb-1 text-sm text-slate-600">姓名</div>
          <Controller
            name="realName"
            control={control}
            render={({ field }) => <Input {...field} placeholder="真实姓名" maxLength={50} />}
          />
          {errors.realName && (
            <div className="mt-1 text-xs text-red-500">{errors.realName.message}</div>
          )}
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600">邮箱（通知用，可空）</div>
          <Controller
            name="email"
            control={control}
            render={({ field }) => <Input {...field} placeholder="name@example.com" maxLength={100} />}
          />
          {errors.email && <div className="mt-1 text-xs text-red-500">{errors.email.message}</div>}
        </div>
        <div>
          <div className="mb-1 text-sm text-slate-600">所属部门</div>
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
          <div className="mb-1 text-sm text-slate-600">角色（至少一个）</div>
          <Controller
            name="roleIds"
            control={control}
            render={({ field }) => (
              <Select
                mode="multiple"
                value={field.value}
                onChange={field.onChange}
                options={roles.map((r) => ({ label: `${r.name}（${r.code}）`, value: r.id }))}
                placeholder="请选择角色"
                className="w-full"
              />
            )}
          />
          {errors.roleIds && (
            <div className="mt-1 text-xs text-red-500">{errors.roleIds.message}</div>
          )}
          {isSelf && (
            <div className="mt-1 text-xs text-amber-600">
              不能移除自己的 ADMIN 角色（否则会把自己锁在管理端之外）
            </div>
          )}
        </div>
      </div>
    </Modal>
  )
}

// ———————————————————— 页面 ————————————————————

function findDept(nodes: DeptNode[], id: number): DeptNode | null {
  for (const node of nodes) {
    if (node.id === id) return node
    const found = findDept(node.children, id)
    if (found) return found
  }
  return null
}

/** 树上展示"该部门及子部门"的总人数（组织架构的常规口径）；接口的 userCount 是直属人数 */
function subtreeUserCount(node: DeptNode): number {
  return node.userCount + node.children.reduce((sum, child) => sum + subtreeUserCount(child), 0)
}

export default function OrgUsers() {
  const { message, modal } = App.useApp()
  const currentUserId = useUserStore((s) => s.user?.id)

  const [selectedDeptId, setSelectedDeptId] = useState<number | 'all'>('all')
  const [includeChildren, setIncludeChildren] = useState(true)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [deptModal, setDeptModal] = useState<
    { mode: 'create'; parent: DeptNode | null } | { mode: 'edit'; node: DeptNode } | null
  >(null)
  const [userModal, setUserModal] = useState<{ mode: 'create' } | { mode: 'edit'; user: UserItem } | null>(
    null,
  )
  const [statusLoadingId, setStatusLoadingId] = useState<number | null>(null)

  const { data: deptTree, loading: treeLoading, run: loadTree } = useAppRequest(fetchDeptTree, {
    manual: false,
  })
  const { data: roles } = useAppRequest(listRoles, { manual: false })
  const { data: userPage, loading: usersLoading, run: loadUsers } = useAppRequest(listUsers)
  const { runAsync: doUpdateStatus } = useAppRequest(updateUserStatus)
  const { runAsync: doDeleteDept } = useAppRequest(deleteDept)

  const deptIdFilter = selectedDeptId === 'all' ? undefined : selectedDeptId
  const query = (p = page, s = size) => ({
    page: p,
    size: s,
    deptId: deptIdFilter,
    includeChildren,
  })

  // 部门/子树/分页变化 → 重查成员（依赖写具体字段，不写 query 函数）
  useEffect(() => {
    loadUsers(query())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deptIdFilter, includeChildren, page, size, loadUsers])

  // 编辑用户把唯一一页的人移走后，当前页可能越界 → 回退到最后一页
  useEffect(() => {
    if (!userPage) return
    const maxPage = Math.max(1, Math.ceil(userPage.total / size))
    if (page > maxPage) setPage(maxPage)
  }, [userPage, page, size])

  const selectedDept = useMemo(
    () => (selectedDeptId === 'all' ? null : findDept(deptTree ?? [], selectedDeptId)),
    [deptTree, selectedDeptId],
  )
  const parentDept = selectedDept ? findDept(deptTree ?? [], selectedDept.parentId) : null

  const refreshUsers = () => loadUsers(query())
  const refreshAll = () => {
    loadTree()
    refreshUsers()
  }

  const handleStatusChange = async (user: UserItem, checked: boolean) => {
    setStatusLoadingId(user.id)
    try {
      await doUpdateStatus(user.id, checked ? 1 : 0)
      message.success(checked ? '已启用' : '已停用')
      refreshUsers()
    } catch {
      // 已 toast（如 20006 不能停用自己）；刷新一次让开关回到真实状态
      refreshUsers()
    } finally {
      setStatusLoadingId(null)
    }
  }

  const confirmDeleteDept = (node: DeptNode) => {
    modal.confirm({
      title: `删除部门「${node.name}」？`,
      content: '存在子部门或挂靠用户时无法删除——请先移走成员/子部门。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      // onOk 返回 rejected Promise 时 antd 会保持弹窗打开：失败（如“部门下存在用户”）
      // 让用户看清提示后再决定，而不是关掉弹窗让他以为删成功了
      onOk: async () => {
        await doDeleteDept(node.id)
        message.success('部门已删除')
        const wasSelected = selectedDeptId === node.id
        if (wasSelected) {
          // 选中项被删 → 回到“全部用户”；成员列表由 selectedDeptId 变化的 effect 重查
          setSelectedDeptId('all')
          setPage(1)
        } else {
          refreshUsers()
        }
        loadTree()
      },
    })
  }

  const treeData: DataNode[] = [
    {
      key: 'all',
      title: (
        <span className="flex items-center justify-between">
          <span>全部用户</span>
          {selectedDeptId === 'all' && userPage ? (
            <Text type="secondary" className="text-xs">
              {userPage.total}
            </Text>
          ) : null}
        </span>
      ),
    },
    ...(deptTree ?? []).map(function toNode(node): DataNode {
      return {
        key: String(node.id),
        title: (
          <span className="group flex items-center justify-between gap-1 pr-1">
            <span className="flex min-w-0 items-center gap-1">
              <span className="truncate">{node.name}</span>
              {node.status === 0 && <Tag className="m-0">停用</Tag>}
              <Text type="secondary" className="text-xs">
                {subtreeUserCount(node)}
              </Text>
            </span>
            <span onClick={(e) => e.stopPropagation()}>
              <DropdownActions
                onAddChild={() => setDeptModal({ mode: 'create', parent: node })}
                onEdit={() => setDeptModal({ mode: 'edit', node })}
                onDelete={() => confirmDeleteDept(node)}
              />
            </span>
          </span>
        ),
        children: node.children.map(toNode),
      }
    }),
  ]

  const columns: ColumnsType<UserItem> = [
    {
      title: '姓名',
      dataIndex: 'realName',
      width: 160,
      render: (_, record) => (
        <div>
          <div>{record.realName}</div>
          <Text type="secondary" className="text-xs">
            {record.username}
          </Text>
        </div>
      ),
    },
    {
      title: '部门',
      dataIndex: 'deptName',
      width: 140,
      render: (name: string | null) => name ?? <Text type="secondary">未分配</Text>,
    },
    {
      title: '角色',
      dataIndex: 'roles',
      width: 200,
      render: (codes: string[]) => (
        <>
          {codes.map((code) => (
            <Tag key={code} color={ROLE_COLOR[code] ?? 'default'} className="mr-1">
              {code}
            </Tag>
          ))}
        </>
      ),
    },
    {
      title: '邮箱',
      dataIndex: 'email',
      ellipsis: true,
      render: (email: string | null) => email ?? <Text type="secondary">—</Text>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (_, record) => {
        const isSelf = record.id === currentUserId
        return (
          <Switch
            size="small"
            checked={record.status === 1}
            disabled={isSelf}
            loading={statusLoadingId === record.id}
            onChange={(checked) => handleStatusChange(record, checked)}
            title={isSelf ? '不能停用自己' : undefined}
          />
        )
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => setUserModal({ mode: 'edit', user: record })}>
          编辑
        </Button>
      ),
    },
  ]

  return (
    <div className="flex gap-4">
      {/* 左：部门树 */}
      <div className="w-72 shrink-0 border-r border-slate-100 pr-4">
        <div className="mb-2 flex items-center justify-between">
          <h2 className="text-base font-medium">组织架构</h2>
          <Button
            type="text"
            size="small"
            icon={<Plus size={14} />}
            onClick={() => setDeptModal({ mode: 'create', parent: null })}
          >
            根部门
          </Button>
        </div>
        <Spin spinning={treeLoading}>
          {deptTree && deptTree.length > 0 ? (
            <Tree
              blockNode
              defaultExpandAll
              selectedKeys={[String(selectedDeptId)]}
              treeData={treeData}
              onSelect={(keys) => {
                const key = keys[0]
                if (key === undefined) return // 点空白不取消选择
                if (key === 'all') {
                  setSelectedDeptId('all')
                } else {
                  setSelectedDeptId(Number(key))
                }
                setPage(1)
              }}
            />
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无部门" />
          )}
        </Spin>
        <Text type="secondary" className="mt-3 block text-xs">
          悬浮节点可新增子部门 / 编辑 / 删除；数字为该部门及子部门的成员数
        </Text>
      </div>

      {/* 右：部门信息 + 成员表 */}
      <div className="min-w-0 flex-1">
        <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-lg font-medium">
                {selectedDept ? selectedDept.name : '全部用户'}
              </h2>
              {selectedDept && (
                <Tag color={selectedDept.status === 1 ? 'green' : 'default'}>
                  {selectedDept.status === 1 ? '启用' : '停用'}
                </Tag>
              )}
              {selectedDept && (
                <Text type="secondary" className="text-xs">
                  上级：{parentDept?.name ?? '—'} · 直属 {selectedDept.userCount} 人
                </Text>
              )}
            </div>
            {selectedDept && (
              <div className="mt-2 flex items-center gap-2">
                <Button size="small" onClick={() => setDeptModal({ mode: 'edit', node: selectedDept })}>
                  编辑部门
                </Button>
                <Button
                  size="small"
                  onClick={() => setDeptModal({ mode: 'create', parent: selectedDept })}
                >
                  新增子部门
                </Button>
                <Button size="small" danger onClick={() => confirmDeleteDept(selectedDept)}>
                  删除部门
                </Button>
              </div>
            )}
          </div>
          <div className="flex items-center gap-3">
            {selectedDept && (
              <Checkbox
                checked={includeChildren}
                onChange={(e) => {
                  setIncludeChildren(e.target.checked)
                  setPage(1)
                }}
              >
                含子部门
              </Checkbox>
            )}
            <Button
              type="primary"
              icon={<Plus size={16} />}
              onClick={() => setUserModal({ mode: 'create' })}
            >
              新增用户
            </Button>
          </div>
        </div>

        <div className="mb-2">
          <Text type="secondary" className="text-xs">
            当前范围 {userPage?.total ?? 0} 人
          </Text>
        </div>

        <Table<UserItem>
          rowKey="id"
          size="middle"
          loading={usersLoading}
          columns={columns}
          dataSource={userPage?.list ?? []}
          pagination={{
            current: page,
            pageSize: size,
            total: userPage?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 人`,
            onChange: (nextPage, nextSize) => {
              setPage(nextSize !== size ? 1 : nextPage)
              setSize(nextSize)
            },
          }}
        />
      </div>

      <DeptFormModal
        open={!!deptModal}
        dept={deptModal?.mode === 'edit' ? deptModal.node : null}
        parent={deptModal?.mode === 'create' ? deptModal.parent : null}
        onClose={() => setDeptModal(null)}
        onSuccess={(createdId) => {
          loadTree()
          if (createdId) {
            // 新建后自动选中它；成员列表由 selectedDeptId 变化的 effect 重查
            setSelectedDeptId(createdId)
            setPage(1)
          } else {
            refreshUsers()
          }
        }}
      />

      <UserFormModal
        open={!!userModal}
        user={userModal?.mode === 'edit' ? userModal.user : null}
        defaultDeptId={selectedDept ? selectedDept.id : undefined}
        roles={roles ?? []}
        deptTree={deptTree ?? []}
        currentUserId={currentUserId}
        onClose={() => setUserModal(null)}
        onSuccess={refreshAll}
      />
    </div>
  )
}

/** 树节点悬浮操作（下拉）：放在独立组件里，避免每个节点都重复写 Dropdown 配置 */
function DropdownActions({
  onAddChild,
  onEdit,
  onDelete,
}: {
  onAddChild: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  return (
    <Dropdown
      trigger={['click']}
      menu={{
        items: [
          { key: 'add', label: '新增子部门', icon: <Plus size={14} /> },
          { key: 'edit', label: '编辑' },
          { key: 'delete', label: '删除', danger: true },
        ],
        onClick: ({ key, domEvent }) => {
          domEvent.stopPropagation()
          if (key === 'add') onAddChild()
          if (key === 'edit') onEdit()
          if (key === 'delete') onDelete()
        },
      }}
    >
      <Button type="text" size="small" icon={<MoreHorizontal size={14} />} />
    </Dropdown>
  )
}
