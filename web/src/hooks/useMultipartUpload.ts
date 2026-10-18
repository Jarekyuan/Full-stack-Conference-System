/**
 * COS 分片上传 hook（docs/04 10.1）。
 *
 * 关键点：
 * ① 前端只拿预签名 URL 直传，文件字节不经过后端；
 * ② 每片失败按 1s/2s 退避重试；暂停用 AbortController 取消在途请求；
 * ③ uploadId + 已完成分片写 localStorage，刷新页面后按后端 listParts 续传；
 * ④ 上传完成后必须拿到 ETag（COS CORS 要 ExposeHeader: ETag）。
 */
import { useCallback, useRef, useState } from 'react'
import {
  completeMultipart,
  getPartUrls,
  getUploadSession,
  initMultipart,
  type MultipartInit,
} from '@/api/file'
import { ApiError } from '@/api/types'

const CONCURRENCY = 3
const PART_BATCH = 50
const MAX_RETRIES = 3

export type UploadStatus = 'idle' | 'uploading' | 'paused' | 'done' | 'error'

interface ActiveSession {
  uploadId: string
  objectKey: string
  chunkSize: number
  totalParts: number
}

interface StoredSession {
  uploadId: string
  objectKey: string
  chunkSize: number
  totalParts: number
  completed: [number, string][]
}

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
  if (file.type) return file.type
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

function readStored(key: string): StoredSession | null {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as StoredSession) : null
  } catch {
    return null
  }
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

export function useMultipartUpload(meetingId: number) {
  const [progress, setProgress] = useState(0)
  const [status, setStatus] = useState<UploadStatus>('idle')
  const [error, setError] = useState<string | null>(null)

  const pausedRef = useRef(false)
  const abortRef = useRef<AbortController | null>(null)
  const fileRef = useRef<File | null>(null)
  const sessionRef = useRef<ActiveSession | null>(null)
  const completedRef = useRef<Map<number, string>>(new Map())
  const keyRef = useRef('')

  const persist = useCallback(() => {
    const active = sessionRef.current
    if (!active || !keyRef.current) return
    writeStored(keyRef.current, {
      ...active,
      completed: [...completedRef.current.entries()],
    })
  }, [])

  const uploadParts = useCallback(
    async (file: File, session: ActiveSession, missing: number[]) => {
      for (let offset = 0; offset < missing.length; offset += PART_BATCH) {
        if (pausedRef.current) return
        const batch = missing.slice(offset, offset + PART_BATCH)
        const urls = await getPartUrls(session.uploadId, batch)
        const urlMap = new Map(urls.map((item) => [item.partNumber, item.url]))
        const queue = [...batch]

        const worker = async () => {
          while (queue.length > 0) {
            if (pausedRef.current) return
            const partNumber = queue.shift()!
            const url = urlMap.get(partNumber)
            if (!url) throw new Error(`分片 ${partNumber} 未拿到上传地址`)
            const [start, end] = partRange(partNumber, session.chunkSize, file.size)
            const blob = file.slice(start, end)

            let lastError: unknown = null
            for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
              try {
                const response = await fetch(url, {
                  method: 'PUT',
                  body: blob,
                  signal: abortRef.current?.signal,
                })
                if (!response.ok) {
                  throw new Error(`分片 ${partNumber} 上传失败（HTTP ${response.status}）`)
                }
                const etag = response.headers.get('ETag') ?? response.headers.get('etag')
                if (!etag) {
                  throw new Error('未拿到 ETag，请检查 COS CORS 的 ExposeHeader')
                }
                completedRef.current.set(partNumber, etag)
                persist()
                setProgress(
                  Math.round((completedRef.current.size / session.totalParts) * 100),
                )
                lastError = null
                break
              } catch (e) {
                if ((e as Error).name === 'AbortError') return
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                  await new Promise((resolve) => setTimeout(resolve, 1000 * 2 ** attempt))
                }
              }
            }
            if (lastError) throw lastError
          }
        }

        await Promise.all(
          Array.from({ length: Math.min(CONCURRENCY, queue.length) }, () => worker()),
        )
        if (pausedRef.current) return
      }
    },
    [persist],
  )

  const upload = useCallback(
    async (file: File): Promise<number | null> => {
      const nextKey = buildResumeKey(meetingId, file)
      if (keyRef.current !== nextKey) {
        // 同一页面里换文件：不能复用上一份文件的 uploadId/已完成分片
        sessionRef.current = null
        completedRef.current = new Map()
        setProgress(0)
      }
      fileRef.current = file
      keyRef.current = nextKey
      pausedRef.current = false
      abortRef.current = new AbortController()
      setStatus('uploading')
      setError(null)

      try {
        let session: ActiveSession | null = sessionRef.current
        const stored = readStored(keyRef.current)
        if (stored) {
          try {
            const remote = await getUploadSession(stored.uploadId)
            if (remote.status === 0 && remote.totalParts === stored.totalParts) {
              session = {
                uploadId: stored.uploadId,
                objectKey: stored.objectKey,
                chunkSize: stored.chunkSize,
                totalParts: stored.totalParts,
              }
              completedRef.current = new Map(
                remote.uploadedParts.map((part) => [part.partNumber, part.etag]),
              )
              sessionRef.current = session
            }
          } catch {
            clearStored(keyRef.current)
          }
        }

        if (!session) {
          const created: MultipartInit = await initMultipart({
            bizType: 'MINUTES_AUDIO',
            bizId: meetingId,
            fileName: file.name,
            fileSize: file.size,
            contentType: audioContentType(file),
          })
          session = {
            uploadId: created.uploadId,
            objectKey: created.objectKey,
            chunkSize: created.chunkSize,
            totalParts: created.totalParts,
          }
          sessionRef.current = session
          completedRef.current = new Map()
          writeStored(keyRef.current, { ...session, completed: [] })
        }

        const active = session
        setProgress(Math.round((completedRef.current.size / active.totalParts) * 100))
        const missing = missingParts(active.totalParts, completedRef.current.keys())
        await uploadParts(file, active, missing)
        if (pausedRef.current) {
          setStatus('paused')
          return null
        }

        const parts = [...completedRef.current.entries()]
          .sort((a, b) => a[0] - b[0])
          .map(([partNumber, etag]) => ({ partNumber, etag }))
        const result = await completeMultipart(active.uploadId, parts)
        clearStored(keyRef.current)
        setProgress(100)
        setStatus('done')
        return result.fileId
      } catch (e) {
        if (pausedRef.current) {
          setStatus('paused')
          return null
        }
        setStatus('error')
        setError(
          e instanceof ApiError
            ? e.message
            : e instanceof Error
              ? e.message
              : '上传失败',
        )
        throw e
      }
    },
    [meetingId, uploadParts],
  )

  const pause = useCallback(() => {
    pausedRef.current = true
    abortRef.current?.abort()
    setStatus('paused')
  }, [])

  const resume = useCallback(() => {
    return fileRef.current ? upload(fileRef.current) : Promise.resolve(null)
  }, [upload])

  const reset = useCallback(() => {
    clearStored(keyRef.current)
    sessionRef.current = null
    completedRef.current = new Map()
    fileRef.current = null
    setProgress(0)
    setStatus('idle')
    setError(null)
  }, [])

  return { upload, pause, resume, reset, progress, status, error }
}
