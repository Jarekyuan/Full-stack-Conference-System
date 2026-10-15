/**
 * src/api/types.ts
 * 与后端统一响应结构对齐的 TypeScript 类型，真相源：docs/04-接口设计.md 1.2 / 1.5 / 4.1 / 4.2。
 * 只放"跨接口通用"的结构：Result / PageResult / 分页参数 / 登录与 token 载荷 / 统一业务错误对象。
 * 具体业务实体的类型（Room/Meeting 等）在各自的 api 文件里定义，不塞这里。
 */

/** 后端统一响应包装：code=0 成功，非 0 业务错误码（HTTP 仍为 200） */
export interface Result<T = unknown> {
  code: number
  msg: string
  /** 成功时是业务数据；错误时可能为 null 或携带上下文（如冲突详情） */
  data: T | null
}

/** 分页响应（data 内结构），见 04 文档 1.2 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** 分页请求参数（page 从 1 起，size 上限 100） */
export interface PageQuery {
  page?: number
  size?: number
}

/** 登录响应中的用户简要信息（对应后端 LoginResponse.User） */
export interface LoginUser {
  id: number
  username: string
  realName: string
  /** 角色 code 列表（RBAC 多角色，如 ["ADMIN","APPROVER"]）；前端按"任一匹配"做菜单与守卫 */
  roles: string[]
  /** 所属部门（可空 = 未分配） */
  deptId: number | null
  deptName: string | null
  avatar: string | null
}

/** 登录 / 刷新接口返回的双 token 载荷 */
export interface TokenResult {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: LoginUser
}

/**
 * 统一业务错误对象：由 request.ts 拦截器在 code!=0 时抛出，
 * 让 useAppRequest / 特殊码监听器能用 `instanceof` 精确识别，而不是靠字符串匹配。
 */
export class ApiError extends Error {
  readonly code: number
  readonly data: unknown
  /** 全链路 traceId（网关响应头 X-Trace-Id 透传），排障用 */
  readonly traceId?: string

  constructor(code: number, msg: string, data: unknown = null, traceId?: string) {
    super(msg)
    this.name = 'ApiError'
    this.code = code
    this.data = data
    this.traceId = traceId
  }
}
