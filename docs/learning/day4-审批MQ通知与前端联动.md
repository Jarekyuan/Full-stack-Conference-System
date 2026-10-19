# Day 4：审批/MQ/通知/WS + 前端联动

> 时间：6 小时
> 目标：把“审批通过 → 发事件 → MQ → 站内信 → WebSocket 推送”这条链路讲清楚，
> 同时把缓存、限流、状态管理作为“理解/了解”侧栏。
> 今天的手敲是**跑 AFTER_COMMIT 测试 + 写消费幂等测试 + 观察 WS 通知**。

---

## 1. 今日目标

### 1.1 学完能做到

- [ ] 讲清“待审批不占时段 + 审批复检 + 锁序”
- [ ] 讲清 AFTER_COMMIT、手动 ACK、消费幂等、死信
- [ ] 能读懂审批中心、通知中心和 WebSocket 联动
- [ ] 能讲清缓存失效和滑动窗口限流的思路
- [ ] 能把“事件驱动 + 最终一致”迁移到订单/支付等场景

### 1.2 时间分配（6h）

| 时间段 | 内容 | 产出 |
|--------|------|------|
| 0:00–1:00 | 概念：审批时序、MQ 可靠性、WS | 一张事件链路图 |
| 1:00–2:30 | 后端走读：审批、发布、消费、推送 | 能口述消息旅程 |
| 2:30–3:30 | 前端走读：审批中心、通知中心、WS | 能口述角标更新 |
| 3:30–5:00 | 手敲：AFTER_COMMIT、幂等、WS | 测试通过 + 通知可见 |
| 5:00–6:00 | 自测 + 复盘 + 举一反三 | 8 道题 + 复盘卡 |

### 1.3 前置

- 服务已启动（compose 或本地）
- RabbitMQ/Redis 可用
- 准备两个浏览器 profile：user1 / approver1

### 1.4 今天的“一张图”

```text
审批通过
  → 房间锁 + 事务
  → 会议室行锁 + 会议行锁
  → 复检冲突
  → 状态机 10→20 + 审批记录
  → 事务内发布领域事件
  → 提交事务
  → AFTER_COMMIT 发 MQ
  → notification-service 消费
  → 幂等检查 → 重试 → 落库
  → AFTER_COMMIT 推 WS
  → 前端角标/通知中心更新
```

记住三句话：

- **待审批不占时段，通过必须复检**
- **AFTER_COMMIT 保证消息只描述已提交事实**
- **消费幂等标记写在成功之后**

---

## 2. 面试先问

先自己想 30 秒，答案在第 6 节。

1. 待审批不占时段，审批通过时怎么防“时段被抢”？
2. 为什么不能在事务内直接发 MQ？
3. 消费端怎么保证不重复落库？标记为什么要写在成功之后？
4. 手动 ACK 和自动 ACK 的区别？失败为什么要进死信？
5. WebSocket 怎么经过网关？为什么 token 只能放 query？
6. 缓存什么时候失效？缓存三大问题怎么处理？
7. 限流为什么用 ZSET + Lua？为什么异常时放行？
8. WS 推送失败/多实例部署会有什么问题？

> 这 8 个问题覆盖“审批一致性 + MQ 可靠性 + 实时推送 + 缓存/限流”，是微服务高频组合。

---

## 3. 10 分钟概念

### 3.1 审批为什么是“从 0 到 1 占用”

- 10 待审批：不占时段（R-4）
- 20 已预约：占时段
- 通过 = 把会议从“不占”变成“占”

所以审批通过和新建预约是同一种竞态：

- 只锁会议行不够（新建锁的是另一行）
- 必须复用房间锁 + 会议室行锁 + 锁内复检

拒绝不改变占用（10/60 都不占），只锁会议行即可。

### 3.2 锁序：先会议室行 → 后会议行

审批通过同时锁两把：

```text
会议室行锁（selectIdForUpdate）
会议行锁（selectByIdForUpdate）
```

顺序固定，与改期/取消一致；反过来会交叉持锁死锁。

### 3.3 为什么事务内不能直接发 MQ

