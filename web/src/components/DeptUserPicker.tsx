/**
 * src/components/DeptUserPicker.tsx
 * 按部门树选参会人（T6.1）：弹窗左侧部门树、右侧该部门用户多选列表 + 搜索。
 *
 * 为什么替换原来的搜索式 UserPicker：
 * ① 用户要求“先看部门，再选部门下的人”，树比关键词搜索更符合组织结构的直觉；
 * ② 原实现用一个 seen 表兜已选标签，换关键字后旧结果/旧选项会留在组件里，看起来像“有缓存”；
 * ③ 现在跨部门选择时只在 selectedMap 里保留**已选用户**，未选中的旧结果不再渲染。
 *
 * 后端契约：GET /users/search?deptId=&keyword=（登录即可；只列直属成员，最多 50 条，不含 email）。
 * 只对外收发 userIds，表单不接触用户对象。
 */
import { useEffect, useMemo, useState } from 'react'
import { Button, Checkbox, Empty, Input, List, Modal, Spin, Tag, Tree, Typography } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { Users } from 'lucide-react'
import { useDebounceFn } from 'ahooks'
import { fetchDeptTree, type DeptNode } from '@/api/dept'
import { searchUsers, type UserSearchItem } from '@/api/user'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'

const { Text } = Typography
const SEARCH_DEBOUNCE_MS = 400

interface DeptUserPickerProps {
  value?: number[]
  onChange?: (userIds: number[]) => void
  disabled?: boolean
}

/** 部门树 → antd Tree 数据（带直属人数，方便判断哪个部门有人） */
function toTreeData(nodes: DeptNode[]): DataNode[] {
  return nodes.map((node) => ({
    key: String(node.id),
    title: `${node.name}（${node.userCount}）`,
    children: node.children.length > 0 ? toTreeData(node.children) : undefined,
  }))
}

