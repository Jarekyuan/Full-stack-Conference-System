/**
 * src/hooks/useAppRequest.ts
 * 基于 ahooks useRequest 的二次封装，统一处理"服务端数据请求"的 UI 侧关注点：
 * - 默认 manual（组件手动触发，避免挂载即请求）
 * - 统一错误 toast（error 为 ApiError 时用后端 msg，否则用通用文案）
 * - 透传 refreshDeps（参数变化自动重查，如日历切周）、onSuccess/onError 等 ahooks 能力
 * 与 request.ts 的分工：request.ts 管"传输/认证层"，这里管"UI 层"。
 */
import { App } from 'antd'
import { useRequest } from 'ahooks'
import type { Options } from 'ahooks/es/useRequest/src/types'
import { ApiError } from '@/api/types'

type Service<TData, TParams extends unknown[]> = (...args: TParams) => Promise<TData>

interface UseAppRequestOptions<TData, TParams extends unknown[]>
  extends Options<TData, TParams> {
  /** 是否在错误时自动 toast（默认 true；局部想自己处理错误时置 false） */
  toastError?: boolean
}

export function useAppRequest<TData, TParams extends unknown[] = []>(
  service: Service<TData, TParams>,
  options: UseAppRequestOptions<TData, TParams> = {},
) {
  const { message } = App.useApp()
  const { toastError = true, onError, ...rest } = options

  return useRequest(service, {
    manual: true,
    ...rest,
    onError: (error, params) => {
      if (toastError) {
        const msg = error instanceof ApiError ? error.message : '请求失败，请稍后重试'
        message.error(msg)
      }
      onError?.(error, params)
    },
  })
}
