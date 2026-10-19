import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  downloadFile: vi.fn(),
}))

vi.mock('./request', () => ({
  http: mocks.http,
  downloadFile: mocks.downloadFile,
}))

import { exportMinutes, startTranscription } from './minutes'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('minutes api', () => {
  it('startTranscription 提交 fileId', async () => {
    await startTranscription(42, 7)

    expect(mocks.http.post).toHaveBeenCalledWith(
      '/meetings/42/minutes/transcribe',
      { fileId: 7 },
      { timeout: 30_000 },
    )
  })

  it('exportMinutes 调用下载封装并使用 docx 导出地址', async () => {
    await exportMinutes(42)

    expect(mocks.downloadFile).toHaveBeenCalledWith(
      '/meetings/42/minutes/export?format=docx',
      undefined,
    )
  })

  it('exportMinutes 可传入兜底文件名', async () => {
    await exportMinutes(42, '周会纪要.docx')

    expect(mocks.downloadFile).toHaveBeenCalledWith(
      '/meetings/42/minutes/export?format=docx',
      '周会纪要.docx',
    )
  })
})
