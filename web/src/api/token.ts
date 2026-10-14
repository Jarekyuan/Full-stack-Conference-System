/**
 * src/api/token.ts
 * 双 token 的本地存取，独立成模块、不依赖 zustand。
 * 原因：request.ts 是模块级代码（拦截器在组件外运行），若它依赖 React 状态会形成循环依赖；
 * 而 token 又是"认证凭证"这一独立关注点，单独用 localStorage 管理最干净。
 * T1.F3 的 userStore 只存用户信息，登录/登出动作调用这里完成 token 的写入与清除。
 */

const ACCESS_TOKEN_KEY = 'meeting.accessToken'
const REFRESH_TOKEN_KEY = 'meeting.refreshToken'

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setTokens(accessToken: string, refreshToken: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}
