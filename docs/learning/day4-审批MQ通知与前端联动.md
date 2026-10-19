# Day 4：审批/MQ/通知/WS + 前端联动

> 时间：6 小时
> 目标：把“审批通过 → 发事件 → MQ → 站内信 → WebSocket 推送”这条链路讲清楚，
> 同时把缓存、限流、状态管理作为“理解/了解”侧栏。
> 今天的手敲是**跑 AFTER_COMMIT 测试 + 写一个消费幂等测试**。

---

## 1. 今日目标

- [ ] 讲清“待审批不占时段 + 审批复检 + 锁序”
- [ ] 讲清 AFTER_COMMIT、手动 ACK、消费幂等、死信
- [ ] 能读懂审批中心、通知中心和 WebSocket 联动

---

## 2. 面试先问

1. 待审批不占时段，审批通过时怎么防“时段被抢”？
2. 为什么不能在事务内直接发 MQ？
3. 消费端怎么保证不重复落库？标记为什么要写在成功之后？
4. WebSocket 怎么经过网关？为什么 token 只能放 query？
5. 缓存什么时候失效？限流为什么用 ZSET + Lua？

---

## 3. 10 分钟概念

### 3.1 审批时序

```text
approver 点“通过”
  → 房间锁（lock:meeting:{roomId}:{date}）
  → 事务：
      会议室行锁（selectIdForUpdate）
      会议行锁（selectByIdForUpdate）
      检查状态仍是 10
      检查会议室不是维护
      复检冲突（待审批期间可能被抢）
      状态机 10 → 20
      写审批记录
      发布领域事件
  → 提交事务
  → AFTER_COMMIT → RabbitMQ
```

拒绝不抢房间锁：拒绝不改变时段占用（10/60 都不占），只锁会议行即可。

### 3.2 MQ 拓扑

```text
meeting.events (topic)
  ├─ meeting.created   → 队列 meeting.created
  ├─ meeting.approved  → 队列 meeting.approved
  ├─ meeting.rejected  → 队列 meeting.rejected
  ├─ meeting.cancelled → 队列 meeting.cancelled
  ├─ meeting.updated   → 队列 meeting.updated
  └─ meeting.remind    → 队列 meeting.remind
          │ 重试耗尽/消息非法
          ▼
  meeting.events.dlx → meeting.dlq
```

队列名 == 路由键，方便排障；所有死信汇到一个 DLQ。

### 3.3 一条消息的旅程

```text
消费：
  已处理？ ──是──▶ ack（重复投递，丢弃）
     │否
  业务处理（重试 3 次）
     ├─失败──▶ nack(requeue=false) → 死信（不写标记，重放可再处理）
     └─成功──▶ 写“已处理”标记 → ack
```

- 手动 ACK：只有业务成功才 ack，失败不会静默变成功
- 标记写在成功之后：崩溃重投时不会误判为重复而丢消息
- ack 失败：标记已在，重投会被幂等挡下，不会重复发通知
- 单消费者：同队列串行，同一 msgId 的两份投递一定先后到达

### 3.4 WebSocket

```text
浏览器 new WebSocket('ws://host/ws/notifications?token=...')
  → Nginx /ws Upgrade
  → gateway 验 JWT（只对 /ws 接受 query token）
  → 注入 X-User-Id
  → notification-service 握手，按 userId 维护一组 session
  → 通知落库后 AFTER_COMMIT 推送
```

- 浏览器 WebSocket 不能自定义请求头，所以 token 只能放 query
- 同一个人多 tab/多设备：`Map<userId, Set<session>>`，不会互相挤掉
- 应用层 PING/PONG：30 秒一次，防止代理静默断链
- 单实例限制：session 表在内存，多实例要 Redis pub/sub 或粘性路由

### 3.5 缓存与限流（理解侧栏）

- 会议室列表/详情：Spring Cache 注解，30 分钟 TTL
- 日历：手写 key（房间 + 周），会议变更后按事件失效
- 限流：ZSET 存请求时间戳 + Lua 原子“清理/计数/判断/写入”，阈值放 Nacos `@RefreshScope`
- 限流异常**放行**（保护手段），分布式锁异常**失败**（正确性依赖）

