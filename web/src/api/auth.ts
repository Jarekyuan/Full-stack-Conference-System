/**
 * src/api/auth.ts
 * 认证域接口封装，与后端 AuthController 一一对应（路径以 /auth 前缀，网关 StripPrefix 后命中）。
 * 真相源：docs/04-接口设计.md 4.1 / 4.2。
 */
import { http } from './request'
import type { LoginUser, TokenResult } from './types'

/** 登录：成功返回双 token + 用户信息（网关白名单，无需 Authorization） */
export function login(username: string, password: string): Promise<TokenResult> {
  return http.post<TokenResult>('/auth/login', { username, password })
}

/** 登出：让后端删除 Redis 里的 refreshToken 索引（需携带 accessToken） */
export function logout(): Promise<void> {
  return http.post<void>('/auth/logout')
}

/** 当前登录用户信息（刷新页面后可用它恢复用户态，需 accessToken） */
export function getCurrentUser(): Promise<LoginUser> {
  return http.get<LoginUser>('/auth/me')
}