“发消息”是不可回滚的副作用：

```text
开始事务
  更新会议
  发 MQ  ← 已经发出去了
  回滚
```

下游收到一场并不存在的会议：通知发了、缓存清了，库里什么都没有。

正确做法：

```text
事务内：发布 Spring 事件（只是注册监听）
提交后：AFTER_COMMIT 监听器发 MQ
回滚：不发
```

### 3.4 为什么需要 `fallbackExecution = true`

默认 `AFTER_COMMIT` 监听器只在有事务时执行。

如果某个写路径忘了开事务：

- 功能正常
- 就是没有通知
- 最难查的静默失败

`fallbackExecution = true`：无事务时立即执行，语义同样成立（数据已自动提交）。

### 3.5 为什么 AFTER_COMMIT 监听器要 try/catch

Spring 的 `invokeAfterCommit` 不吞异常：

- 数据已经提交
- 监听器抛异常冒泡回调用方
- 接口返回 500
- 用户以为失败，实际成功 → 重复提交

所以只告警不抛：通知是旁路，不能反过来影响主流程。

### 3.6 MQ 拓扑

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

设计点：

- 队列名 == 路由键：管理台一眼对得上
- 拓扑放共享库：生产端/消费端同名队列参数必须一致，否则 406
- 所有死信汇到一个 DLQ：排障通道，不是业务通道
- 死信保留原 routing key 和 `x-death` 头

### 3.7 一条消息的旅程

```text
消费：
  已处理？ ──是──▶ ack（重复投递，丢弃）
     │否
  业务处理（重试 3 次，指数退避）
     ├─失败──▶ nack(requeue=false) → 死信（不写标记，重放可再处理）
     └─成功──▶ 写“已处理”标记 → ack
```

为什么手动 ACK：

- 自动 ACK 在方法返回时就确认
- catch 异常只打日志 → broker 认为成功 → 消息被删
- 失败静默变成成功

为什么失败进死信而不是 requeue：

- requeue 立即重投，毒消息会无限打转
- 代码里已经重试 3 次，还失败说明需要人看

为什么标记写在成功之后：

- 写在处理前：进程崩溃 → broker 重投 → 新进程看到标记 → 判重复 → 消息永久丢失
- 写在成功后：最坏是极少数重复通知，不会丢

### 3.8 WebSocket

```text
new WebSocket('ws://host/ws/notifications?token=...')
  → Nginx Upgrade
  → gateway 验 JWT（只对 /ws 接受 query token）
  → 注入 X-User-Id
  → notification-service 握手
  → Map<userId, Set<session>>
```

关键点：

- 浏览器 WS 不能自定义请求头 → token 放 query
- 同一个用户多 tab/多设备 → Set，不互相挤掉
- 应用层 PING/PONG 30 秒，防代理静默断链
- 单实例：session 表在内存；多实例要 Redis pub/sub 或粘性路由
- 推送失败只告警：REST 才是数据一致性来源

### 3.9 前端角标：为什么用权威值覆盖

`notificationStore`：

- `unreadCount` 是服务端状态的客户端镜像
- WS 推来的 `UNREAD_COUNT` 是权威值，直接覆盖
- 不靠前端自己 +1：丢推送/多端在线会让角标长期偏离
- `version`：解决“REST 旧响应覆盖 WS 新值”的竞态
- 不 persist：刷新后重新拉

### 3.10 缓存与限流侧栏

缓存：

- 会议室列表/详情：Spring Cache 注解，30 分钟
- 日历：手写 key（房间 + 周），会议变更后失效
- 三大问题：穿透（空值缓存）、击穿（互斥/逻辑过期）、雪崩（随机 TTL）

限流：

- ZSET 存请求时间戳
- Lua 原子“清理过期 + 计数 + 判断 + 写入”
- 阈值放 Nacos `@RefreshScope`
- Redis 异常放行：限流是保护，不是业务前提
- 对比：分布式锁异常必须失败（正确性依赖）

### 3.11 记忆法：审批、事件、消费、推送

