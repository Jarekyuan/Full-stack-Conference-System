import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'

interface FakeClient {
  interceptors: {
    request: { use: MockInstance }
    response: { use: MockInstance }
  }
  get: MockInstance
  post: MockInstance
  put: MockInstance
  delete: MockInstance
}

const mocks = vi.hoisted(() => ({
  clients: [] as FakeClient[],
  accessToken: 'access-1' as string | null,
  refreshToken: 'refresh-1' as string | null,
  setTokens: vi.fn(),
  clearSession: vi.fn(),
}))

vi.mock('axios', () => {
  const create = vi.fn(() => {
    const client: FakeClient = {
      interceptors: {
        request: { use: vi.fn() },
        response: { use: vi.fn() },
      },
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
    }
    mocks.clients.push(client)
    return client
  })
  return { default: { create } }
})

vi.mock('@/api/token', () => ({
  getAccessToken: () => mocks.accessToken,
  getRefreshToken: () => mocks.refreshToken,
  setTokens: mocks.setTokens,
  clearSession: mocks.clearSession,
}))

import {
  downloadFile,
  parseContentDispositionFilename,
  sanitizeDownloadFilename,
} from '@/api/request'
import { ApiError } from '@/api/types'

// request.ts 按顺序创建三个实例：主实例 / 刷新专用 / 下载专用
const downloadClient = () => mocks.clients[2]
const refreshClient = () => mocks.clients[1]

interface FakeAnchor {
  href: string
  download: string
  click: MockInstance
  remove: MockInstance
}

