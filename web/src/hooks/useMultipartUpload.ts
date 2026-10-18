/**
 * COS 分片上传 hook（docs/04 10.1）。
 *
 * 关键点：
 * ① 前端只拿预签名 URL 直传，文件字节不经过后端；
 * ② 每片失败按 1s/2s/4s 退避（首传 + 3 次重试，共 4 次尝试）；暂停用 AbortController 取消在途请求；
 * ③ uploadId + 已完成分片写 localStorage，刷新页面后按后端 listParts 续传；
 * ④ 上传完成后必须拿到 ETag（COS CORS 要 ExposeHeader: ETag）；
 * ⑤ 每次 upload 有独立 runId/AbortController：pause/reset/unmount 递增 runId 并 abort，
 *    旧 run 静默退出，不能再写状态、localStorage 或调用 complete。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  completeMultipart,
  getPartUrls,
  getUploadSession,
  initMultipart,
  type MultipartInit,
  type MultipartSession,
} from '@/api/file'
import { ApiError } from '@/api/types'

const CONCURRENCY = 3
const PART_BATCH = 50
/** 1 次首传 + 3 次重试；重试前分别等待 1s/2s/4s（设计文档 §4.4）。 */
const RETRY_DELAYS_MS = [1000, 2000, 4000] as const
/** 单个分片 PUT 的超时；超时按可重试错误处理。 */
const PART_TIMEOUT_MS = 60_000
/** localStorage 写入节流：每 10 片或每 1 秒最多一次（init/末片立即写）。 */
const PERSIST_EVERY_PARTS = 10
const PERSIST_INTERVAL_MS = 1000
/** 分片会话不存在/已过期（docs/04 错误码表）。 */
const CODE_UPLOAD_SESSION_EXPIRED = 70006
const EMPTY_FILE_MESSAGE = '文件为空，请选择有效的音频文件'

export type UploadStatus = 'idle' | 'uploading' | 'paused' | 'done' | 'error'

export interface ActiveSession {
  uploadId: string
  objectKey: string
  chunkSize: number
  totalParts: number
}

export interface StoredSession {
  uploadId: string
  objectKey: string
  chunkSize: number
  totalParts: number
  completed: [number, string][]
}

export type ResumeDecision =
  | { action: 'resume'; session: ActiveSession; completed: Map<number, string> }
  | { action: 'restart' }

export function buildResumeKey(meetingId: number, file: File): string {
  return `minutes-upload:${meetingId}:${file.name}:${file.size}:${file.lastModified}`
}

export function partRange(
  partNumber: number,
  chunkSize: number,
  fileSize: number,
): [number, number] {
  const start = (partNumber - 1) * chunkSize
  return [start, Math.min(start + chunkSize, fileSize)]
}

export function missingParts(totalParts: number, completed: Iterable<number>): number[] {
  const done = new Set(completed)
  const result: number[] = []
  for (let i = 1; i <= totalParts; i++) {
    if (!done.has(i)) result.push(i)
  }
  return result
}

/** 浏览器有时不给 File.type；按扩展名兜底，后端会同时校验扩展名与 MIME。 */
export function audioContentType(file: File): string {
  const declared = normalizeDeclaredType(file.type)
  if (declared) return declared
  const extension = file.name.split('.').pop()?.toLowerCase()
  const byExtension: Record<string, string> = {
    m4a: 'audio/mp4',
    mp3: 'audio/mpeg',
    wav: 'audio/wav',
    aac: 'audio/aac',
    ogg: 'audio/ogg',
  }
  return (extension && byExtension[extension]) || 'application/octet-stream'
}

/** 仅重试网络抖动/超时/限流/服务端 5xx；400/401/403/404 等确定性错误不重试。 */
export function isRetryableStatus(status: number): boolean {
  return status === 408 || status === 429 || (status >= 500 && status <= 599)
}

/** localStorage 里的会话必须字段完整且数值合理，否则按“无会话”处理。 */
export function isValidStoredSession(value: unknown): value is StoredSession {
  if (!value || typeof value !== 'object') return false
  const session = value as Record<string, unknown>
  if (typeof session.uploadId !== 'string' || session.uploadId.length === 0) return false
  if (typeof session.objectKey !== 'string') return false
  if (!Number.isInteger(session.chunkSize) || (session.chunkSize as number) <= 0) return false
  if (!Number.isInteger(session.totalParts) || (session.totalParts as number) <= 0) return false
  if (!Array.isArray(session.completed)) return false
  return (session.completed as unknown[]).every((entry) => {
    if (!Array.isArray(entry) || entry.length !== 2) return false
    const [partNumber, etag] = entry as unknown[]
    return Number.isInteger(partNumber) && (partNumber as number) > 0 && typeof etag === 'string'
  })
}