export default function DeptUserPicker({ value = [], onChange, disabled }: DeptUserPickerProps) {
  const currentDeptId = useUserStore((s) => s.user?.deptId ?? undefined)

  const [open, setOpen] = useState(false)
  /** 弹窗内的草稿：取消不落表单 */
  const [draft, setDraft] = useState<number[]>([])
  const [selectedMap, setSelectedMap] = useState<Map<number, UserSearchItem>>(new Map())
  const [deptId, setDeptId] = useState<number | undefined>(undefined)
  const [keyword, setKeyword] = useState('')

  const { data: deptTree, loading: treeLoading } = useAppRequest(fetchDeptTree, { manual: false })
  const { data: users, loading: usersLoading, run: runSearch } = useAppRequest(searchUsers)

  // 是否处于“有查询条件”的状态：都没有时右侧不展示上一次的旧结果
  const active = deptId != null || keyword.trim().length > 0
  const visibleUsers = active ? users ?? [] : []

  const selectedIds = useMemo(() => new Set(draft), [draft])
  const labelOf = (id: number) => selectedMap.get(id)?.realName ?? `用户#${id}`

  const { run: debouncedSearch, cancel: cancelDebouncedSearch } = useDebounceFn(
    (kw: string, dept: number | undefined) => {
      if (!kw.trim() && dept == null) return
      runSearch(kw.trim() || undefined, dept)
    },
    { wait: SEARCH_DEBOUNCE_MS },
  )

  // 打开时：草稿 = 当前值；若从没选过，默认落到“我的部门”，避免首次打开是一片空白
  useEffect(() => {
    if (!open) return
    setDraft(value)
    if (value.length === 0) {
      setSelectedMap(new Map())
    }
    if (deptId == null && !keyword.trim() && currentDeptId != null) {
      setDeptId(currentDeptId)
      runSearch(undefined, currentDeptId)
    } else if (deptId != null || keyword.trim()) {
      // 重开弹窗时按上次的部门/关键字重查，避免展示关闭前的旧列表
      runSearch(keyword.trim() || undefined, deptId)
    }
    // 只在打开时做一次初始化；deptId/keyword 的变化由各自的交互驱动
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const toggle = (user: UserSearchItem) => {
    setDraft((prev) =>
      prev.includes(user.id) ? prev.filter((id) => id !== user.id) : [...prev, user.id],
    )
    setSelectedMap((prev) => {
      const next = new Map(prev)
      next.set(user.id, user)
      return next
    })
  }

  const selectDept = (key: string) => {
    // R16：切换部门前取消仍在排队的“旧部门 + 关键字”防抖请求，
    // 否则它会在 400ms 后成为“最后一次请求”，把新部门的结果覆盖掉。
    cancelDebouncedSearch()
    const nextDept = key === 'all' ? undefined : Number(key)
    setDeptId(nextDept)
    if (nextDept == null && !keyword.trim()) {
      return // 全部部门 + 无关键字：右侧提示“输入关键字”，不查全表
    }
    runSearch(keyword.trim() || undefined, nextDept)
  }

  const confirm = () => {
    onChange?.(draft)
    setOpen(false)
  }

  const treeData: DataNode[] = [
    { key: 'all', title: '全部部门（搜索）' },
    ...toTreeData(deptTree ?? []),
  ]

  return (
    <>
      <div className="flex flex-wrap items-center gap-1">
        {value.map((id) => (
          <Tag
            key={id}
            closable={!disabled}
            onClose={() =>
              onChange?.(value.filter((selected) => selected !== id))
            }
          >
            {labelOf(id)}
          </Tag>
        ))}
        <Button
          size="small"
          icon={<Users size={14} />}
          disabled={disabled}
          onClick={() => setOpen(true)}
        >
          选择参会人
        </Button>
      </div>

      <Modal
        open={open}
        title="选择参会人"
        width={760}
        onCancel={() => setOpen(false)}
        onOk={confirm}
        okText={`确定（${draft.length} 人）`}
        cancelText="取消"
        destroyOnClose
      >
        <div className="mb-2 flex min-h-[28px] flex-wrap items-center gap-1 rounded bg-slate-50 dark:bg-slate-900 px-2 py-1">
          {draft.length === 0 ? (
            <Text type="secondary" className="text-xs">
              还未选择参会人
            </Text>
          ) : (
            draft.map((id) => (
              <Tag
                key={id}
                closable
                onClose={() => setDraft((prev) => prev.filter((selected) => selected !== id))}
              >
                {labelOf(id)}
              </Tag>
            ))
          )}
        </div>

        <div className="flex gap-4">
          {/* 左：部门树 */}
          <div className="w-56 shrink-0 border-r border-slate-100 dark:border-slate-800 pr-3">
            <Spin spinning={treeLoading}>
              <Tree
                blockNode
                defaultExpandAll
                selectedKeys={[deptId == null ? 'all' : String(deptId)]}
                treeData={treeData}
                onSelect={(keys) => {
                  const key = keys[0]
                  if (key !== undefined) selectDept(String(key))
                }}
              />
            </Spin>
            <Text type="secondary" className="mt-2 block text-xs">
              部门人数含停用用户；右侧只列启用成员
            </Text>
          </div>

          {/* 右：该部门用户 / 搜索结果 */}
          <div className="min-w-0 flex-1">
            <Input
              allowClear
              value={keyword}
              placeholder="搜索姓名或用户名（当前部门内；跨部门请选“全部部门”）"
              onChange={(e) => {
                setKeyword(e.target.value)
                debouncedSearch(e.target.value, deptId)
              }}
              className="mb-2"
            />
            <div className="h-72 overflow-y-auto">
              {!active ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择部门或输入关键字" />
              ) : (
                <List
                  loading={usersLoading}
                  dataSource={visibleUsers}
                  locale={{ emptyText: '该范围暂无用户' }}
                  renderItem={(user) => (
                    <List.Item className="!px-1">
                      <Checkbox
                        checked={selectedIds.has(user.id)}
                        onChange={() => toggle(user)}
                      >
                        <span className="mr-2">{user.realName}</span>
                        <Text type="secondary" className="text-xs">
                          {user.deptName ?? '未分配部门'}
                        </Text>
                      </Checkbox>
                    </List.Item>
                  )}
                />
              )}
            </div>
          </div>
        </div>
      </Modal>
    </>
  )
}
