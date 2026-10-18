/**
 * 纪要 UI 纯逻辑单测：
 * - 空态决策覆盖 40/50/60/10 × canManage true/false，并守住"权限优先于状态"的顺序；
 * - 错误映射覆盖 70003（带/不带行号）、70005、70006、10005、10003、AbortError 与兜底。
 */
import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/types'
import { describeMinutesError, minutesEmptyStateKind } from './minutesUi'

describe('minutesEmptyStateKind', () => {
  it('无管理权限时一律"暂无会议纪要"，优先于会议状态', () => {
    for (const status of [10, 40, 50, 60]) {
      expect(minutesEmptyStateKind(status, false)).toBe('no-permission')
    }
  })

  it('已结束 + 可管理 → 展示上传器', () => {
    expect(minutesEmptyStateKind(40, true)).toBe('uploader')
  })

  it('已取消/已拒绝给出明确的不可上传提示', () => {
    expect(minutesEmptyStateKind(50, true)).toBe('cancelled')
    expect(minutesEmptyStateKind(60, true)).toBe('rejected')
  })

  it('其他未结束状态提示"会议结束后可上传"', () => {
    for (const status of [10, 20, 30]) {
      expect(minutesEmptyStateKind(status, true)).toBe('not-ended')
    }
  })
})

describe('describeMinutesError', () => {
  it('70003 使用 data.line/reason 定位到行', () => {
    const info = describeMinutesError(
      new ApiError(70003, '转写文本格式错误', { line: 3, reason: '缺少说话人' }),
    )
    expect(info.kind).toBe('transcript-format')
    expect(info.line).toBe(3)
    expect(info.reason).toBe('缺少说话人')
    expect(info.message).toBe('第 3 行：缺少说话人')
  })

  it('70003 缺 data 时降级为未知行号并回落到后端 message', () => {
    const info = describeMinutesError(new ApiError(70003, '转写文本格式错误', null))
    expect(info.line).toBeUndefined()
    expect(info.message).toBe('第 ? 行：转写文本格式错误')
  })

  it('70005/70006/10005 映射为固定可操作文案', () => {
    expect(describeMinutesError(new ApiError(70005, 'x')).kind).toBe('file-too-large')
    expect(describeMinutesError(new ApiError(70005, 'x')).message).toContain('2GiB')
    expect(describeMinutesError(new ApiError(70006, 'x'))).toMatchObject({
      kind: 'upload-session-expired',
      message: '上传会话已过期，请重新上传',
    })
    expect(describeMinutesError(new ApiError(10005, 'x')).kind).toBe('rate-limited')
  })

  it('10003 映射为无权查看会议纪要', () => {
    expect(describeMinutesError(new ApiError(10003, '无权限执行此操作')).message).toBe(
      '无权查看会议纪要',
    )
  })

  it('其他 ApiError 使用后端 message；AbortError 静默', () => {
    expect(describeMinutesError(new ApiError(70010, '没有有效分段'))).toEqual({
      kind: 'api',
      message: '没有有效分段',
    })
    const abort = new Error('aborted')
    abort.name = 'AbortError'
    expect(describeMinutesError(abort)).toEqual({ kind: 'abort', message: '' })
  })

  it('非 Error 值使用调用方兜底文案', () => {
    expect(describeMinutesError('boom', '导入失败，请稍后重试').message).toBe(
      '导入失败，请稍后重试',
    )
  })
})
