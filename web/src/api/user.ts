/**
 * src/api/user.ts
 * 用户域接口封装，对应后端 auth-service 的 UserController（网关路由 /api/users/**）。
 * 真相源：docs/04-接口设计.md 3.1 + 后端 UserVO 字段。
 * 目前只用到"选参会人搜索"；用户管理页（ADMIN）的接口待前端任务 T1.20 再补。
 */
import { http } from './request'

/** 用户信息（后端 UserVO，不含密码等敏感字段） */
export interface User {
  id: number
  username: string
  realName: string
  email: string | null
  avatar: string | null
  /** 角色 code 列表（RBAC 多角色） */
  roles: string[]
  deptId: number | null
  deptName: string | null
  /** 0 启用 / 1 停用 */
  status: number
}

/** 按姓名/用户名模糊搜索用户（选参会人用；后端上限内部处理，前端按关键字调用即可） */
export function searchUsers(keyword: string): Promise<User[]> {
  return http.get<User[]>('/users/search', { params: { keyword } })
}
