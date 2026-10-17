/**
 * src/api/dept.ts
 * 部门域接口封装，对应 auth-service 的 DeptController（网关路由 /api/depts/**）。
 * 真相源：docs/04-接口设计.md 3.1「用户管理 / 组织与用户页」。
 */
import { http } from './request'

/** 部门树节点（后端 DeptVO）：userCount = 直属成员数（不含子部门） */
export interface DeptNode {
  id: number
  parentId: number
  name: string
  sortOrder: number
  status: number
  userCount: number
  children: DeptNode[]
}

export interface DeptSaveInput {
  name: string
  /** 父部门 ID（0 = 根；创建时有效，编辑时后端忽略——父级不可变） */
  parentId?: number
  sortOrder?: number
  /** 0 停用 / 1 启用（编辑时可选） */
  status?: number
}

/** 部门树（登录即可访问；组织与用户页、选人场景共用） */
export function fetchDeptTree(): Promise<DeptNode[]> {
  return http.get<DeptNode[]>('/depts/tree')
}

export function createDept(input: DeptSaveInput): Promise<number> {
  return http.post<number>('/depts', input)
}

/** 编辑部门（只改名称/排序/状态；父部门不可变，见 docs/04） */
export function updateDept(id: number, input: DeptSaveInput): Promise<void> {
  return http.put<void>(`/depts/${id}`, input)
}

/** 删除部门（存在子部门或挂靠用户时后端拒绝，错误信息可直接展示） */
export function deleteDept(id: number): Promise<void> {
  return http.delete<void>(`/depts/${id}`)
}
