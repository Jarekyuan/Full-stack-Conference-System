/**
 * src/api/user.ts
 * 用户域接口封装，对应后端 auth-service 的 UserController（网关路由 /api/users/**）。
 * 真相源：docs/04-接口设计.md 3.1「附：选参会人搜索」+ 后端 UserSearchVO。
 * 目前只用到"选参会人搜索"；用户管理页（ADMIN）的接口待前端任务 T1.20 再补。
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