/**
 * 续传决策：只有后端会话仍在进行中（status=0）且分片布局一致时才复用；
 * 已完成/已中止/分片数或分片大小不一致都重新 init（由调用方清掉 localStorage）。
 * 已完成分片以后端 listParts（uploadedParts）为准，不信任本地记录。
 */
export function resolveResumeSession(
  stored: StoredSession,
  remote: MultipartSession,
): ResumeDecision {
  if (
    remote.status !== 0 ||
    remote.totalParts !== stored.totalParts ||
    remote.chunkSize !== stored.chunkSize
  ) {
    return { action: 'restart' }
  }
  return {
    action: 'resume',
    session: {
      uploadId: stored.uploadId,
      objectKey: stored.objectKey,
      chunkSize: stored.chunkSize,
      totalParts: stored.totalParts,
    },
    completed: new Map(remote.uploadedParts.map((part) => [part.partNumber, part.etag])),
  }
}

function normalizeDeclaredType(type: string): string {
  const normalized = type.trim().toLowerCase()
  return normalized === 'audio/wave' || normalized === 'audio/x-wav' ? 'audio/wav' : normalized
}

function readStored(key: string): StoredSession | null {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(key)
  } catch {
    return null
  }
  if (!raw) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    if (isValidStoredSession(parsed)) return parsed
  } catch {
    // JSON 损坏：按无效会话处理，下面清掉
  }
  clearStored(key)
  return null
}

function writeStored(key: string, session: StoredSession): void {
  try {
    localStorage.setItem(key, JSON.stringify(session))
  } catch {
    // localStorage 满了/禁用时，续传能力退化，但上传本身仍可继续
  }
}

function clearStored(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    // ignore
  }
}

/** 确定性失败（4xx、缺 ETag 等）：不重试，直接让整个 run 失败。 */
class NonRetryableUploadError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NonRetryableUploadError'
  }
}

/** 单次 PUT 超时：可重试，与 pause/abort 区分。 */
class UploadTimeoutError extends Error {
  constructor() {
    super('分片上传超时')
    this.name = 'UploadTimeoutError'
  }
}

/** 可被 abort 立即唤醒的重试等待，避免暂停后还要等满退避时间。 */
function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve()
      return
    }
    let timer: ReturnType<typeof setTimeout> | null = null
    const finish = () => {
      if (timer) clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
    timer = setTimeout(finish, ms)
    signal.addEventListener('abort', finish, { once: true })
  })
}

/** PUT 一片：把 run 的取消信号与单次超时合成为一个 signal。 */
async function fetchPartWithTimeout(
  url: string,
  body: Blob,
  runSignal: AbortSignal,
  timeoutMs: number,
): Promise<Response> {
  const combined = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    combined.abort()
  }, timeoutMs)
  const onRunAbort = () => combined.abort()
  if (runSignal.aborted) combined.abort()
  else runSignal.addEventListener('abort', onRunAbort, { once: true })

  try {
    return await fetch(url, { method: 'PUT', body, signal: combined.signal })
  } catch (e) {
    // 暂停/取消优先于超时：runSignal 已 abort 时交给调用方静默退出。
    if (timedOut && !runSignal.aborted) throw new UploadTimeoutError()
    throw e
  } finally {
    clearTimeout(timer)
    runSignal.removeEventListener('abort', onRunAbort)
  }
}

interface PutPartOptions {
  partNumber: number
  uploadId: string
  initialUrl: string
  blob: Blob
  runSignal: AbortSignal
  isCurrent: () => boolean
}

/**
 * 上传单个分片并返回 ETag；返回 null 表示 run 已被暂停/取消。
 * 重试策略：网络错误/超时/408/429/5xx 退避重试；401/403 重新签发一次 URL；其余 4xx 立即失败。
 */
