/**
 * src/api/user.ts
 * 用户域接口封装，对应后端 auth-service 的 UserController（网关路由 /api/users/**）。
 * 真相源：docs/04-接口设计.md 3.1（选参会人搜索 + 用户管理 / 组织与用户页）。
 *
 * 两组出参刻意分开：
 * - UserSearchItem：选参会人专用（id/realName/deptName），不装配角色、不含 email；
 * - UserItem：管理端分页专用（含 roles/dept/status），供组织与用户页使用。
 */
import { http } from './request'

/**
 * 选参会人搜索结果（后端 UserSearchVO）。
 * ⚠️ 刻意**不是** `/users` 分页那个 UserVO：搜索路径不装配角色、也不返回 email（评审 P1-13）。
 * 别把 roles/email 加回来——那会让同一个 VO 在两条接口上呈现两种形状（`roles: null` vs `roles: []`），
 * 调用方按 `string[]` 消费就会踩空。
 */
export interface UserSearchItem {
  id: number
  realName: string
  /** 所属部门名；未分配部门时为 null */
  deptName: string | null
}

/** 按姓名/用户名模糊搜索用户（选参会人用；后端最多 20 条，空关键字返回空数组） */
export function searchUsers(keyword: string): Promise<UserSearchItem[]> {
  return http.get<UserSearchItem[]>('/users/search', { params: { keyword } })
}

// ———————————————————— 管理端：组织与用户页（T1.20） ————————————————————

/** 后端 UserVO（分页出参；绝不包含 password） */
export interface UserItem {
  id: number
  username: string
  realName: string
  email: string | null
  avatar: string | null
  roles: string[]
  deptId: number | null
  deptName: string | null
  status: number
}

export interface UserPage {
  list: UserItem[]
  total: number
  page: number
  size: number
}

export interface UserQuery {
  page?: number
  size?: number
  /** 不传 = 全部用户（含未分配部门） */
  deptId?: number
  /** true = 部门筛选包含整棵子树（组织与用户页默认勾选） */
  includeChildren?: boolean
}

export interface RoleItem {
  id: number
  code: string
  name: string
}

export interface UserCreateInput {
  username: string
  password: string
  realName: string
  email?: string
  roleIds: number[]
  deptId?: number
}

/** 编辑用户：用户名/密码不在本接口（docs/04 3.1） */
export interface UserUpdateInput {
  realName: string
  email?: string
  roleIds: number[]
  deptId?: number
}

/** 用户分页（ADMIN；deptId + includeChildren 定部门范围） */
export function listUsers(params: UserQuery): Promise<UserPage> {
  return http.get<UserPage>('/users', { params })
}

export function createUser(input: UserCreateInput): Promise<number> {
  return http.post<number>('/users', input)
}

export function updateUser(id: number, input: UserUpdateInput): Promise<void> {
  return http.put<void>(`/users/${id}`, input)
}

/** 启用/停用（后端拒绝停用自己 → 20006） */
export function updateUserStatus(id: number, status: number): Promise<void> {
  return http.put<void>(`/users/${id}/status`, undefined, { params: { status } })
}

/** 角色字典（用户管理分配角色用；ADMIN） */
export function listRoles(): Promise<RoleItem[]> {
  return http.get<RoleItem[]>('/roles')
}
