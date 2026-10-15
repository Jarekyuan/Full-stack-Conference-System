/**
 * src/components/RouteGuards.tsx
 * 路由守卫（对应 02 文档 3.3 #9，从 router.tsx 抽出）：
 * - RequireAuth：无 token 跳 /login，并带上原目标路径（登录后跳回）
 * - RequireRole：用户拥有的任一角色命中白名单即通过（RBAC 多角色）；否则回退日历页
 *
 * 抽出来的原因：守卫是**可复用的路由件**（M3 审批中心还要用同一套），
 * 留在 router.tsx 里既让路由表读起来是"路由 + 两个组件 + JSX"三件事混排，
 * 也踩到 react-refresh 的"文件同时含组件与非组件导出"警告。
 *
 * ⚠️ 这两个守卫只负责 UX（少给无权限的入口）。**权限裁决一律在服务端**：
 * 后端每个写接口都有 `@PreAuthorize` / 归属校验（见 docs/04 各接口的权限列），
 * 绕过前端守卫直接发请求只会拿到 403/40004。
 */
import type { ReactNode } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { getAccessToken } from '@/api/token'
import { useUserStore } from '@/stores/userStore'

/** 鉴权守卫：未登录（无 accessToken）重定向登录页 */
export function RequireAuth() {
  const location = useLocation()
  if (!getAccessToken()) {
    const redirect = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?redirect=${redirect}`} replace />
  }
  return <Outlet />
}

/** 角色守卫：任一角色命中白名单即通过 */
export function RequireRole({ roles: allowed, children }: { roles: string[]; children: ReactNode }) {
  const user = useUserStore((s) => s.user)
  // user.roles 理论上是数组，但持久化里可能残留旧格式（见评审 P1-14 的同源问题），故 `?? []` 兜底
  const hasRole = !!user && (user.roles ?? []).some((role) => allowed.includes(role))
  if (!hasRole) {
    return <Navigate to="/calendar" replace />
  }
  return <>{children}</>
}
