/**
 * src/api/token.ts
 * 双 token 与会话快照的本地存取，独立成模块、不依赖 zustand。
 *
 * **为什么用 sessionStorage 而不是 localStorage**：
 * 同一个浏览器可以开多个 tab 分别登录不同用户（例如一个管理员、一个审批人）。
 * localStorage 是同一 origin 全 tab 共享的：后登录的 tab 会覆盖前一个 tab 的 token，
 * 前一个 tab 下一次请求就变成了"另一个用户"，看起来像被挤掉。
 * sessionStorage 按 tab 隔离，正好对应"每个 tab 一个登录会话"。
 *
 * 兼容升级：旧版本把 token/用户快照放在 localStorage；首次加载时迁移到当前 tab 的
 * sessionStorage，并清掉共享的 localStorage 副本（新 tab 不会再继承旧登录）。
 */
const ACCESS_TOKEN_KEY = 'meeting.accessToken'
const REFRESH_TOKEN_KEY = 'meeting.refreshToken'

/**
 * 用户快照的持久化键——zustand persist 的 `name`（见 stores/userStore.ts，从本模块导入，避免键写两处）。
 * 和两个 token 同属"当前 tab 的会话"，退出时必须一起清掉。
 */
export const USER_STORAGE_KEY = 'meeting.user'

const memoryValues = new Map<string, string>()
const memoryStorage: Storage = {
  get length() {
    return memoryValues.size
  },
  clear: () => memoryValues.clear(),
  getItem: (key) => memoryValues.get(key) ?? null,
  key: (index) => Array.from(memoryValues.keys())[index] ?? null,
  removeItem: (key) => {
    memoryValues.delete(key)
  },
  setItem: (key, value) => {
    memoryValues.set(key, value)
  },
}

/** 当前 tab 的会话存储；隐私模式/测试环境下退回进程内 Map，避免整个应用起不来 */
export function authStorage(): Storage {
  if (typeof window !== 'undefined') {
    try {
      return window.sessionStorage
    } catch {
      // 某些隐私策略会禁用 sessionStorage
    }
  }
  return memoryStorage
}

function migrateLegacyLocalSession(): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    const tab = authStorage()
    const legacyKeys = [ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, USER_STORAGE_KEY]
    for (const key of legacyKeys) {
      const oldValue = window.localStorage.getItem(key)
      if (oldValue != null && tab.getItem(key) == null) {
        tab.setItem(key, oldValue)
      }
      // 清掉共享副本：否则以后新开的 tab 会继承第一位用户的登录态
      window.localStorage.removeItem(key)
    }
  } catch {
    // localStorage/sessionStorage 不可用时静默降级
  }
}

migrateLegacyLocalSession()

export function getAccessToken(): string | null {
  return authStorage().getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return authStorage().getItem(REFRESH_TOKEN_KEY)
}

export function setTokens(accessToken: string, refreshToken: string): void {
  const storage = authStorage()
  storage.setItem(ACCESS_TOKEN_KEY, accessToken)
  storage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens(): void {
  const storage = authStorage()
  storage.removeItem(ACCESS_TOKEN_KEY)
  storage.removeItem(REFRESH_TOKEN_KEY)
  // 旧版本共享副本也一并清掉
  if (typeof window !== 'undefined') {
    try {
      window.localStorage.removeItem(ACCESS_TOKEN_KEY)
      window.localStorage.removeItem(REFRESH_TOKEN_KEY)
    } catch {
      // ignore
    }
  }
}

/**
 * 清空整个会话：token **和**用户快照。
 * token 与用户信息是同一个会话的两个碎片，"清会话"必须是一个动作。
 */
export function clearSession(): void {
  clearTokens()
  authStorage().removeItem(USER_STORAGE_KEY)
  if (typeof window !== 'undefined') {
    try {
      window.localStorage.removeItem(USER_STORAGE_KEY)
    } catch {
      // ignore
    }
  }
}