let anchor: FakeAnchor
let appendChild: MockInstance
let locationReplace: MockInstance
let scheduledRevoke: (() => void) | null

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  mocks.accessToken = 'access-1'
  mocks.refreshToken = 'refresh-1'

  anchor = { href: '', download: '', click: vi.fn(), remove: vi.fn() }
  appendChild = vi.fn()
  locationReplace = vi.fn()
  scheduledRevoke = null

  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  vi.stubGlobal('document', {
    createElement: vi.fn(() => anchor),
    body: { appendChild },
  })
  vi.stubGlobal('window', {
    setTimeout: (callback: () => void) => {
      scheduledRevoke = callback
      return 1
    },
    location: {
      pathname: '/meetings/42',
      search: '?tab=minutes',
      replace: locationReplace,
    },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** 构造一个 mock 的 Axios 响应 */
function response(status: number, data: unknown, headers: Record<string, string> = {}) {
  return { status, headers, data }
}

function jsonBlob(body: unknown): Blob {
  return new Blob([JSON.stringify(body)], { type: 'application/json' })
}

describe('文件名解析与清洗', () => {
  it('解析 RFC 5987 的 filename*=UTF-8 中文文件名', () => {
    const header =
      "attachment; filename*=UTF-8''%E5%91%A8%E4%BC%9A%E7%BA%AA%E8%A6%81.docx"
    expect(parseContentDispositionFilename(header)).toBe('周会纪要.docx')
  })

  it('非法百分号编码时回退普通 filename', () => {
    const header =
      'attachment; filename*=UTF-8\'\'%E0%A4%A; filename="fallback.docx"'
    expect(parseContentDispositionFilename(header)).toBe('fallback.docx')
  })

  it('缺省 / 空 header 返回 null', () => {
    expect(parseContentDispositionFilename(undefined)).toBeNull()
    expect(parseContentDispositionFilename('')).toBeNull()
  })

  it('清洗非法字符并保证 .docx 后缀', () => {
    expect(sanitizeDownloadFilename('周会/纪要:终版?.docx')).toBe('周会_纪要_终版_.docx')
    expect(sanitizeDownloadFilename('周会纪要')).toBe('周会纪要.docx')
    expect(sanitizeDownloadFilename('a\u0000b.docx')).toBe('a_b.docx')
  })

  it('空文件名回退默认名', () => {
    expect(sanitizeDownloadFilename('')).toBe('会议纪要.docx')
    expect(sanitizeDownloadFilename(null)).toBe('会议纪要.docx')
  })

  it('超长文件名截断且保留 .docx', () => {
    const name = sanitizeDownloadFilename('a'.repeat(200))
    expect(name).toHaveLength(120)
    expect(name.endsWith('.docx')).toBe(true)
  })
})

describe('downloadFile', () => {
  it('成功时触发下载：优先服务端文件名，object URL 延迟释放', async () => {
    const blob = new Blob(['docx-bytes'])
    downloadClient().get.mockResolvedValueOnce(
      response(
        200,
        blob,
        {
          'content-type':
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          'content-disposition':
            "attachment; filename*=UTF-8''%E5%91%A8%E4%BC%9A%E7%BA%AA%E8%A6%81.docx",
        },
      ),
    )

    await downloadFile('/meetings/42/minutes/export?format=docx', 'fallback.docx')

    expect(downloadClient().get).toHaveBeenCalledWith(
      '/meetings/42/minutes/export?format=docx',
      expect.objectContaining({
        responseType: 'blob',
        validateStatus: expect.any(Function),
      }),
    )
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob)
    expect(appendChild).toHaveBeenCalledWith(anchor)
    expect(anchor.download).toBe('周会纪要.docx')
    expect(anchor.click).toHaveBeenCalledTimes(1)
    expect(anchor.remove).toHaveBeenCalledTimes(1)

    // 延迟释放：点击后不能同步 revoke（Firefox/Safari 会取消下载）
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    expect(scheduledRevoke).not.toBeNull()
    scheduledRevoke?.()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock')
  })

  it('无 Content-Disposition 时使用清洗后的兜底文件名', async () => {
    downloadClient().get.mockResolvedValueOnce(
      response(200, new Blob(['x']), { 'content-type': 'application/octet-stream' }),
    )

    await downloadFile('/x', '非法/名称?.docx')

    expect(anchor.download).toBe('非法_名称_.docx')
  })

  it('HTTP 200 + JSON 业务错误：不下载，抛出 ApiError 的 code/msg/data', async () => {
    downloadClient().get.mockResolvedValueOnce(
      response(200, jsonBlob({ code: 70001, msg: '会议纪要不存在', data: { id: 1 } }), {
        'content-type': 'application/json;charset=UTF-8',
      }),
    )

    const error = await downloadFile('/meetings/42/minutes/export').catch(
      (e: unknown) => e,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      code: 70001,
      message: '会议纪要不存在',
      data: { id: 1 },
    })
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    expect(anchor.click).not.toHaveBeenCalled()
  })

  it('401 时复用 single-flight 刷新并重试成功', async () => {
    const blob = new Blob(['docx'])
    downloadClient().get
      .mockResolvedValueOnce(response(401, new Blob([])))
      .mockResolvedValueOnce(
        response(200, blob, { 'content-type': 'application/octet-stream' }),
      )
    refreshClient().post.mockResolvedValueOnce({
      data: {
        code: 0,
        msg: 'ok',
        data: { accessToken: 'access-2', refreshToken: 'refresh-2' },
      },
    })

    await downloadFile('/x')

    expect(refreshClient().post).toHaveBeenCalledWith('/auth/refresh', {
      refreshToken: 'refresh-1',
    })
    expect(mocks.setTokens).toHaveBeenCalledWith('access-2', 'refresh-2')
    expect(downloadClient().get).toHaveBeenCalledTimes(2)
    expect(anchor.click).toHaveBeenCalledTimes(1)
  })

  it('401 且刷新失败：清会话、跳登录并抛 20003', async () => {
    downloadClient().get.mockResolvedValueOnce(response(401, new Blob([])))
    refreshClient().post.mockResolvedValueOnce({
      data: { code: 20003, msg: 'refresh token 已失效', data: null },
    })

    const error = await downloadFile('/x').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ code: 20003, message: '登录已过期' })
    expect(mocks.clearSession).toHaveBeenCalledTimes(1)
    expect(locationReplace).toHaveBeenCalledWith(
      '/login?redirect=%2Fmeetings%2F42%3Ftab%3Dminutes',
    )
    expect(downloadClient().get).toHaveBeenCalledTimes(1)
  })

  it('非 2xx + JSON 错误体：抛出后端业务码', async () => {
    downloadClient().get.mockResolvedValueOnce(
      response(503, jsonBlob({ code: 50001, msg: '服务暂不可用', data: null }), {
        'content-type': 'application/json',
      }),
    )

    const error = await downloadFile('/x').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ code: 50001, message: '服务暂不可用' })
  })

  it('非 2xx 且无 JSON 错误体：抛出 HTTP 状态码', async () => {
    downloadClient().get.mockResolvedValueOnce(
      response(502, new Blob(['bad gateway']), { 'content-type': 'text/plain' }),
    )

    const error = await downloadFile('/x').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ code: 502, message: '导出失败' })
  })
})