```text
审批：待审不占 → 通过复检 → 10→20
事件：事务内发布 → AFTER_COMMIT 发 MQ
消费：先查幂等 → 重试 → 成功写标记 → ACK
推送：落库后 AFTER_COMMIT → WS → 权威角标
```

---

## 4. 代码走读

### 4.1 后端：审批

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/ApprovalService.java`

#### 4.1.1 pass

```java
public ApprovalResultVO pass(Long meetingId, String comment, Long approverId) {
    Meeting meeting = loadMeeting(meetingId);

    return lockHelper.executeWithLock(meeting.getRoomId(), meeting.getStartTime(), meeting.getEndTime(), () ->
        transactionTemplate.execute(tx -> {
            meetingRoomMapper.selectIdForUpdate(meeting.getRoomId());

            Meeting locked = meetingMapper.selectByIdForUpdate(meetingId);
            if (locked == null) throw new BusinessException(MEETING_NOT_FOUND);
            if (locked.getStatus() != PENDING_APPROVAL) {
                throw new BusinessException(APPROVAL_ALREADY_HANDLED);
            }

            MeetingRoom room = meetingRoomMapper.selectById(locked.getRoomId());
            if (room == null) throw new BusinessException(ROOM_NOT_FOUND);
            if (room.getStatus() == 1) throw new BusinessException(ROOM_MAINTENANCE);

            List<MeetingConflictVO> conflicts = conflictChecker.findConflicts(
                locked.getRoomId(), locked.getStartTime(), locked.getEndTime(), meetingId);
            if (!conflicts.isEmpty()) {
                throw new BusinessException(APPROVAL_CONFLICT, Map.of("conflicts", conflicts));
            }

            stateMachine.transition(meetingId, APPROVE, approverId, comment);
            insertApproval(meetingId, approverId, "PASS", comment);
            eventPublisher.publishEvent(MeetingDomainEvent.approved(...));
            return new ApprovalResultVO(meetingId, RESERVED);
        }));
}
```

逐段：

1. `loadMeeting`：先快速失败（会议不存在）
2. 房间锁：覆盖会议时段
3. 会议室行锁：数据库兜底 + 锁序
4. 会议行锁：同一条会议的并发审批串行
5. 状态检查：防两个人同时点通过
6. 房间维护检查：待审批期间房间可能被置维护
7. 冲突复检：待审批不占时段，通过时可能被抢
8. 状态机 10→20
9. 审批记录
10. 事件发布（事务内）→ AFTER_COMMIT 发 MQ

#### 4.1.2 reject

```java
public ApprovalResultVO reject(Long meetingId, String comment, Long approverId) {
    if (StrUtil.isBlank(comment)) {
        throw new BusinessException(PARAM_ERROR, "拒绝时必须填写理由");
    }
    return transactionTemplate.execute(tx -> {
        Meeting locked = meetingMapper.selectByIdForUpdate(meetingId);
        if (locked == null) throw new BusinessException(MEETING_NOT_FOUND);
        if (locked.getStatus() != PENDING_APPROVAL) {
            throw new BusinessException(APPROVAL_ALREADY_HANDLED);
        }
        stateMachine.transition(meetingId, REJECT, approverId, comment);
        insertApproval(meetingId, approverId, "REJECT", comment);
        eventPublisher.publishEvent(MeetingDomainEvent.rejected(...));
        return new ApprovalResultVO(meetingId, REJECTED);
    });
}
```

为什么拒绝不抢房间锁：

- 拒绝不改变时段占用（10/60 都不占）
- 只锁会议行防并发通过
- 通过那边同时持会议室行 + 会议行；拒绝只等会议行，不构成环

### 4.2 后端：事件发布

文件：`server/meeting-service/src/main/java/com/smt/meeting/mq/MeetingEventPublisher.java`

```java
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void onMeetingEvent(MeetingDomainEvent event) {
    MeetingEventMessage message = MeetingEventMessage.of(UUID.randomUUID().toString(), event);
    String routingKey = message.routingKey();
    try {
        rabbitTemplate.convertAndSend(MeetingMqTopology.EXCHANGE, routingKey, message);
        log.info("[MQ] 已发送事件 {} meetingId={} msgId={}", routingKey, event.meetingId(), message.msgId());
    } catch (Exception e) {
        log.error("[MQ] 事件发送失败（业务已提交，本条通知丢失）...", e);
    }
}
```

- `AFTER_COMMIT`：消息只描述已落库事实
- `fallbackExecution=true`：无事务也执行
- `try/catch`：不让发消息失败影响已提交业务
- 代价：发送失败这条通知丢失（项目取舍“接受漏发”）

### 4.3 后端：MQ 拓扑

文件：`server/meeting-common/src/main/java/com/smt/common/mq/MeetingMqTopology.java`

```java
public static final String EXCHANGE = "meeting.events";
public static final String DEAD_LETTER_EXCHANGE = "meeting.events.dlx";
public static final String DEAD_LETTER_QUEUE = "meeting.dlq";

