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
 * ③ **重连前先"踢一脚"刷新**（2026-10-16 评审 R1 修正）：只做到"现取"是不够的——
 *    token 过了 30 分钟，`getAccessToken()` 拿到的**还是那个过期的**，重连会被网关 401 拒掉，
 *    然后无限退避重试、角标静默停更（表现为"最近没人找我"，而不是报错）。
 *    而浏览器的 WebSocket API **读不到握手失败的 HTTP 状态码**，没法"失败后再去刷新"。
 *    所以必须在重连前主动发一次 REST（refreshUnread）——它会走 axios 的 401 拦截器触发
 *    无感刷新（刷新失败会自动跳登录），刷新完再连。
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
  const bumpTick = useNotificationStore((s) => s.bumpTick)

  useEffect(() => {
    let socket: WebSocket | null = null
    let heartbeatTimer: ReturnType<typeof setInterval> | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let attempt = 0
    let closedByUs = false

    const refreshUnread = async () => {
      // 记下发请求前的 version：响应回来的路上若 WS 推来了新值，这次结果就该丢（见 store 注释）
      const versionBefore = useNotificationStore.getState().version
      try {
        const { unreadCount } = await fetchUnreadCount()
        setUnreadCount(unreadCount, versionBefore)
        // 断线期间可能漏过 NOTIFICATION；重连后也让通知中心列表补拉一次
        bumpTick()
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
        // 先刷新 token 再连（见类注释 ③）：这次 REST 顺带把"断线期间漏掉的未读变化"也补齐了
        void refreshUnread().finally(connect)
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
          bumpTick()   // 通知中心若正开着，列表也拉一次，避免角标变了列表没变（O12）
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

    // 挂载时先拿一次（**即使 WS 连不上，角标也是对的**——推送只是加速）；
    // onopen 时再拿一次（覆盖"断线期间的未读变化"）。两次重复请求是有意的，代价可忽略。
    void refreshUnread()
    connect()

    return () => {
      closedByUs = true
      clearTimers()
      socket?.close()
      socket = null
    }
  }, [setUnreadCount, bumpUnread, bumpTick])
}
