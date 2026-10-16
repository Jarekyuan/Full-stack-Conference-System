package com.smt.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import lombok.RequiredArgsConstructor;

import com.smt.notification.ws.NotificationWebSocketHandler;

/**
 * WebSocket 配置（T4.6）：把 {@link NotificationWebSocketHandler} 挂到 `/ws/notifications`。
 *
 * <p>为什么用**原生 WebSocket**（`WebSocketConfigurer` + `TextWebSocketHandler`）而不是 STOMP：
 * docs/04 六章定义的是一条**自有的极简 JSON 协议**（`{type, data}` + PING/PONG 心跳），
 * 不需要 STOMP 的目的地/订阅模型。引 STOMP 等于为了"用框架"而让前端多背一个客户端库
 * （且它的 broker relay 那套在单实例内推场景下毫无用处）。
 *
 * <p>路径**不带 `/api` 前缀**：网关的 `notification-ws` 路由就是 `Path=/ws/**` 且不剥前缀
 * （见 gateway/application.yml），服务端两侧路径要一模一样。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final NotificationWebSocketHandler notificationWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWebSocketHandler, "/ws/notifications")
                // 开发期允许任意来源，理由同网关的 CORS 配置：本系统用 Bearer/query token 鉴权、
                // 不用 Cookie，陌生站点既拿不到 token，也无法借浏览器自动携带的凭证"搭车"。
                // 生产应收窄为前端域名白名单（WS 握手不受 HTTP CORS 约束，必须在这里单独配）。
                .setAllowedOriginPatterns("*");
    }
}
