/**
 * src/stores/userStore.ts
 * 客户端全局状态：当前登录用户。职责边界（02 文档 3.3 #4）：
 * - 只存"客户端状态"（用户信息），不存接口数据（那归 useRequest 管）
 * - token 不在这里存，交给 api/token.ts（凭证关注点独立，且 request.ts 不能依赖 React 状态）
 * persist 让刷新页面后用户信息仍在（验收③"登录态保持"）。
 */
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { immer } from 'zustand/middleware/immer'
import type { LoginUser, TokenResult } from '@/api/types'
import { clearTokens, setTokens } from '@/api/token'

interface UserState {
  user: LoginUser | null
  /** 登录成功：写 token（localStorage）+ 记录用户 */
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
        clearTokens()
        set((state) => {
          state.user = null
        })
      },
    })),
    {
      name: 'meeting.user',
      // 只持久化 user，函数不序列化
      partialize: (state) => ({ user: state.user }),
    },
  ),
)