---

## 4. 代码走读

### 4.1 后端：审批

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/ApprovalService.java`

`pass` 的关键顺序：

1. `lockHelper.executeWithLock(roomId, start, end, ...)`
2. `transactionTemplate.execute`
3. `meetingRoomMapper.selectIdForUpdate(roomId)`：先会议室行
4. `meetingMapper.selectByIdForUpdate(meetingId)`：后会议行
5. 校验状态仍是 10；校验房间不是维护
6. `conflictChecker.findConflicts(..., meetingId)`：复检
7. `stateMachine.transition(APPROVE)`：10 → 20
8. `insertApproval`：写审批记录
9. `eventPublisher.publishEvent(approved)`
10. 提交事务 → AFTER_COMMIT 发 MQ

锁序固定“先会议室行 → 后会议行”，与改期一致，避免交叉死锁。

`reject`：

- comment 必填
- 只锁会议行，不抢房间锁（不改变占用）
- 状态机 10 → 60，写审批记录，发 rejected 事件

### 4.2 后端：事件发布

文件：`server/meeting-service/src/main/java/com/smt/meeting/mq/MeetingEventPublisher.java`

```java
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void onMeetingEvent(MeetingDomainEvent event) {
    try {
        rabbitTemplate.convertAndSend(EXCHANGE, routingKey, message);
    } catch (Exception e) {
        log.error(...); // 不抛：业务已提交，通知是旁路
    }
}
```

- `AFTER_COMMIT`：消息只描述已经落库的事实；回滚不发
- `fallbackExecution = true`：没有事务时也执行，避免“忘了开事务 → 静默没通知”
- `try/catch`：AFTER_COMMIT 监听器抛异常会冒泡回提交调用方；业务已成功，不能因为发消息失败报 500

文件：`server/meeting-common/src/main/java/com/smt/common/mq/MeetingMqTopology.java`

- 拓扑定义放共享库：生产端/消费端同名队列参数必须一致，否则 406
- 每个事件一个队列，统一 DLX → `meeting.dlq`

### 4.3 后端：消费与幂等

文件：
- `server/notification-service/src/main/java/com/smt/notification/mq/MeetingEventConsumer.java`
- `server/notification-service/src/main/java/com/smt/notification/mq/ConsumeIdempotency.java`

`onMeetingEvent`：

- 消息结构不合法 → 直接 nack 进死信（不打印全文，保护用户内容）
- `idempotency.isProcessed(msgId)` → 已处理则 ack 丢弃
- `meetingEventRetryTemplate.execute(...)`：重试 3 次
- 成功后才 `markProcessed(msgId)`，再 ack
- 失败 → `basicNack(requeue=false)` → 死信
- ack/nack 失败只告警，不改变业务结果

`ConsumeIdempotency`：

- key `mq:idem:{msgId}`，TTL 24h
- 标记写在成功之后：崩溃重投不会误判为重复
- Redis 故障时抛异常，不降级放行：幂等是正确性前提

### 4.4 后端：WebSocket 推送

文件：
- `server/notification-service/src/main/java/com/smt/notification/ws/NotificationWebSocketHandler.java`
- `server/notification-service/src/main/java/com/smt/notification/ws/NotificationPushListener.java`

`NotificationWebSocketHandler`：

- 从握手头 `X-User-Id` 取身份；没有就 `close(4001)`
- `ConcurrentHashMap<Long, CopyOnWriteArraySet<WebSocketSession>>`
- `handleTextMessage` 处理 `PING` → 回 `PONG`
- `push(userId, json)` 遍历该用户所有 session；单条失败不影响其他

`NotificationPushListener`：

- `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`
- 推 `NOTIFICATION` + 权威的 `UNREAD_COUNT`
- 推送失败只告警：REST 仍是数据一致性来源

### 4.5 后端：缓存/限流侧栏

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/RoomService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/CalendarCacheEvictor.java`
- `server/meeting-service/src/main/java/com/smt/meeting/config/CacheConfig.java`
- `server/meeting-service/src/main/java/com/smt/meeting/aop/RateLimitAspect.java`
- `server/meeting-service/src/main/java/com/smt/meeting/config/RateLimitProperties.java`

