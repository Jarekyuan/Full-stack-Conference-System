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
import { clearSession, getAccessToken, getRefreshToken, setTokens } from './token'

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
  // clearSession（而非 clearTokens）：token 与持久化的用户快照一起清，避免上一用户的资料留在 localStorage（评审 P1-14）
  clearSession()
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

/**
 * 文件下载：独立实例，不经过 Result 解包拦截器。
 * 导出接口返回二进制，若走主实例会把 Blob 当成 Result 解析并误报业务错误。
 * 业务错误仍可能以 HTTP 200 + JSON 返回，因此放宽 validateStatus 后在这里自行判别。
 */
const downloadClient = axios.create({ baseURL: '/api', timeout: 120_000 })

downloadClient.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

/** 兜底文件名最大长度（含 .docx 后缀） */
const MAX_DOWNLOAD_FILENAME_LENGTH = 120
/** 文件系统非法字符（Windows/Unix 常见集合）与控制字符（Unicode Cc） */
const INVALID_FILENAME_CHARS = /[\\/:*?"<>|\p{Cc}]/gu

/**
 * 从 Content-Disposition 解析 RFC 5987 文件名（filename*=UTF-8''...）。
 * 解析失败/缺省返回 null，由调用方回退到显式传入的文件名。
 */
export function parseContentDispositionFilename(
  header: string | null | undefined,
): string | null {
  if (!header) return null
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)?.[1]
  if (encoded) {
    try {
      const decoded = decodeURIComponent(encoded).trim()
      if (decoded) return decoded
    } catch {
      // 非法百分号编码：继续尝试普通 filename
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)?.[1]
  return plain?.trim() || null
}

/**
 * 兜底文件名清洗：替换路径分隔符/控制字符、限制长度，并保证 .docx 后缀。
 * 服务端返回的文件名同样走这里，避免上游数据带出非法字符。
 */
export function sanitizeDownloadFilename(name?: string | null): string {
  const cleaned = (name ?? '').replace(INVALID_FILENAME_CHARS, '_').trim()
  const withExtension = /\.docx$/i.test(cleaned)
    ? cleaned
    : `${cleaned || '会议纪要'}.docx`
  if (withExtension.length <= MAX_DOWNLOAD_FILENAME_LENGTH) return withExtension
  return `${withExtension
    .slice(0, MAX_DOWNLOAD_FILENAME_LENGTH - '.docx'.length)
    .trimEnd()}.docx`
}

/** 读取响应头；兼容 AxiosHeaders（有 get 方法）与普通对象 */
function headerValue(headers: unknown, name: string): string | undefined {
  if (!headers || typeof headers !== 'object') return undefined
  const getter = (headers as { get?: (key: string) => unknown }).get
  const raw =
    typeof getter === 'function'
      ? getter.call(headers, name)
      : (headers as Record<string, unknown>)[name]
  return raw === undefined || raw === null ? undefined : String(raw)
}

/** 把错误响应体 Blob 解析成统一 Result；不是 JSON 时返回 null */
async function parseJsonResult(data: unknown): Promise<Result | null> {
  const blob = data as Blob | undefined
  if (!blob || typeof blob.text !== 'function') return null
  try {
    return JSON.parse(await blob.text()) as Result
  } catch {
    return null
  }
}

/** 触发浏览器下载；url 不含 /api 前缀（由 baseURL 提供） */
export async function downloadFile(url: string, filename?: string): Promise<void> {
  const request = () =>
    downloadClient.get<Blob>(url, {
      responseType: 'blob',
      // 业务错误是 HTTP 200 + JSON，401 也要自己处理，不让 axios 按状态码提前 reject
      validateStatus: () => true,
    })

  let response = await request()

  if (response.status === 401) {
    try {
      // 复用主实例的 single-flight 刷新，多处同时 401 时只发一次刷新
      await refreshAccessToken()
    } catch {
      redirectToLogin()
      throw new ApiError(CODE_REFRESH_EXPIRED, '登录已过期')
    }
    response = await request()
    if (response.status === 401) {
      redirectToLogin()
      throw new ApiError(CODE_REFRESH_EXPIRED, '登录已过期')
    }
  }

  const contentType = (
    headerValue(response.headers, 'content-type') ??
    (response.data as Blob | undefined)?.type ??
    ''
  ).toLowerCase()
  const success = response.status >= 200 && response.status < 300

  // 非 2xx 或 2xx + JSON：都是后端 Result 错误，绝不能落成 .docx
  if (!success || contentType.includes('json')) {
    const body = await parseJsonResult(response.data)
    if (body && typeof body === 'object') {
      throw new ApiError(
        typeof body.code === 'number' ? body.code : -1,
        body.msg || (success ? '下载失败' : '导出失败'),
        body.data,
      )
    }
    throw new ApiError(response.status, success ? '下载失败' : '导出失败')
  }

  const blob = response.data as Blob
  const serverFilename = parseContentDispositionFilename(
    headerValue(response.headers, 'content-disposition'),
  )
  const downloadName = sanitizeDownloadFilename(serverFilename ?? filename)

  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  try {
    anchor.href = objectUrl
    anchor.download = downloadName
    document.body.appendChild(anchor)
    anchor.click()
  } finally {
    anchor.remove()
    // 延迟释放：Firefox/Safari 在 click 后立即 revoke 可能取消尚未开始的下载
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  }
}