async function putPartWithRetry(options: PutPartOptions): Promise<string | null> {
  const { partNumber, uploadId, blob, runSignal, isCurrent } = options
  let url = options.initialUrl
  let attempt = 0
  let reSigned = false

  for (;;) {
    if (!isCurrent() || runSignal.aborted) return null

    let response: Response
    try {
      response = await fetchPartWithTimeout(url, blob, runSignal, PART_TIMEOUT_MS)
    } catch (e) {
      if (!isCurrent() || runSignal.aborted) return null
      const retryable = e instanceof UploadTimeoutError || e instanceof TypeError
      if (!retryable) throw e
      if (attempt >= RETRY_DELAYS_MS.length) throw e
      await delay(RETRY_DELAYS_MS[attempt], runSignal)
      attempt += 1
      continue
    }

    if (!isCurrent() || runSignal.aborted) return null

    if (response.ok) {
      const etag = response.headers.get('ETag')
      if (!etag) {
        throw new NonRetryableUploadError('未拿到 ETag，请检查 COS CORS 的 ExposeHeader')
      }
      return etag
    }

    if ((response.status === 401 || response.status === 403) && !reSigned) {
      reSigned = true
      const urls = await getPartUrls(uploadId, [partNumber])
      if (!isCurrent() || runSignal.aborted) return null
      const fresh = urls.find((item) => item.partNumber === partNumber)
      if (!fresh) throw new NonRetryableUploadError(`分片 ${partNumber} 未拿到上传地址`)
      url = fresh.url
      continue
    }

    if (isRetryableStatus(response.status) && attempt < RETRY_DELAYS_MS.length) {
      await delay(RETRY_DELAYS_MS[attempt], runSignal)
      attempt += 1
      continue
    }
    throw new NonRetryableUploadError(`分片 ${partNumber} 上传失败（HTTP ${response.status}）`)
  }
}