public static Queue eventQueue(MeetingEventType type) {
    return QueueBuilder.durable(type.routingKey())
            .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
            .build();
}
```

- `durable=true`：broker 重启队列还在
- `x-dead-letter-exchange`：失败转死信
- 不设 `x-dead-letter-routing-key`：沿用原 routing key，保留“哪个事件死了”
- 共享库定义：生产/消费两端参数一致，避免 406

### 4.4 后端：消费与幂等

文件：
- `server/notification-service/src/main/java/com/smt/notification/mq/MeetingEventConsumer.java`
- `server/notification-service/src/main/java/com/smt/notification/mq/ConsumeIdempotency.java`

#### 4.4.1 消费主流程

```java
@RabbitListener(queues = {QUEUE_CREATED, QUEUE_APPROVED, ...})
public void onMeetingEvent(MeetingEventMessage message, Channel channel,
                           @Header(DELIVERY_TAG) long deliveryTag) throws IOException {
    if (message == null || message.msgId() == null || message.data() == null) {
        nackQuietly(channel, deliveryTag);
        return;
    }

    String msgId = message.msgId();
    if (idempotency.isProcessed(msgId)) {
        ackQuietly(channel, deliveryTag, msgId);
        return;
    }

    try {
        meetingEventRetryTemplate.execute(ctx -> {
            notificationService.handle(message.data());
            return null;
        });
    } catch (Exception e) {
        nackQuietly(channel, deliveryTag); // 不写标记，进死信
        return;
    }

    idempotency.markProcessed(msgId); // 成功后才写
    ackQuietly(channel, deliveryTag, msgId);
}
```

- 消息结构非法：直接死信
- 已处理：ack 丢弃
- 业务失败：重试 3 次 → nack → 死信，不写标记
- 成功：写标记 → ack
- ack 失败：标记已在，重投会被幂等挡下

#### 4.4.2 幂等存储

```java
KEY_PREFIX = "mq:idem:";
DONE_TTL = Duration.ofHours(24);

public boolean isProcessed(String msgId) {
    return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + msgId));
}

public void markProcessed(String msgId) {
    redis.opsForValue().set(KEY_PREFIX + msgId, "S", DONE_TTL);
}
```

- Redis 故障时抛异常 → 消息不 ack、被重投
- 不降级放行：幂等是正确性前提
- 标记写在成功之后：崩溃重投不会丢消息

### 4.5 后端：WebSocket

文件：
- `server/notification-service/src/main/java/com/smt/notification/ws/NotificationWebSocketHandler.java`
- `server/notification-service/src/main/java/com/smt/notification/ws/NotificationPushListener.java`

#### 4.5.1 Handler

```java
private final Map<Long, CopyOnWriteArraySet<WebSocketSession>> sessions = new ConcurrentHashMap<>();

@Override
public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    Long userId = resolveUserId(session); // X-User-Id
    if (userId == null) {
        session.close(new CloseStatus(4001, "无身份信息"));
        return;
    }
    sessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
}

@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
    if (message.getPayload().contains("PING")) {
        session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
    }
}

