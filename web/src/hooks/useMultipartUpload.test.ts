import { describe, expect, it, vi } from 'vitest'

// 纯函数测试不需要真实请求层；mock 掉避免加载 axios/拦截器等模块副作用。
vi.mock('@/api/file', () => ({
  initMultipart: vi.fn(),
  getPartUrls: vi.fn(),
  getUploadSession: vi.fn(),
  completeMultipart: vi.fn(),
}))

import type { MultipartSession } from '@/api/file'
import {
  audioContentType,
  buildResumeKey,
  isRetryableStatus,
  isValidStoredSession,
  missingParts,
  partRange,
  resolveResumeSession,
  type StoredSession,
} from './useMultipartUpload'

/** 测试里用不到真实的 Blob 行为，只需 File 元数据字段。 */
function fileLike(
  overrides: { name?: string; size?: number; lastModified?: number; type?: string } = {},
): File {
  return {
    name: 'meeting.m4a',
    size: 11,
    lastModified: 123,
    type: '',
    ...overrides,
  } as File
}

function storedSession(overrides: Partial<StoredSession> = {}): StoredSession {
  return {
    uploadId: 'upload-1',
    objectKey: 'minutes/42/2026-10/a.m4a',
    chunkSize: 5,
    totalParts: 3,
    completed: [],
    ...overrides,
  }
}

function remoteSession(overrides: Partial<MultipartSession> = {}): MultipartSession {
  return {
    uploadId: 'upload-1',
    objectKey: 'minutes/42/2026-10/a.m4a',
    chunkSize: 5,
    totalParts: 3,
    status: 0,
    uploadedParts: [],
    missingPartNumbers: [1, 2, 3],
    expiresAt: '2026-10-20T00:00:00Z',
    ...overrides,
  }
}

describe('buildResumeKey', () => {
  it('包含会议 ID、文件名、大小与最后修改时间', () => {
    const file = fileLike({
      name: '周会录音.m4a',
      size: 2 * 1024 * 1024,
      lastModified: 1729250000000,
    })

    expect(buildResumeKey(42, file)).toBe(
      'minutes-upload:42:周会录音.m4a:2097152:1729250000000',
    )
  })

  it('不同会议、不同文件元数据得到不同 key', () => {
    const base = fileLike()

    expect(buildResumeKey(1, base)).not.toBe(buildResumeKey(2, base))
    expect(buildResumeKey(1, base)).not.toBe(buildResumeKey(1, fileLike({ size: 12 })))
    expect(buildResumeKey(1, base)).not.toBe(buildResumeKey(1, fileLike({ lastModified: 124 })))
    expect(buildResumeKey(1, base)).not.toBe(buildResumeKey(1, fileLike({ name: 'other.m4a' })))
  })
})

describe('partRange', () => {
  it('计算首片、中间片与末片', () => {
    expect(partRange(1, 5, 11)).toEqual([0, 5])
    expect(partRange(2, 5, 11)).toEqual([5, 10])
    expect(partRange(3, 5, 11)).toEqual([10, 11])
  })

  it('文件大小恰好整除分片大小时，末片结束于文件末尾', () => {
    expect(partRange(3, 5, 15)).toEqual([10, 15])
  })

  it('空文件返回零长度区间', () => {
    expect(partRange(1, 5, 0)).toEqual([0, 0])
  })

  it('分片大小超过文件大小时按文件大小截断', () => {
    expect(partRange(1, 100, 11)).toEqual([0, 11])
  })
})

describe('missingParts', () => {
  it('返回缺失分片，且按分片号升序', () => {
    expect(missingParts(5, [4, 1])).toEqual([2, 3, 5])
  })

  it('全部完成时返回空数组', () => {
    expect(missingParts(3, [1, 2, 3])).toEqual([])
  })

  it('没有任何已完成分片时返回全部分片', () => {
    expect(missingParts(3, new Map<number, string>().keys())).toEqual([1, 2, 3])
  })
})

describe('audioContentType', () => {
  it('优先使用浏览器给出的 MIME', () => {
    expect(audioContentType(fileLike({ name: 'meeting.bin', type: 'audio/x-m4a' }))).toBe(
      'audio/x-m4a',
    )
  })

  const byExtension: [string, string][] = [
    ['meeting.m4a', 'audio/mp4'],
    ['meeting.mp3', 'audio/mpeg'],
    ['meeting.wav', 'audio/wav'],
    ['meeting.aac', 'audio/aac'],
    ['meeting.ogg', 'audio/ogg'],
  ]

  for (const [name, expected] of byExtension) {
    it(`File.type 缺失时按扩展名兜底：${name}`, () => {
      expect(audioContentType(fileLike({ name, type: '' }))).toBe(expected)
    })
  }

  it('扩展名大小写不敏感', () => {
    expect(audioContentType(fileLike({ name: 'MEETING.M4A', type: '' }))).toBe('audio/mp4')
  })

  it('audio/wave 与 audio/x-wav 归一化为 audio/wav', () => {
    expect(audioContentType(fileLike({ type: 'audio/wave' }))).toBe('audio/wav')
    expect(audioContentType(fileLike({ type: 'AUDIO/X-WAV' }))).toBe('audio/wav')
  })

  it('未知扩展名与无扩展名回退为 application/octet-stream', () => {
    expect(audioContentType(fileLike({ name: 'meeting.flac', type: '' }))).toBe(
      'application/octet-stream',
    )
    expect(audioContentType(fileLike({ name: 'no-extension', type: '' }))).toBe(
      'application/octet-stream',
    )
  })
})