export function useMultipartUpload(meetingId: number) {
  const [progress, setProgress] = useState(0)
  const [status, setStatus] = useState<UploadStatus>('idle')
  const [error, setError] = useState<string | null>(null)

  const runIdRef = useRef(0)
  const abortRef = useRef<AbortController | null>(null)
  const fileRef = useRef<File | null>(null)
  const keyRef = useRef('')

  // 卸载只取消在途请求，不清 localStorage——回到页面后还能续传。
  useEffect(
    () => () => {
      runIdRef.current += 1
      abortRef.current?.abort()
    },
    [],
  )

  const upload = useCallback(
    async (file: File): Promise<number | null> => {
      const runId = ++runIdRef.current
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller
      const key = buildResumeKey(meetingId, file)
      // 同一页面换文件时先把进度归零；同文件续传则保留进度，等会话解析后再校正。
      if (keyRef.current !== key) setProgress(0)
      fileRef.current = file
      keyRef.current = key
      const isCurrent = () => runIdRef.current === runId

      setStatus('uploading')
      setError(null)

      try {
        if (file.size <= 0) throw new Error(EMPTY_FILE_MESSAGE)

        // 本次 run 的会话/已完成分片只存在局部变量里，旧 run 无法污染新 run。
        let session: ActiveSession | null = null
        let completed = new Map<number, string>()

        let lastPersistAt = Date.now()
        let partsSincePersist = 0
        const persistNow = () => {
          if (!isCurrent() || !session) return
          writeStored(key, { ...session, completed: [...completed.entries()] })
          lastPersistAt = Date.now()
          partsSincePersist = 0
        }
        const persistAfterPart = () => {
          if (!isCurrent() || !session) return
          partsSincePersist += 1
          if (
            partsSincePersist >= PERSIST_EVERY_PARTS ||
            Date.now() - lastPersistAt >= PERSIST_INTERVAL_MS ||
            completed.size === session.totalParts
          ) {
            persistNow()
          }
        }

        const stored = readStored(key)
        if (stored) {
          let remote: MultipartSession | null = null
          try {
            remote = await getUploadSession(stored.uploadId)
          } catch (e) {
            if (!isCurrent()) return null
            if (e instanceof ApiError && e.code === CODE_UPLOAD_SESSION_EXPIRED) {
              // 会话已过期：丢掉本地记录，下面 init 一个全新会话。
              clearStored(key)
            } else {
              // 网络/5xx：保留本地状态，用户稍后重试仍可续传。
              throw e
            }
          }
          if (!isCurrent()) return null

          if (remote) {
            const decision = resolveResumeSession(stored, remote)
            if (decision.action === 'resume') {
              session = decision.session
              completed = decision.completed
            } else {
              // 已完成/已中止/分片布局不一致：旧会话不可继续，重新 init。
              clearStored(key)
            }
          }
        }

        if (!isCurrent()) return null

        if (!session) {
          const created: MultipartInit = await initMultipart({
            bizType: 'MINUTES_AUDIO',
            bizId: meetingId,
            fileName: file.name,
            fileSize: file.size,
            contentType: audioContentType(file),
          })
          if (!isCurrent()) return null
          session = {
            uploadId: created.uploadId,
            objectKey: created.objectKey,
            chunkSize: created.chunkSize,
            totalParts: created.totalParts,
          }
          completed = new Map()
          writeStored(key, { ...session, completed: [] })
          lastPersistAt = Date.now()
          partsSincePersist = 0
        }

        const active = session
        if (isCurrent()) {
          setProgress(Math.round((completed.size / active.totalParts) * 100))
        }
        const missing = missingParts(active.totalParts, completed.keys())

        // 任一分片最终失败：标记本地 failed 并 abort 整个 run，兄弟 worker 立即退出。
        let failed = false
        let firstError: unknown = null
        const failRun = (e: unknown) => {
          if (failed) return
          failed = true
          firstError = e
          controller.abort()
        }

        const uploadBatch = async (batch: number[]) => {
          const urls = await getPartUrls(active.uploadId, batch)
          if (!isCurrent() || controller.signal.aborted) return
          const urlMap = new Map(urls.map((item) => [item.partNumber, item.url]))
          const queue = [...batch]

          const worker = async () => {
            while (queue.length > 0) {
              if (!isCurrent() || controller.signal.aborted) return
              const partNumber = queue.shift()!
              const url = urlMap.get(partNumber)
              if (!url) {
                failRun(new NonRetryableUploadError(`分片 ${partNumber} 未拿到上传地址`))
                return
              }
              const [start, end] = partRange(partNumber, active.chunkSize, file.size)
              const blob = file.slice(start, end)

              try {
                const etag = await putPartWithRetry({
                  partNumber,
                  uploadId: active.uploadId,
                  initialUrl: url,
                  blob,
                  runSignal: controller.signal,
                  isCurrent,
                })
                if (etag === null || !isCurrent() || controller.signal.aborted) return
                completed.set(partNumber, etag)
                persistAfterPart()
                if (isCurrent()) {
                  setProgress(Math.round((completed.size / active.totalParts) * 100))
                }
              } catch (e) {
                failRun(e)
                return
              }
            }
          }

          await Promise.allSettled(
            Array.from({ length: Math.min(CONCURRENCY, queue.length) }, () => worker()),
          )
        }

        for (let offset = 0; offset < missing.length; offset += PART_BATCH) {
          if (!isCurrent() || controller.signal.aborted) return null
          await uploadBatch(missing.slice(offset, offset + PART_BATCH))
          if (failed) break
          if (!isCurrent() || controller.signal.aborted) return null
        }
        if (failed) throw firstError
        if (!isCurrent()) return null

        // 防御：绝不能用不完整的分片列表调用 complete。
        if (completed.size !== active.totalParts) {
          throw new Error('分片未全部完成，已中止合并')
        }

        const parts = [...completed.entries()]
          .sort((a, b) => a[0] - b[0])
          .map(([partNumber, etag]) => ({ partNumber, etag }))
        const result = await completeMultipart(active.uploadId, parts)
        if (!isCurrent()) return null
        clearStored(key)

        if (!result || typeof result.fileId !== 'number') {
          // request 层对 10006 幂等重复会解包成 undefined；不能直接读 fileId。
          throw new Error('分片合并成功但未返回文件信息，请刷新后重试')
        }

        setProgress(100)
        setStatus('done')
        fileRef.current = null
        abortRef.current = null
        return result.fileId
      } catch (e) {
        if (!isCurrent()) return null
        setStatus('error')
        setError(
          e instanceof ApiError ? e.message : e instanceof Error ? e.message : '上传失败',
        )
        throw e
      }
    },
    [meetingId],
  )

  const pause = useCallback(() => {
    if (status !== 'uploading') return
    // 递增 runId 后旧 run 的所有 isCurrent() 检查都会失败，不会再写状态/存储。
    runIdRef.current += 1
    abortRef.current?.abort()
    setStatus('paused')
  }, [status])

  const resume = useCallback(() => {
    if (status !== 'paused' || !fileRef.current) return Promise.resolve(null)
    // 新 run 会重新读 localStorage + getUploadSession，按后端 listParts 续传。
    return upload(fileRef.current)
  }, [status, upload])

  const reset = useCallback(() => {
    runIdRef.current += 1
    abortRef.current?.abort()
    abortRef.current = null
    const key = keyRef.current
    keyRef.current = ''
    if (key) clearStored(key)
    fileRef.current = null
    setProgress(0)
    setStatus('idle')
    setError(null)
  }, [])

  return { upload, pause, resume, reset, progress, status, error }
}
