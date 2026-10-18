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

import { exportMinutes, renameSpeaker } from './minutes'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('minutes api', () => {
  it('renameSpeaker 对 speakerKey 做 URL 编码', async () => {
    const input = { displayName: '张三', color: '#112233' }

    await renameSpeaker(42, 'speaker/1', input)

    expect(mocks.http.put).toHaveBeenCalledWith(
      '/meetings/42/minutes/speakers/speaker%2F1',
      input,
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
