package com.smt.notification.ws;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.notification.entity.Notification;
import com.smt.notification.service.NotificationService;

/**
 * 通知落库后实时推送（T4.6）：把 {@link NotificationCreatedEvent} 变成 WebSocket 消息。
 *
 * <h3>为什么是 AFTER_COMMIT（同 T4.1 的 MQ 事件）</h3>
 * 事务里推会出现"推送里有、库里没有"的幽灵通知——用户点开就是 404。
 * {@code fallbackExecution = true}：没有事务时也执行（否则"某条路径忘了开事务"会表现为
 * "功能都正常、就是不推送"，最难查的静默失败）。
 *
 * <h3>为什么这里必须 try/catch</h3>
 * AFTER_COMMIT 的监听器抛异常会**冒泡回提交调用方**：通知已经落库了，消费却因为"推送失败"报错
 * → 消息被 nack 进死信、重投时又被幂等挡下，一次推送失败演变成一条通知的"永不见天日"。
 * 推送是加速手段（docs/04 6.3：数据一致性以 REST 为准），失败只告警。
 *
 * <h3>推送体</h3>
 * 每条通知推一个 `{"type":"NOTIFICATION","data":{...}}`，之后**每个涉及的用户再补一条
 * `{"type":"UNREAD_COUNT","data":{"unreadCount":N}}`**——角标用权威值覆盖，
 * 前端不必自己 +1（丢一条推送、多端同时在线都会让自增的角标长期跑偏）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushListener {

    private static final String TYPE_NOTIFICATION = "NOTIFICATION";
    private static final String TYPE_UNREAD_COUNT = "UNREAD_COUNT";

    private final NotificationWebSocketHandler webSocketHandler;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            Set<Long> affectedUsers = new LinkedHashSet<>();
            for (Notification n : event.rows()) {
                webSocketHandler.push(n.getUserId(), envelope(TYPE_NOTIFICATION, notificationPayload(n)));
                affectedUsers.add(n.getUserId());
            }
            for (Long userId : affectedUsers) {
                webSocketHandler.push(userId, envelope(TYPE_UNREAD_COUNT,
                        Map.of("unreadCount", notificationService.unreadCount(userId))));
            }
        } catch (Exception e) {
            log.error("[WS] 推送失败（通知已落库，用户下次拉列表仍可见）: {}", e.getMessage(), e);
        }
    }

    /** 消息信封：`{"type":..., "data":...}`（docs/04 6.2）；LinkedHashMap 只为让日志里的 JSON 顺序稳定可读 */
    private String envelope(String type, Object data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("data", data);
        return json(message);
    }

    /** 推送体与 REST 的 NotificationVO 同字段（前端一套解析） */
    private Map<String, Object> notificationPayload(Notification n) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", n.getId());
        data.put("type", n.getType());
        data.put("title", n.getTitle());
        data.put("content", n.getContent());
        data.put("meetingId", n.getMeetingId());
        data.put("createdAt", n.getCreatedAt());
        return data;
    }

    private String json(Object value) {
        try {
            // 用容器里的 ObjectMapper：与 REST 响应同一套序列化配置（尤其 LocalDateTime 的格式）
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("推送体序列化失败", e);
        }
    }
}
