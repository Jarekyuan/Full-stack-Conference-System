/**
 * src/api/request.ts
 * axios 实例 + 双拦截器，前端请求层的唯一入口（组件禁止直接 axios/fetch）。
 * 职责边界（对应 docs/04 接口设计 1.3 的"HTTP 状态码与业务 code 分工"）：
 * - 传输/认证层语义在这里闭环：注入 token、401 single-flight 刷新、跳登录、幂等静默、特殊码监听；
 * - UI 层（toast）不在这里做，交给 useAppRequest 统一处理，避免模块级拿不到 antd App 上下文。
 * 成功时直接解包返回 data（业务层只管数据），失败时 reject 一个 ApiError。
 */
import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ApiError, type Result, type TokenResult } from './types'
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from './token'

/** 幂等重复提交：静默忽略（复用上次结果），见 04 文档 2.2 特殊码清单 */
const CODE_IDEMPOTENT = 10006
/** refreshToken 失效：跳登录，见 04 文档 2.2 */
const CODE_REFRESH_EXPIRED = 20003

const instance = axios.create({
  baseURL: '/api',
  timeout: 10_000,
})

/** 刷新专用裸实例：不挂拦截器，避免刷新请求自身 401 时触发死循环 */
const refreshClient = axios.create({ baseURL: '/api', timeout: 10_000 })

// ———————————————————— 请求拦截：注入 accessToken ————————————————————
instance.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// ———————————————————— 401 single-flight 刷新 ————————————————————
// 多个请求同时 401 时，共享同一个 Promise，只发一次刷新（02 文档 5.1 ★ 考点）
let refreshPromise: Promise<string> | null = null

function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

async function doRefresh(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new ApiError(CODE_REFRESH_EXPIRED, '登录状态已失效，请重新登录')
  }
  // 刷新接口失败时后端返回 HTTP 200 + code 20003（不是 401），这里显式判断
  const { data: body } = await refreshClient.post<Result<TokenResult>>('/auth/refresh', { refreshToken })
  if (body.code !== 0 || !body.data) {
    throw new ApiError(body.code, body.msg)
  }
  setTokens(body.data.accessToken, body.data.refreshToken)
  return body.data.accessToken
}

// ———————————————————— 跳登录（登录态彻底失效）————————————————————
function redirectToLogin(): void {
  clearTokens()
  // T1.F3 接入 react-router 后可改为 navigate；当前用硬跳转兜底，并带上原路径供登录后跳回
  if (window.location.pathname !== '/login') {
    const redirect = encodeURIComponent(window.location.pathname + window.location.search)
    window.location.replace(`/login?redirect=${redirect}`)
  }
}

// ———————————————————— 特殊错误码监听（结构化交互钩子）————————————————————
// 用于 40001/40005/50001 这类"data 带结构化详情"的错误：业务组件可注册监听弹详情。
// 监听器不改变错误传播（错误仍会 reject 给 useAppRequest 做 toast）。
type ErrorCodeListener = (err: ApiError) => void
const errorCodeListeners = new Map<number, Set<ErrorCodeListener>>()

/** 注册某个错误码的监听器，返回取消注册函数 */
export function onErrorCode(code: number, listener: ErrorCodeListener): () => void {
  let set = errorCodeListeners.get(code)
  if (!set) {
    set = new Set()
    errorCodeListeners.set(code, set)
  }
  set.add(listener)
  return () => {
    set.delete(listener)
  }
}

function notifyListeners(err: ApiError): void {
  errorCodeListeners.get(err.code)?.forEach((fn) => {
    try {
      fn(err)
    } catch {
      // 监听器自身的异常不影响主流程
    }
  })
}

// ———————————————————— 响应拦截 ————————————————————
instance.interceptors.response.use(
  // onFulfilled：HTTP 200（含业务错误，因为业务错误也返回 200 + 非 0 code）
  (response) => {
    const body = response.data as Result
    const traceId = response.headers['x-trace-id'] as string | undefined

    if (body.code === 0) {
      // 成功：解包 data（业务层直接拿数据）。
      // 类型桥接：拦截器改变了返回语义（AxiosResponse → data），用断言让 TS 通过，
      // 实际返回类型由 http 封装的 <T, T> 泛型对外承诺。
      return body.data as unknown as AxiosResponse
    }
    if (body.code === CODE_IDEMPOTENT) {
      // 幂等重复：静默，返回 undefined（复用上次结果）
      return undefined as unknown as AxiosResponse
    }

    const err = new ApiError(body.code, body.msg, body.data, traceId)
    if (body.code === CODE_REFRESH_EXPIRED) {
      redirectToLogin()
      return Promise.reject(err)
    }
    notifyListeners(err)
    return Promise.reject(err)
  },
  // onRejected：HTTP 401/403/404/500 等（401 触发刷新）
  async (error: unknown) => {
    const axiosErr = error as AxiosError
    const config = axiosErr.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined
    const status = axiosErr.response?.status

    if (status === 401 && config && !config._retry) {
      config._retry = true
      try {
        const newToken = await refreshAccessToken()
        config.headers.set('Authorization', `Bearer ${newToken}`)
        return instance(config) // 用新 token 重放原请求
      } catch (refreshErr) {
        redirectToLogin()
        return Promise.reject(
          refreshErr instanceof ApiError ? refreshErr : new ApiError(CODE_REFRESH_EXPIRED, '登录已过期'),
        )
      }
    }

    const body = axiosErr.response?.data as Result | undefined
    const traceId = axiosErr.response?.headers?.['x-trace-id'] as string | undefined
    const apiErr = body
      ? new ApiError(body.code ?? status ?? -1, body.msg || axiosErr.message, body.data, traceId)
      : new ApiError(status ?? -1, axiosErr.message, null, traceId)
    notifyListeners(apiErr)
    return Promise.reject(apiErr)
  },
)

// ———————————————————— 对外暴露的类型安全封装 ————————————————————
// 泛型说明：get<T, T> 第一个 T 是响应 data 类型，第二个 T 声明拦截器解包后的返回类型，
// 让业务层 `http.get<Room>('/rooms')` 直接拿到 Room（无需再 .data.data）。
export const http = {
  get: <T>(url: string, config?: AxiosRequestConfig) => instance.get<T, T>(url, config),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    instance.post<T, T>(url, data, config),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    instance.put<T, T>(url, data, config),
  delete: <T>(url: string, config?: AxiosRequestConfig) => instance.delete<T, T>(url, config),
}

export default instance
