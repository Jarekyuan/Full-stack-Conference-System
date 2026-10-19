/**
 * 会议纪要 UI 的纯逻辑（无 React 依赖）：
 * - `minutesEmptyStateKind`：空态展示什么，权限判断优先于会议状态；
 * - `describeMinutesError`：后端错误码 → 用户可操作文案（70006 重新上传、70012 未配置等）；
 * - `MINUTES_AI_STATUS_META`：AI 状态 → 文案/颜色/按钮文案的唯一映射（头部与 AI 状态条共用）；
 * - `escapeHtml`：ECharts HTML tooltip 的转义（displayName 是用户输入，直接拼 HTML 会 XSS）；
 * - `formatDuration` / `compactList` / `minutesActionItemKey`：洞察时长与摘要表格的纯函数。
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
  | 'file-too-large'
  | 'asr-failed'
  | 'asr-not-configured'
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
}

/** 把任意异常翻译成 UI 可用的结构。 */
export function describeMinutesError(
  error: unknown,
  fallback = '操作失败，请稍后重试',
): MinutesErrorInfo {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 70005:
        return {
          kind: 'file-too-large',
          message: '文件超出大小上限（音频 2GiB）',
        }
      case 70006:
        return { kind: 'upload-session-expired', message: '上传会话已过期，请重新上传' }
      case 70011:
        return { kind: 'asr-failed', message: error.message || '语音识别失败，请重试' }
      case 70012:
        return { kind: 'asr-not-configured', message: '语音识别服务未配置，请联系管理员' }
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

export interface MinutesAiStatusMeta {
  /** 状态文案（不含 "AI " 前缀） */
  text: string
  /** antd Tag 的颜色 */
  color: string
  /** 生成按钮文案：0/10/20 生成，30 重新生成，40 重试 */
  actionLabel: string
}

/**
 * AI 生成状态（docs/04 §10.2：0 未生成 / 10 待生成 / 20 生成中 / 30 已生成 / 40 失败）。
 * 头部状态标签与 MinutesAiBar 共用这一份映射，避免两处文案/颜色漂移。
 */
export const MINUTES_AI_STATUS_META: Record<number, MinutesAiStatusMeta> = {
  0: { text: '未生成', color: 'default', actionLabel: '生成纪要' },
  10: { text: '待生成', color: 'blue', actionLabel: '生成纪要' },
  20: { text: '生成中', color: 'processing', actionLabel: '生成纪要' },
  30: { text: '已生成', color: 'green', actionLabel: '重新生成' },
  40: { text: '生成失败', color: 'red', actionLabel: '重试生成' },
}

/** 未知状态保留原始数字，颜色与按钮文案回退到安全默认值 */
export function minutesAiStatusMeta(aiStatus: number): MinutesAiStatusMeta {
  return (
    MINUTES_AI_STATUS_META[aiStatus] ?? {
      text: String(aiStatus),
      color: 'default',
      actionLabel: '生成纪要',
    }
  )
}

/**
 * 转义 ECharts HTML tooltip 中插入的动态文本。
 * ECharts 的 formatter 返回值会写进 innerHTML；发言人名称由用户重命名，直接拼接可执行脚本。
 */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 毫秒 → “X 分 YY 秒” / “X 小时 YY 分 ZZ 秒”；非法值与负数按 0 处理 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0 分 00 秒'
  const total = Math.floor(ms / 1000)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  if (hours > 0) {
    return `${hours} 小时 ${String(minutes).padStart(2, '0')} 分 ${String(seconds).padStart(2, '0')} 秒`
  }
  return `${minutes} 分 ${String(seconds).padStart(2, '0')} 秒`
}

/** 过滤历史脏数据里的 null/undefined 元素（后端 VO 类型上不可空，但旧数据可能越界） */
export function compactList<T>(
  items: readonly (T | null | undefined)[] | null | undefined,
): T[] {
  return (items ?? []).filter((item): item is T => item !== null && item !== undefined)
}

/** 待办行 key：任务/负责人/截止时间可能重复，带上索引保证唯一 */
export function minutesActionItemKey(
  row: { task?: string | null; owner?: string | null; dueDate?: string | null },
  index?: number,
): string {
  return `${index ?? 0}:${row.task ?? ''}:${row.owner ?? ''}:${row.dueDate ?? ''}`
}