public void push(Long userId, String json) {
    CopyOnWriteArraySet<WebSocketSession> set = sessions.get(userId);
    if (set == null) return;
    for (WebSocketSession session : set) {
        try {
            if (session.isOpen()) session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            closeQuietly(session);
        } catch (IllegalStateException e) {
            // Tomcat 不允许并发 send，跳过
        }
    }
}
```

- 用户 → 一组 session：多 tab/多设备
- `ConcurrentHashMap` + `CopyOnWriteArraySet`：读多写少
- 单条发送失败不影响其他
- 无身份关闭 4001

#### 4.5.2 PushListener

```java
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void onNotificationCreated(NotificationCreatedEvent event) {
    try {
        Set<Long> affectedUsers = new LinkedHashSet<>();
        for (Notification n : event.rows()) {
            webSocketHandler.push(n.getUserId(), envelope("NOTIFICATION", payload(n)));
            affectedUsers.add(n.getUserId());
        }
        for (Long userId : affectedUsers) {
            webSocketHandler.push(userId, envelope("UNREAD_COUNT",
                Map.of("unreadCount", notificationService.unreadCount(userId))));
        }
    } catch (Exception e) {
        log.error("[WS] 推送失败（通知已落库）...", e);
    }
}
```

- 落库后 AFTER_COMMIT 推送
- 推 `NOTIFICATION` + 权威 `UNREAD_COUNT`
- 推送失败只告警：REST 仍可拉到

### 4.6 后端：缓存与限流

#### 4.6.1 Spring Cache

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/RoomService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/config/CacheConfig.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/CalendarCacheEvictor.java`

```java
@Cacheable(cacheNames = ROOM_LIST_CACHE, key = "#groupId + ':' + #capacity + ':' + #status + ':' + #device")
public List<RoomVO> list(...) { ... }

@CacheEvict(cacheNames = {ROOM_LIST_CACHE, ROOM_DETAIL_CACHE}, allEntries = true)
public void save(...) { ... }
```

- 会议室列表/详情：注解缓存，30 分钟
- 日历：手写 key（房间+周），因为要精确失效
- 会议变更后：`CalendarCacheEvictor.evict(roomId, start, end)` 删旧房间/旧周

