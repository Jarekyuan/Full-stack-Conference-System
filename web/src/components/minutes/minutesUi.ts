/**
 * 会议纪要 UI 的纯逻辑（无 React 依赖）：
 * - `minutesEmptyStateKind`：空态展示什么，权限判断优先于会议状态；
 * - `describeMinutesError`：后端错误码 → 用户可操作文案（70003 定位到行、70006 重新上传等）。
 * 抽成纯函数是为了 Vitest 能直接覆盖全部分支；组件渲染行为仍交给 Task 18 的 e2e。
 */
import { ApiError } from '@/api/types'

/** 空态类型：uploader 表示满足上传条件，其余是提示文案的类别 */
export type MinutesEmptyStateKind =
  | 'no-permission'
  | 'cancelled'
  | 'rejected'
  | 'not-ended'
  | 'uploader'

/**
 * 空态决策。顺序有意如此：**先看权限**（无权者即使会议已结束也只显示"暂无"），
 * 再看已取消/已拒绝（这两种终态永远不会再有纪要），最后才是"结束后可上传"。
 */
export function minutesEmptyStateKind(
  status: number,
  canManage: boolean,
): MinutesEmptyStateKind {
  if (!canManage) return 'no-permission'
  if (status === 50) return 'cancelled'
  if (status === 60) return 'rejected'
  if (status !== 40) return 'not-ended'
  return 'uploader'
}

/** 错误类别：调用方按类别决定 toast / 内联提示 / 静默 */
export type MinutesErrorKind =
  | 'transcript-format'
  | 'file-too-large'
  | 'upload-session-expired'
  | 'rate-limited'
  | 'forbidden'
  | 'abort'
  | 'api'
  | 'unknown'

export interface MinutesErrorInfo {
  kind: MinutesErrorKind
  /** 可直接展示给用户的文案；abort 时为空串（调用方应静默） */
  message: string
  /** 70003 专用：出错行号（后端未提供时 undefined） */
  line?: number
  /** 70003 专用：后端给出的原因 */
  reason?: string
}

/**
 * 把任意异常翻译成 UI 可用的结构。
 * 70003 的 data 约定为 `{line, reason}`（设计文档 §10.2），缺失时降级为「第 ? 行：<msg>」。
 */
export function describeMinutesError(
  error: unknown,
  fallback = '操作失败，请稍后重试',
): MinutesErrorInfo {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 70003: {
        const detail = (error.data ?? null) as { line?: number; reason?: string } | null
        const line = typeof detail?.line === 'number' ? detail.line : undefined
        const reason = typeof detail?.reason === 'string' ? detail.reason : undefined
        return {
          kind: 'transcript-format',
          line,
          reason,
          message: `第 ${line ?? '?'} 行：${reason ?? error.message}`,
        }
      }
      case 70005:
        return {
          kind: 'file-too-large',
          message: '文件或转写文本超出大小上限（音频 2GiB / 文本 5MiB）',
        }
      case 70006:
        return { kind: 'upload-session-expired', message: '上传会话已过期，请重新上传' }
      case 10005:
        return { kind: 'rate-limited', message: '请求过于频繁，请稍后再试' }
      case 10003:
        return { kind: 'forbidden', message: '无权查看会议纪要' }
      default:
        return { kind: 'api', message: error.message }
    }
  }
  if (error instanceof Error && error.name === 'AbortError') {
    return { kind: 'abort', message: '' }
  }
  return { kind: 'unknown', message: fallback }
}
