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
import {
  Alert,
  App,
  Button,
  Checkbox,
  Dropdown,
  Empty,
  Spin,
  Switch,
  Table,
  Tag,
  Tree,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { DataNode } from 'antd/es/tree'
import { MoreHorizontal, Plus } from 'lucide-react'
import { deleteDept, fetchDeptTree, type DeptNode } from '@/api/dept'
import { listRoles, listUsers, updateUserStatus, type UserItem } from '@/api/user'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'
import DeptFormModal from './DeptFormModal'
import UserFormModal from './UserFormModal'

const { Text } = Typography

const ROLE_COLOR: Record<string, string> = { ADMIN: 'red', APPROVER: 'blue', USER: 'default' }

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

  const { data: deptTree, loading: treeLoading, run: loadTree, error: treeError } = useAppRequest(
    fetchDeptTree,
    { manual: false },
  )
  const { data: roles } = useAppRequest(listRoles, { manual: false })
  const { data: userPage, loading: usersLoading, run: loadUsers, error: usersError } =
    useAppRequest(listUsers)
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
      <div className="w-72 shrink-0 border-r border-slate-100 dark:border-slate-800 pr-4">
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
        {treeError && (
          <Alert
            type="error"
            showIcon
            className="mb-2"
            message="部门树加载失败"
            action={<Button size="small" onClick={() => loadTree()}>重试</Button>}
          />
        )}
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

        {usersError && (
          <Alert
            type="error"
            showIcon
            className="mb-3"
            message="成员列表加载失败"
            action={<Button size="small" onClick={() => loadUsers(query())}>重试</Button>}
          />
        )}
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
      <Button type="text" size="small" aria-label="部门操作" icon={<MoreHorizontal size={14} />} />
    </Dropdown>
  )
}
