/**
 * src/components/UserPicker.tsx
 * 参会人选择器：antd Select(multiple) + **防抖调用 /users/search**（不做本地过滤——用户数据在服务端，
 * 全量拉下来前端过滤在用户量上来后不可行，这是"选人"类组件的标准做法）。
 *
 * 受控契约：只对外收发 number[]（userIds），与后端 v1.3 的 `participants.userIds` 直接对齐，
 * 不把整个 User 对象透传出去（表单只关心 id，展示名由组件内部维护）。
 *
 * 已选但不在当前搜索结果里的用户，靠 seen 表兜住 label——否则用户一改关键字，
 * 已选中的项就会退化成裸 id（antd Select 的经典坑）。
 */
import { useState } from 'react'
import { Select } from 'antd'
import { useDebounceFn } from 'ahooks'
import { searchUsers, type UserSearchItem } from '@/api/user'
import { useAppRequest } from '@/hooks/useAppRequest'

/** 搜索防抖窗口（与项目既有的防抖约定一致，见 docs/05 T2.F2） */
const SEARCH_DEBOUNCE_MS = 500

interface UserPickerProps {
  value?: number[]
  onChange?: (userIds: number[]) => void
  disabled?: boolean
  placeholder?: string
}

export default function UserPicker({ value = [], onChange, disabled, placeholder = '搜索姓名或用户名' }: UserPickerProps) {
  const [options, setOptions] = useState<UserSearchItem[]>([])
  /** id → 用户：累积见过的用户，保证已选项在关键字变化后仍有名字可显示 */
  const [seen, setSeen] = useState<Map<number, UserSearchItem>>(new Map())

  const { run: runSearch, loading } = useAppRequest(searchUsers, {
    onSuccess: (list) => {
      setOptions(list)
      setSeen((prev) => {
        const next = new Map(prev)
        list.forEach((u) => next.set(u.id, u))
        return next
      })
    },
  })

  const { run: debouncedSearch } = useDebounceFn((keyword: string) => {
    if (!keyword.trim()) {
      setOptions([])
      return
    }
    runSearch(keyword)
  }, { wait: SEARCH_DEBOUNCE_MS })

  const labelOf = (u: UserSearchItem) => `${u.realName}${u.deptName ? ` · ${u.deptName}` : ''}`
  // 选项 = 已选中但不在当前结果里的（补 label）+ 当前搜索结果
  const selectedMissing = value
    .map((id) => seen.get(id))
    .filter((u): u is UserSearchItem => !!u && !options.some((o) => o.id === u.id))

  return (
    <Select
      mode="multiple"
      allowClear
      disabled={disabled}
      placeholder={placeholder}
      value={value}
      onChange={(ids: number[]) => onChange?.(ids)}
      onSearch={debouncedSearch}
      // 关掉本地过滤：关键字交给服务端，否则会把"服务端匹配但本地字面不匹配"的项滤掉
      filterOption={false}
      loading={loading}
      options={[...selectedMissing, ...options].map((u) => ({ value: u.id, label: labelOf(u) }))}
      notFoundContent={loading ? '搜索中…' : null}
    />
  )
}
