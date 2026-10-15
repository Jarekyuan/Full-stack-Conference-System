/**
 * src/api/token.ts
 * 双 token 的本地存取，独立成模块、不依赖 zustand。
 * 原因：request.ts 是模块级代码（拦截器在组件外运行），若它依赖 React 状态会形成循环依赖；
 * 而 token 又是"认证凭证"这一独立关注点，单独用 localStorage 管理最干净。
 * T1.F3 的 userStore 只存用户信息，登录/登出动作调用这里完成 token 的写入与清除。
 */

const ACCESS_TOKEN_KEY = 'meeting.accessToken'
const REFRESH_TOKEN_KEY = 'meeting.refreshToken'

/**
 * 用户快照的持久化键——zustand persist 的 `name`（见 stores/userStore.ts，从本模块导入，避免键写两处）。
 * 放在这里是因为它和两个 token 同属"**会话**在 localStorage 里的落脚点"：
 * 任何一个"退出"路径都必须把它们一起清掉（评审 P1-14）。
 */
export const USER_STORAGE_KEY = 'meeting.user'

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

/**
 * 清空整个会话：token **和**用户快照。
 *
 * 评审 P1-14：此前"刷新失败 → 跳登录"只清了 token，`meeting.user` 原样留在 localStorage 里——
 * 上一登录者的姓名/部门/角色在登录页按 F12 就能看到（共用电脑时是实打实的 PII 残留）。
 * token 与用户信息是同一个会话的两个碎片，"清会话"必须是**一个**动作，故出口收敛到本函数。
 *
 * 注：这里只清持久化层。内存里的 zustand store 由调用方所在的整页跳转
 * （`window.location.replace`）重建，不留旧值。
 */
export function clearSession(): void {
  clearTokens()
  localStorage.removeItem(USER_STORAGE_KEY)
}
