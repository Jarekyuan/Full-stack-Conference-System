/**
 * src/stores/userStore.ts
 * 客户端全局状态：当前登录用户。职责边界（02 文档 3.3 #4）：
 * - 只存"客户端状态"（用户信息），不存接口数据（那归 useRequest 管）
 * - token 不在这里存，交给 api/token.ts（凭证关注点独立，且 request.ts 不能依赖 React 状态）
 * persist 让刷新页面后用户信息仍在（验收③"登录态保持"）。
 */
import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { immer } from 'zustand/middleware/immer'
import type { LoginUser, TokenResult } from '@/api/types'
import { authStorage, clearSession, setTokens, USER_STORAGE_KEY } from '@/api/token'
import { useNotificationStore } from '@/stores/notificationStore'

interface UserState {
  user: LoginUser | null
  /** 登录成功：写 token（当前 tab 的 sessionStorage）+ 记录用户 */
  login: (token: TokenResult) => void
  /** 登出：清 token + 清用户（后端 logout 由调用方单独触发） */
  logout: () => void
}

export const useUserStore = create<UserState>()(
  persist(
    immer((set) => ({
      user: null,
      login: (token) => {
        setTokens(token.accessToken, token.refreshToken)
        set((state) => {
          state.user = token.user
        })
      },
      logout: () => {
        clearSession()
        // 顺带清掉通知角标：不清的话，下一个用户登录时会先看到上一个人的未读数
        // （要等首次 REST 返回才被覆盖；那次请求失败就一直显示错误数字）
        useNotificationStore.getState().reset()
        set((state) => {
          state.user = null
        })
      },
    })),
    {
      // 键从 api/token.ts 导入：它同时也是"清会话"要清的键，写两处就会漏一处（评审 P1-14）
      name: USER_STORAGE_KEY,
      // sessionStorage：每个 tab 独立登录，互不覆盖；刷新当前 tab 仍保留用户信息
      storage: createJSONStorage(() => authStorage()),
      // 只持久化 user，函数不序列化
      partialize: (state) => ({ user: state.user }),
    },
  ),
)
