/**
 * src/hooks/useWebSocket.ts
 * 通知实时推送（T4.F1）：连接 / 心跳 / 指数退避重连 / 消息分发。
 *
 * 协议见 docs/04 第六章：
 *   连接  ws(s)://<同源>/ws/notifications?token=<accessToken>   ← token 只能走 query（浏览器 WS 不能自定义头）
 *   下发  {type:'NOTIFICATION',data:{...}} / {type:'UNREAD_COUNT',data:{unreadCount}}
 *   上行  {type:'PING'} → 回 {type:'PONG'}（应用层心跳）
 *
 * 三条设计取舍：
 * ① **推送只是加速，不是真相**：UNREAD_COUNT 用服务端权威值覆盖（见 notificationStore），
 *    重连成功后**再拉一次 REST** 兜底——断线期间漏掉的推送只有这样才补得回来。
 * ② **重连用指数退避 + 抖动**：断线往往是服务端整体不可用（重启/发版），固定 1 秒重试
 *    会让所有在线客户端在同一秒一起回来（惊群）。抖动把它们的重试时刻打散。
 * ③ **token 每次重连都现取**：accessToken 只有 30 分钟，长连接迟早会遇到它过期——
 *    现取（而不是闭包里抓住连接时的那个）才能用刷新后的新 token 重连。
 */
import { useEffect } from 'react'
import { getAccessToken } from '@/api/token'
import { fetchUnreadCount } from '@/api/notification'
import { useNotificationStore } from '@/stores/notificationStore'

/** 心跳间隔：30s（与 docs/04 6.1 一致）。代理/负载均衡常在 60s 无流量时静默断链 */
const HEARTBEAT_MS = 30_000
/** 重连退避：1s 起、翻倍、封顶 30s，再叠加 0~500ms 抖动 */
const RECONNECT_BASE_MS = 1_000
const RECONNECT_MAX_MS = 30_000

interface ServerMessage {
  type?: string
  data?: unknown
}

/** 拼 WS 地址：默认**同源**（开发期由 Vite 代理 /ws，生产由 Nginx 反代），可用 VITE_WS_BASE 覆盖 */
function buildUrl(): string {
  const override = import.meta.env.VITE_WS_BASE as string | undefined
  const base =
    override ?? `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
  const token = getAccessToken() ?? ''
  return `${base}/ws/notifications?token=${encodeURIComponent(token)}`
}

/**
 * 在需要接收通知的组件里调用（当前挂在 MainLayout → 登录后的整个会话内都连着）。
 * 返回值留空：状态在 notificationStore 里，消费方直接订阅 store（避免每个组件各自持有一份）。
 */
export function useNotificationSocket(): void {
  const setUnreadCount = useNotificationStore((s) => s.setUnreadCount)
  const bumpUnread = useNotificationStore((s) => s.bumpUnread)

  useEffect(() => {
    let socket: WebSocket | null = null
    let heartbeatTimer: ReturnType<typeof setInterval> | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let attempt = 0
    let closedByUs = false

    const refreshUnread = async () => {
      try {
        const { unreadCount } = await fetchUnreadCount()
        setUnreadCount(unreadCount)
      } catch {
        // 拉不到就保持原值：角标不是关键路径，别为它弹错误提示
      }
    }

    const clearTimers = () => {
      if (heartbeatTimer) clearInterval(heartbeatTimer)
      if (reconnectTimer) clearTimeout(reconnectTimer)
      heartbeatTimer = null
      reconnectTimer = null
    }

    const scheduleReconnect = () => {
      if (closedByUs || reconnectTimer) return
      const delay = Math.min(RECONNECT_BASE_MS * 2 ** attempt, RECONNECT_MAX_MS) + Math.random() * 500
      attempt += 1
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null
        connect()
      }, delay)
    }

    const connect = () => {
      // 未登录（无 token）不连：连接必被网关拒，只会白刷错误日志
      if (!getAccessToken()) return
      try {
        socket = new WebSocket(buildUrl())
      } catch {
        scheduleReconnect()
        return
      }

      socket.onopen = () => {
        attempt = 0 // 连上了就重置退避
        // 重连成功后补一次 REST：断线期间的未读变化只能这样补齐（推送是加速、REST 才是真相）
        void refreshUnread()
        heartbeatTimer = setInterval(() => {
          if (socket?.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({ type: 'PING' }))
          }
        }, HEARTBEAT_MS)
      }

      socket.onmessage = (event) => {
        let message: ServerMessage
        try {
          message = JSON.parse(event.data as string)
        } catch {
          return // 脏消息直接忽略，绝不让它把连接打断
        }
        if (message.type === 'UNREAD_COUNT') {
          const data = message.data as { unreadCount?: number } | undefined
          if (typeof data?.unreadCount === 'number') setUnreadCount(data.unreadCount)
        } else if (message.type === 'NOTIFICATION') {
          bumpUnread() // 乐观 +1：紧跟其后的 UNREAD_COUNT 会用权威值校正
        }
      }

      socket.onclose = () => {
        if (heartbeatTimer) clearInterval(heartbeatTimer)
        heartbeatTimer = null
        scheduleReconnect()
      }

      socket.onerror = () => {
        // onerror 后浏览器必定再触发 onclose：重连统一交给 onclose，避免重试两次
      }
    }

    void refreshUnread() // 首屏先拿一次真实未读数
    connect()

    return () => {
      closedByUs = true
      clearTimers()
      socket?.close()
      socket = null
    }
  }, [setUnreadCount, bumpUnread])
}
