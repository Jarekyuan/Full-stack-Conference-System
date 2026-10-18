import { describe, expect, it, vi } from 'vitest'

// 纯函数测试不需要真实请求层；mock 掉避免加载 axios/拦截器等模块副作用。
vi.mock('@/api/file', () => ({
  initMultipart: vi.fn(),
  getPartUrls: vi.fn(),
  getUploadSession: vi.fn(),
  completeMultipart: vi.fn(),
}))

import {
  audioContentType,
  buildResumeKey,
  missingParts,
  partRange,
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

  it('未知扩展名与无扩展名回退为 application/octet-stream', () => {
    expect(audioContentType(fileLike({ name: 'meeting.flac', type: '' }))).toBe(
      'application/octet-stream',
    )
    expect(audioContentType(fileLike({ name: 'no-extension', type: '' }))).toBe(
      'application/octet-stream',
    )
  })
})