- `RoomService`：`@Cacheable` / `@CacheEvict`，30 分钟
- `CalendarCacheEvictor`：会议变更后删旧房间/旧周缓存
- `RateLimitProperties`：`@RefreshScope`，Nacos 改值即时生效
- `RateLimitAspect`：ZSET + Lua，阈值 ≤0 关闭限流，Redis 异常放行

### 4.6 前端：审批中心与通知中心

文件：
- `web/src/pages/ApprovalCenter.tsx`
- `web/src/components/ApprovalDrawer.tsx`
- `web/src/pages/NotificationList.tsx`
- `web/src/hooks/useWebSocket.ts`
- `web/src/stores/notificationStore.ts`
- `web/src/components/notificationRouting.ts`

`ApprovalCenter`：

- `useAppRequest(listPending)` / `listHistory` 分页
- 通过/拒绝走 `ApprovalDrawer`
- 冲突 50001 → 弹冲突详情

`NotificationList`：

- REST 拉列表/未读数；WS 推送时刷新
- `notificationRouting` 根据通知类型跳会议详情/审批中心

`useWebSocket`：

- 同源地址：`ws(s)://<host>/ws/notifications?token=...`
- 断线重连前先刷新 token（access token 30 分钟会过期）
- 30 秒 PING；组件卸载清理 timer/socket
- 未登录不连，避免白刷 401

`notificationStore`：

- `unreadCount` 是服务端状态的客户端镜像
- WS 推来的 `UNREAD_COUNT` 是权威值，直接覆盖
- `version` 解决“REST 旧响应覆盖 WS 新值”的竞态
- 不 persist：刷新后重新拉

### 4.7 📘 笔记引用

> 📘 **笔记引用**：《13.微服务+部署》§三 消息队列 MQ / §三.4 消息可靠性
> 类型：A（项目直接对应）
> 笔记结论：MQ 要解决消息丢失、重复、积压；生产确认、手动 ACK、消费幂等、死信是常用手段。
> 项目落点：`MeetingEventPublisher` + `MeetingEventConsumer` + `ConsumeIdempotency`。
> 差异/提醒：项目接受“极少数重复通知”，换“崩溃后不丢消息”；这是明确的取舍。

> 📘 **笔记引用**：《11.Spring Boot》§七.1 Redis 缓存 / §七.3 WebSocket 双工通信
> 类型：A（项目直接对应）
> 笔记结论：缓存要设 TTL 和失效策略；WebSocket 适合服务端主动推送。
> 项目落点：`RoomService` / `CalendarCacheEvictor`；`NotificationWebSocketHandler`。
> 差异/提醒：项目 WS 用原生 Spring WebSocket，不是 STOMP；网关只对 `/ws` 接受 query token。

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§四.1 缓存 / §四.2 缓存的三个问题 / §四.3 Spring Cache
> 类型：A（项目直接对应）
> 笔记结论：穿透用空值缓存，击穿用互斥/逻辑过期，雪崩用随机 TTL；Spring Cache 用注解简化。
> 项目落点：`RoomService` 注解缓存；日历是手写 key，因为要按房间+周精确失效。
> 差异/提醒：项目没有做多级缓存；先把“缓存一致性”讲清楚，不堆技术。

> 📘 **笔记引用**：《2.React知识点》§五.4 现代状态管理选型
> 类型：A/B（项目直接对应 + 有差异）
> 笔记结论：Context 适合低频全局状态，Redux/Zustand 适合复杂共享状态；服务端状态交给请求库。
> 项目落点：`notificationStore` 只存角标，不存列表；列表由 `useAppRequest` 拉。
> 差异/提醒：项目用 Zustand + immer，不是 Redux Toolkit；服务端状态没有用 React Query。

> 📘 **笔记引用**：《1.前端基础知识点》§四 HTTP、运行环境
> 类型：C（纯基础补充）
> 笔记结论：HTTP 是无状态协议；WebSocket 握手复用了 HTTP Upgrade。
> 项目落点：`/ws` 的 Upgrade 头和 query token。
> 差异/提醒：这是基础题，不需要和项目每个类对应。

