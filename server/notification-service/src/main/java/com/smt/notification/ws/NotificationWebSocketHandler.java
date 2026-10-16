package com.smt.notification.ws;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * 通知实时推送（T4.6）：内存维护"用户 → 会话集合"，通知落库后往该用户的所有会话推。
 *
 * <h3>身份从哪来</h3>
 * 握手请求里的 `X-User-Id`（网关注入，见 docs/04 6.1）。**没有它就 close(4001)**：
 * 直连业务端口（只绑回环）也订阅不到任何人的通知——失败要往"关掉"的方向倒，不能往"放行"倒。
 *
 * <h3>为什么是"用户 → 一组会话"而不是"用户 → 一个会话"</h3>
 * 同一个人可能开着多个标签页/多台设备，任何一个都该收到通知。
 * 用 Set 而不是单个 session，避免"后开的标签页把先开的挤掉"。
 *
 * <h3>并发容器为什么这么选</h3>
 * 注册/注销发生在**不同的容器线程**（每条连接一个收消息线程），推送发生在消息消费线程：
 * `ConcurrentHashMap` + 每用户的 `CopyOnWriteArraySet`——读多写极少（发通知时遍历、连接时改集合），
 * 恰好是 CopyOnWrite 的适用场景（遍历时不需要加锁）。
 *
 * <h3>已知边界（单实例假设）</h3>
 * 会话表在**本进程内存**里：多实例部署时，A 实例产生的通知推不到连在 B 实例上的用户。
 * 真上多实例要把这张表外置（Redis pub/sub 广播给各实例，或按 userId 做粘性路由）——
 * 本项目单实例部署，先不做，但**别把它当成"天然支持集群"**（docs/02 5.6 已记）。
 */
@Slf4j
@Component
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    /** 无身份（缺 X-User-Id）时的关闭码：4001 = 未认证，与 docs/04 6.1 的约定一致 */
    public static final CloseStatus UNAUTHORIZED = new CloseStatus(4001, "无身份信息");

    private final Map<Long, CopyOnWriteArraySet<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Long userId = resolveUserId(session);
        if (userId == null) {
            log.warn("[WS] 握手缺少 X-User-Id（未经网关？），关闭连接: session={}", session.getId());
            session.close(UNAUTHORIZED);
            return;
        }
        sessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("[WS] 连接建立: userId={}, session={}, 当前连接数={}", userId, session.getId(), sessionCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        // 应用层心跳（docs/04 6.2）：前端 30s 一次，双保险于 TCP 层（代理/负载均衡可能静默断链）
        String payload = message.getPayload();
        if (payload != null && payload.contains("PING")) {
            session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = resolveUserId(session);
        if (userId == null) {
            return;
        }
        CopyOnWriteArraySet<WebSocketSession> set = sessions.get(userId);
        if (set != null) {
            set.remove(session);
            // 最后一个会话关掉就删掉整个 key：否则会话表会随着"来过又走了"的用户一直长大（内存泄漏）
            if (set.isEmpty()) {
                sessions.remove(userId, set);
            }
        }
        log.info("[WS] 连接关闭: userId={}, session={}, 状态={}, 当前连接数={}",
                userId, session.getId(), status.getCode(), sessionCount());
    }

    /** 给某个用户的所有会话推一条 JSON（由 {@link NotificationPushListener} 在事务提交后调用） */
    public void push(Long userId, String json) {
        CopyOnWriteArraySet<WebSocketSession> set = sessions.get(userId);
        if (set == null || set.isEmpty()) {
            return; // 人不在线：不推（通知已落库，下次打开列表照样能看到）
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : set) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                // 单个会话发送失败（对端已断但关闭事件还没到）不能影响其他会话
                log.warn("[WS] 推送失败，关闭该会话: userId={}, session={}, err={}", userId, session.getId(), e.getMessage());
                closeQuietly(session);
            } catch (IllegalStateException e) {
                // 并发写同一个 WebSocketSession 会抛 IllegalStateException（Tomcat 不允许并发 send）
                log.warn("[WS] 会话正忙，跳过本次推送: userId={}, session={}", userId, session.getId());
            }
        }
    }

    /** 当前连接总数（日志用） */
    public int sessionCount() {
        return sessions.values().stream().mapToInt(Collection::size).sum();
    }

    private Long resolveUserId(WebSocketSession session) {
        String header = session.getHandshakeHeaders().getFirst("X-User-Id");
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException e) {
            log.warn("[WS] X-User-Id 非法: {}", header);
            return null;
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // 关不掉就算了，GC 会带走它
        }
    }
}