#### 4.6.2 限流

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/aop/RateLimitAspect.java`
- `server/meeting-service/src/main/java/com/smt/meeting/config/RateLimitProperties.java`

```java
private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
    local key = KEYS[1]
    local now = tonumber(ARGV[1])
    local windowMs = tonumber(ARGV[2])
    local limit = tonumber(ARGV[3])
    local member = ARGV[4]
    redis.call('ZREMRANGEBYSCORE', key, 0, now - windowMs)
    local count = redis.call('ZCARD', key)
    if count >= limit then return -1 end
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, windowMs)
    return count + 1
    """, Long.class);
```

- ZSET 存时间戳：滑动窗口，没有固定窗口边界漏洞
- Lua 原子：清理/计数/判断/写入不会被并发插入
- `RateLimitProperties` 加 `@RefreshScope`：Nacos 改阈值即时生效
- 异常放行：限流是保护，不是业务前提

### 4.7 前端：审批中心与通知中心

文件：
- `web/src/pages/ApprovalCenter.tsx`
- `web/src/components/ApprovalDrawer.tsx`
- `web/src/pages/NotificationList.tsx`
- `web/src/hooks/useWebSocket.ts`
- `web/src/stores/notificationStore.ts`
- `web/src/components/notificationRouting.ts`

#### 4.7.1 ApprovalCenter

```tsx
const pending = useAppRequest(() => listPending(page, PAGE_SIZE), {
  refreshDeps: [page],
})
const history = useAppRequest(() => listHistory(page, PAGE_SIZE), {
  refreshDeps: [page],
})
```

- 待办/已办分页
- 通过/拒绝走 `ApprovalDrawer`
- 50001 冲突 → 弹冲突详情

#### 4.7.2 useWebSocket

```ts
function buildUrl() {
  const base = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}`
  return `${base}/ws/notifications?token=${encodeURIComponent(getAccessToken() ?? '')}`
}

const socket = new WebSocket(buildUrl())
socket.onmessage = (event) => {
  const msg = JSON.parse(event.data)
  if (msg.type === 'NOTIFICATION') { ... }
  if (msg.type === 'UNREAD_COUNT') {
    setUnreadCount(msg.data.unreadCount)
  }
}
heartbeatTimer = setInterval(() => socket.send('PING'), 30_000)
socket.onclose = () => {
  if (closedByUs) return
  reconnectTimer = setTimeout(connect, ...)
}
```

- 同源地址，开发/生产都由代理转发
- token 放 query（浏览器 WS 不能自定义头）
- 30 秒心跳
- 断线重连前先刷新 token
- 卸载时清理 timer/socket

#### 4.7.3 notificationStore

```ts
setUnreadCount: (count, ifUnchangedSince) => {
  set((state) => {
    if (ifUnchangedSince !== undefined && state.version !== ifUnchangedSince) return
    state.unreadCount = Math.max(count, 0)
    state.version += 1
  })
}
```

- 权威值覆盖，不靠自增
- `version` 丢弃迟到的 REST 旧响应
- 登出 `reset()`，避免下一个用户看到上一个人的角标
- 不 persist：服务端状态

### 4.8 边界与异常

| 场景 | 行为 | 说明 |
|------|------|------|
| 待审批被抢 | 50001 + conflicts | 审批复检 |
| 两人同时审批 | 后到者 50002 | 会议行锁 + 状态检查 |
| 房间维护 | 40005 | 锁内复查 |
| MQ 发送失败 | 业务成功、通知丢失 | 旁路，不影响主流程 |
| 消费失败 | 重试 3 次 → 死信 | 不写标记 |
| 重复投递 | ack 丢弃 | 幂等标记 |
| ack 失败 | 重投 → 幂等挡下 | 不会重复通知 |
| WS 无身份 | close 4001 | 防止直连订阅 |
| WS 推送失败 | 只告警 | REST 仍可拉 |
| 多实例 WS | 只能推到本实例 | 需 Redis pub/sub |
| 缓存旧数据 | TTL/事件失效 | 先更库、后删缓存 |
| 限流 Redis 挂 | 放行 | 保护手段，不阻断业务 |

### 4.9 📘 笔记引用

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

### 4.10 举一反三

#### 4.10.1 事件驱动还能用在哪

- 订单创建 → 扣库存/发券/通知
- 支付成功 → 更新订单/记账/发票
- 审批通过 → 通知/日历/待办
- 用户注册 → 发欢迎信/初始化配置

套路：

```text
本地事务 + AFTER_COMMIT 事件
消费幂等 + 重试 + 死信
最终一致，不追求强一致
```

#### 4.10.2 Outbox 模式（进阶了解）

如果“业务落库”和“消息发送”必须严格一致：

```text
事务内：业务表 + outbox 表
事务后：独立 worker 扫 outbox 发 MQ
消费端幂等
```

本项目用 AFTER_COMMIT，接受“发送失败丢一条通知”；Outbox 更可靠但更重。

#### 4.10.3 WS vs SSE vs 轮询

| 方案 | 方向 | 适用 |
|------|------|------|
| 轮询 | 客户端拉 | 简单、低频 |
| SSE | 服务端推 | 单向通知、HTTP 友好 |
| WebSocket | 双向 | 聊天、协同、实时通知 |

#### 4.10.4 缓存模式

- Cache-Aside：先更库，后删缓存（项目）
- Read-Through：缓存层负责加载
- Write-Through：写缓存 + 写库
- Write-Behind：写缓存，异步写库

#### 4.10.5 反例

- 事务内直接发 MQ → 下游收到不存在的会议
- 消费幂等标记写在处理前 → 崩溃后丢消息
- 自动 ACK + catch 异常 → 失败静默成功
- WS 推送当数据源 → 推送丢失就显示错误
- 缓存先删后更库 → 并发下旧值回填
- 限流异常直接拒绝 → Redis 抖动把业务打挂

---

## 5. 手敲练习

### 5.1 跑 AFTER_COMMIT 测试

```bash
cd server
mvn -pl meeting-service -Dtest=MeetingEventPublisherTest test
```

两条用例：

- `committedTransactionShouldPublishMessage`
- `rolledBackTransactionShouldNotPublishMessage`

验收：两条都过；能解释“为什么回滚不发”。

### 5.2 消费幂等测试

在 `server/notification-service/src/test/java/com/smt/notification/mq/` 下新建（学习分支）：

```java
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

Run:

```bash
cd server
mvn -pl notification-service -Dtest=ConsumeIdempotencyPracticeTest test
```

验收：通过；能解释“标记写在成功之后”。

### 5.3 观察 WS 通知

1. 两个浏览器 profile：user1 / approver1
2. user1 提交需审批会议
3. approver1 通知中心 1 秒内 +1
4. approver1 通过
5. user1 收到“审批通过”

验收：角标/列表更新；Network → WS 看到 `NOTIFICATION` / `UNREAD_COUNT`。

### 5.4 破坏性实验（学习分支）

- 把 `@TransactionalEventListener` 改成 `@EventListener` → 事务回滚也发消息
- 把幂等标记写到 `notificationService.handle` 之前 → 模拟崩溃，观察消息丢失
- 把消费 ACK 改成自动 ACK → catch 异常后消息仍被删
- 把 WS 的 `X-User-Id` 检查去掉 → 直连可订阅任何人

### 5.5 变体：加一种通知类型

需求：会议改期也通知参会人。

- `MeetingEventType` 加 `UPDATED`（如果已有则复用）
- `NotificationService.handle` 加分支
- 前端 `notificationRouting` 加跳转
- 测试：改期后通知可见

验收：端到端跑通，旧类型不受影响。

---

## 6. 八道自测

### 题目

1. 审批通过为什么要复检？
2. 为什么不能在事务内直接发 MQ？
3. 消费幂等标记为什么写在成功之后？
4. 手动 ACK 和自动 ACK 的区别？失败为什么进死信？
5. WS 为什么只能把 token 放 query？多实例有什么问题？
6. 缓存什么时候失效？缓存三大问题怎么处理？
7. 限流为什么用 ZSET + Lua？异常为什么放行？
8. WS 推送失败/多实例部署会有什么问题？

### 答案

1. 待审批不占时段，审批通过是“从 0 到 1 占用”，期间时段可能被抢；必须房间锁 + 行锁 + 锁内复检，失败 50001。
2. 发消息不可回滚；事务回滚会出现“下游收到不存在的会议”。AFTER_COMMIT 保证消息只描述已落库事实。
3. 写在处理前，进程崩溃重投时会被误判为重复而丢弃，通知永久丢失；写在成功后，最坏是极少数重复通知，不会丢。
4. 自动 ACK 在方法返回时确认，catch 异常只打日志会让失败静默成功；手动 ACK 由业务决定成功/失败，失败 nack 进死信，避免毒消息无限重投。
5. 浏览器 WebSocket 不能自定义请求头；query token 会进日志/历史/Referer，所以只给 `/ws` 开口。多实例 session 表在内存，A 实例推不到连在 B 实例的用户，需要 Redis pub/sub 或粘性路由。
6. 会议室缓存 TTL 30 分钟 + 写操作 `@CacheEvict`；日历按事件失效。穿透：空值缓存；击穿：互斥/逻辑过期；雪崩：随机 TTL。
7. ZSET 存时间戳实现滑动窗口，避免固定窗口边界双倍流量；Lua 保证清理/计数/判断/写入原子。异常放行因为限流是保护手段，不是业务前提。
8. 推送失败只影响实时性，REST 仍可拉；多实例需要广播/粘性路由，否则部分用户收不到。

### 追问

- 如果要保证消息“绝不丢”，架构怎么改？
- 如果消费端多实例并发消费同一 msgId，幂等还够吗？
- 如果 WS 连接数很多，内存 session 表怎么优化？
- 如果缓存和数据库不一致，怎么排查和修复？

---

## 7. 复盘卡

### 7.1 三句话

```text
1. 审批通过 = 从 0 到 1 占用，必须复用房间锁 + 行锁 + 锁内复检。
2. AFTER_COMMIT 保证消息只描述已提交事实；消费幂等标记写在成功之后。
3. WS 是加速手段，REST 才是数据一致性来源；角标用权威值覆盖。
```

### 7.2 归纳：消息链路一张表

| 阶段 | 组件 | 关键动作 | 失败 |
|------|------|----------|------|
| 业务 | ApprovalService | 复检 + 状态机 + 审批记录 | 50001/50002 |
| 发布 | MeetingEventPublisher | AFTER_COMMIT 发 MQ | 告警，通知丢失 |
| 消费 | MeetingEventConsumer | 幂等 → 重试 → 落库 → 标记 → ACK | nack 死信 |
| 推送 | PushListener | AFTER_COMMIT 推 WS | 告警，REST 可拉 |
| 前端 | useWebSocket/store | 权威角标 + 重连 | 断线重连 |

### 7.3 演绎：从原则推导新场景

原则：**不可回滚的副作用放到事务提交后；消费端假设消息会重复、会失败**。

由此推导：

- 为什么 AFTER_COMMIT？——消息不能描述未提交事实
- 为什么消费幂等？——至少一次投递
- 为什么手动 ACK？——失败不能静默成功
- 为什么失败进死信？——毒消息不能无限重投
- 为什么 WS 失败只告警？——REST 是数据源
- 为什么限流异常放行？——保护手段不能阻断业务

### 7.4 记忆法

```text
审批：待审不占 → 通过复检 → 10→20
事件：事务内发布 → AFTER_COMMIT 发 MQ
消费：先查幂等 → 重试 → 成功写标记 → ACK
推送：落库后 AFTER_COMMIT → WS → 权威角标
```

### 7.5 60 秒面试话术

> 审批通过时待审批不占时段，所以必须复用房间锁 + 会议室行锁 + 会议行锁，
> 锁内复检冲突，再走状态机 10→20。领域事件在事务内发布，AFTER_COMMIT 才发 MQ，
> 避免回滚后下游收到不存在的会议。消费端手动 ACK，先查 Redis 幂等，
> 业务重试 3 次，成功后才写标记并 ack；失败 nack 进死信。
> 通知落库后再 AFTER_COMMIT 推 WebSocket，角标用权威未读数覆盖，推送失败不影响 REST。

### 7.6 明日预告

Day 5 进入会议纪要：COS 分片、ASR 状态机、语音文本联动、DeepSeek、Docker/Nginx/CI。

---

### 附：今天要能打开的文件

- `server/meeting-service/src/main/java/com/smt/meeting/service/ApprovalService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/mq/MeetingEventPublisher.java`
- `server/meeting-common/src/main/java/com/smt/common/mq/MeetingMqTopology.java`
- `server/notification-service/src/main/java/com/smt/notification/mq/MeetingEventConsumer.java`
- `server/notification-service/src/main/java/com/smt/notification/mq/ConsumeIdempotency.java`
- `server/notification-service/src/main/java/com/smt/notification/service/NotificationService.java`
- `server/notification-service/src/main/java/com/smt/notification/ws/NotificationWebSocketHandler.java`
- `server/notification-service/src/main/java/com/smt/notification/ws/NotificationPushListener.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/RoomService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/CalendarCacheEvictor.java`
- `server/meeting-service/src/main/java/com/smt/meeting/config/CacheConfig.java`
- `server/meeting-service/src/main/java/com/smt/meeting/aop/RateLimitAspect.java`
- `server/meeting-service/src/main/java/com/smt/meeting/config/RateLimitProperties.java`
- `web/src/pages/ApprovalCenter.tsx`
- `web/src/components/ApprovalDrawer.tsx`
- `web/src/pages/NotificationList.tsx`
- `web/src/hooks/useWebSocket.ts`
- `web/src/stores/notificationStore.ts`
- `web/src/components/notificationRouting.ts`