> 📘 **笔记引用**：《11.Spring Boot》§七.2 Spring Task 定时任务
> 类型：A/B（项目直接对应 + 有差异）
> 笔记结论：`@Scheduled` 适合周期任务，要注意线程池和幂等。
> 项目落点：`MeetingStatusTask`、`MeetingRemindTask`、`MinutesAsrTask`。
> 差异/提醒：项目用调度线程池 + 扫表补偿，不是单纯 `@Scheduled` 全量扫描。

---

## 5. 手敲练习

### 5.1 跑 AFTER_COMMIT 测试

文件：`server/meeting-service/src/test/java/com/smt/meeting/mq/MeetingEventPublisherTest.java`

```bash
cd server
mvn -pl meeting-service -Dtest=MeetingEventPublisherTest test
```

它已经有两条用例：

- `committedTransactionShouldPublishMessage`
- `rolledBackTransactionShouldNotPublishMessage`

验收：两条都通过；能解释“为什么回滚不发消息”。

### 5.2 写消费幂等测试

在 `server/notification-service/src/test/java/com/smt/notification/mq/` 下新建（学习分支）：

```java
package com.smt.notification.mq;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
class ConsumeIdempotencyPracticeTest {

    @Autowired
    private ConsumeIdempotency idempotency;

    @Test
    void markAfterProcessed() {
        String msgId = UUID.randomUUID().toString();
        assertFalse(idempotency.isProcessed(msgId));
        idempotency.markProcessed(msgId);
        assertTrue(idempotency.isProcessed(msgId));
    }
}
```

运行：

```bash
cd server
mvn -pl notification-service -Dtest=ConsumeIdempotencyPracticeTest test
```

验收：测试通过；能解释“为什么标记必须写在成功之后”，以及 ack 失败时幂等标记怎么挡住重复通知。

### 5.3 观察 WS 通知

1. 开两个浏览器 profile：user1 / approver1
2. user1 提交一场需审批会议
3. approver1 通知中心应在 1 秒内 +1
4. 点开审批中心，通过
5. user1 收到“审批通过”通知

验收：通知中心和铃铛角标都更新；Network → WS 里能看到 `NOTIFICATION` / `UNREAD_COUNT`。

---

## 6. 五道自测

### 题目

1. 审批通过为什么要复检？
2. 为什么不能在事务内直接发 MQ？
3. 消费幂等标记为什么写在成功之后？
4. 手动 ACK 和自动 ACK 的区别？
5. WS 为什么只能把 token 放 query？多实例部署有什么问题？

### 答案

1. 待审批不占时段，审批通过是“从 0 到 1 占用”，期间时段可能被抢；必须房间锁 + 行锁 + 锁内复检，失败返回 50001。
2. 发消息不可回滚；事务回滚会出现“下游收到不存在的会议”。AFTER_COMMIT 保证消息只描述已落库事实。
3. 写在处理前，进程崩溃重投时会被误判为重复而丢弃，通知永久丢失；写在成功后，最坏是极少数重复通知，不会丢。
4. 自动 ACK 在方法返回时确认，catch 异常只打日志会让失败静默成功；手动 ACK 由业务决定成功/失败，失败 nack 进死信。
5. 浏览器 WebSocket 不能自定义请求头；query token 会进日志/历史/Referer，所以只给 `/ws` 开口。多实例时 session 表在内存，A 实例推不到连在 B 实例的用户，需要 Redis pub/sub 或粘性路由。

---

## 7. 复盘卡

```text
今天最重要的 3 句话：
1. 审批通过 = 从 0 到 1 占用，必须复用房间锁 + 行锁 + 锁内复检。
2. AFTER_COMMIT 保证消息只描述已提交事实；消费幂等标记写在成功之后。
3. WS 是加速手段，REST 才是数据一致性来源；角标用权威值覆盖。

可以讲给面试官的 1 个点：
“为什么消费幂等标记不能写在处理之前”——崩溃重投会静默丢消息。
```
