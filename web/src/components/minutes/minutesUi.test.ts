/**
 * 纪要 UI 纯逻辑单测：
 * - 空态决策覆盖 40/50/60/10 × canManage true/false，并守住"权限优先于状态"的顺序；
 * - 错误映射覆盖 70003（带/不带行号）、70005、70006、10005、10003、AbortError 与兜底；
 * - AI 状态映射覆盖 0/10/20/30/40 + 未知值；tooltip 转义覆盖标签/引号/实体；
 * - 洞察时长、脏数据过滤与待办 rowKey 的边界。
 */
import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/types'
import {
  MINUTES_AI_STATUS_META,
  compactList,
  describeMinutesError,
  escapeHtml,
  formatDuration,
  minutesActionItemKey,
  minutesAiStatusMeta,
  minutesEmptyStateKind,
} from './minutesUi'

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

describe('MINUTES_AI_STATUS_META', () => {
  it('五个状态各有文案、颜色与按钮文案', () => {
    expect(MINUTES_AI_STATUS_META).toEqual({
      0: { text: '未生成', color: 'default', actionLabel: '生成纪要' },
      10: { text: '待生成', color: 'blue', actionLabel: '生成纪要' },
      20: { text: '生成中', color: 'processing', actionLabel: '生成纪要' },
      30: { text: '已生成', color: 'green', actionLabel: '重新生成' },
      40: { text: '生成失败', color: 'red', actionLabel: '重试生成' },
    })
  })

  it('未知状态保留原始数字并回退默认色/按钮文案', () => {
    expect(minutesAiStatusMeta(99)).toEqual({
      text: '99',
      color: 'default',
      actionLabel: '生成纪要',
    })
  })
})

describe('escapeHtml', () => {
  it('转义标签，阻断 <img onerror> 之类的注入', () => {
    expect(escapeHtml('<img src=x onerror="alert(1)">')).toBe(
      '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;',
    )
  })

  it('同时转义单引号与 &，且 & 先处理避免二次实体', () => {
    expect(escapeHtml(`& < > " '`)).toBe('&amp; &lt; &gt; &quot; &#39;')
    expect(escapeHtml('&lt;')).toBe('&amp;lt;')
  })
})

describe('formatDuration', () => {
  it('非法值与负数按 0 处理', () => {
    expect(formatDuration(Number.NaN)).toBe('0 分 00 秒')
    expect(formatDuration(Number.POSITIVE_INFINITY)).toBe('0 分 00 秒')
    expect(formatDuration(-5000)).toBe('0 分 00 秒')
  })

  it('不足 1 小时只显示分秒', () => {
    expect(formatDuration(0)).toBe('0 分 00 秒')
    expect(formatDuration(90_000)).toBe('1 分 30 秒')
  })

  it('超过 1 小时显示小时', () => {
    expect(formatDuration(3_661_000)).toBe('1 小时 01 分 01 秒')
  })
})

describe('compactList / minutesActionItemKey', () => {
  it('过滤 null/undefined 的脏数据，空输入返回空数组', () => {
    expect(compactList([null, 'a', undefined, 'b'])).toEqual(['a', 'b'])
    expect(compactList(null)).toEqual([])
    expect(compactList(undefined)).toEqual([])
  })

  it('rowKey 带索引，完全相同的待办也不会撞 key', () => {
    const row = { task: '同步排期', owner: null, dueDate: null }
    expect(minutesActionItemKey(row, 0)).toBe('0:同步排期::')
    expect(minutesActionItemKey(row, 1)).not.toBe(minutesActionItemKey(row, 0))
    expect(minutesActionItemKey({}, 2)).toBe('2:::')
  })
})