describe('isRetryableStatus', () => {
  it('网络抖动/限流/服务端错误可重试', () => {
    for (const status of [408, 429, 500, 502, 503, 599]) {
      expect(isRetryableStatus(status)).toBe(true)
    }
  })

  it('确定性 4xx 与非法状态不重试', () => {
    for (const status of [200, 400, 401, 403, 404, 409, 499, 600]) {
      expect(isRetryableStatus(status)).toBe(false)
    }
  })
})

describe('isValidStoredSession', () => {
  it('字段完整且数值合理时有效', () => {
    expect(isValidStoredSession(storedSession({ completed: [[1, '"etag-1"']] }))).toBe(true)
  })

  it('非对象、空 uploadId、缺 objectKey 都无效', () => {
    expect(isValidStoredSession(null)).toBe(false)
    expect(isValidStoredSession('x')).toBe(false)
    expect(isValidStoredSession({})).toBe(false)
    expect(isValidStoredSession(storedSession({ uploadId: '' }))).toBe(false)
    expect(
      isValidStoredSession({ ...storedSession(), objectKey: undefined }),
    ).toBe(false)
  })

  it('chunkSize/totalParts 必须是正整数', () => {
    for (const chunkSize of [0, -1, 1.5, Number.NaN]) {
      expect(isValidStoredSession(storedSession({ chunkSize }))).toBe(false)
    }
    for (const totalParts of [0, -1, 1.5, Number.NaN]) {
      expect(isValidStoredSession(storedSession({ totalParts }))).toBe(false)
    }
  })

  it('completed 必须是 [正整数, string] 数组', () => {
    expect(isValidStoredSession({ ...storedSession(), completed: 'x' })).toBe(false)
    expect(
      isValidStoredSession(
        storedSession({ completed: [1, '"etag-1"'] as unknown as [number, string][] }),
      ),
    ).toBe(false)
    expect(
      isValidStoredSession(
        storedSession({ completed: [['1', 'etag']] as unknown as [number, string][] }),
      ),
    ).toBe(false)
    expect(
      isValidStoredSession(
        storedSession({ completed: [[1]] as unknown as [number, string][] }),
      ),
    ).toBe(false)
    expect(
      isValidStoredSession(
        storedSession({ completed: [[1, 2]] as unknown as [number, string][] }),
      ),
    ).toBe(false)
    expect(isValidStoredSession(storedSession({ completed: [[0, 'etag']] }))).toBe(false)
  })
})

describe('resolveResumeSession', () => {
  it('会话进行中且分片布局一致时复用，已完成分片以后端为准', () => {
    const decision = resolveResumeSession(
      storedSession({ completed: [[1, '"local-etag"']] }),
      remoteSession({
        uploadedParts: [
          { partNumber: 1, etag: '"remote-1"', size: 5 },
          { partNumber: 2, etag: '"remote-2"', size: 5 },
        ],
      }),
    )

    expect(decision.action).toBe('resume')
    if (decision.action !== 'resume') return
    expect(decision.session).toEqual({
      uploadId: 'upload-1',
      objectKey: 'minutes/42/2026-10/a.m4a',
      chunkSize: 5,
      totalParts: 3,
    })
    expect([...decision.completed.entries()]).toEqual([
      [1, '"remote-1"'],
      [2, '"remote-2"'],
    ])
  })

  it('后端会话已完成/已中止时重新开始', () => {
    expect(resolveResumeSession(storedSession(), remoteSession({ status: 20 }))).toEqual({
      action: 'restart',
    })
    expect(resolveResumeSession(storedSession(), remoteSession({ status: 30 }))).toEqual({
      action: 'restart',
    })
  })

  it('分片数或分片大小不一致时重新开始', () => {
    expect(resolveResumeSession(storedSession(), remoteSession({ totalParts: 4 }))).toEqual({
      action: 'restart',
    })
    expect(resolveResumeSession(storedSession(), remoteSession({ chunkSize: 10 }))).toEqual({
      action: 'restart',
    })
  })
})
