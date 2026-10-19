import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('./request', () => ({ http }))

import {
  abortMultipart,
  completeMultipart,
  getPartUrls,
  getUploadSession,
  initMultipart,
} from './file'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('file api', () => {
  it('initMultipart 使用后端路径并原样传参', async () => {
    const input = {
      bizType: 'MINUTES_AUDIO' as const,
      bizId: 42,
      fileName: 'meeting.m4a',
      fileSize: 1024,
      contentType: 'audio/mp4',
    }

    await initMultipart(input)

    expect(http.post).toHaveBeenCalledWith('/files/multipart/init', input)
  })

  it('getPartUrls 以逗号拼接 partNumbers 并编码 uploadId', async () => {
    await getPartUrls('upload/1', [1, 2, 3])

    expect(http.get).toHaveBeenCalledWith('/files/multipart/upload%2F1/parts', {
      params: { partNumbers: '1,2,3' },
    })
  })

  it('getUploadSession 编码 uploadId', async () => {
    await getUploadSession('upload/1')

    expect(http.get).toHaveBeenCalledWith('/files/multipart/upload%2F1', {
      timeout: 30_000,
    })
  })

  it('completeMultipart 编码 uploadId 并把 parts 包成请求体', async () => {
    const parts = [{ partNumber: 1, etag: 'etag-1' }]

    await completeMultipart('upload/1', parts)

    expect(http.post).toHaveBeenCalledWith(
      '/files/multipart/upload%2F1/complete',
      { parts },
      { timeout: 120_000 },
    )
  })

  it('abortMultipart 编码 uploadId', async () => {
    await abortMultipart('upload/1')

    expect(http.delete).toHaveBeenCalledWith('/files/multipart/upload%2F1')
  })
})
