# Day 3：并发/锁/幂等 + 详情状态

> 时间：6 小时
> 目标：把“并发预约”这条招牌链路讲透：锁粒度、锁与事务顺序、幂等、乐观锁、状态门槛。
> 今天的手敲是**跑并发脚本 + 用 curl 验证幂等 + 做一次破坏性实验**。

---

## 1. 今日目标

### 1.1 学完能做到

- [ ] 讲清并发控制的三个层次，知道什么时候用什么锁
- [ ] 讲清锁 key 为什么是“会议室 + 日期”，跨天怎么加锁
- [ ] 讲清“锁必须包住事务”，以及改期/取消/延长为什么也要加锁
- [ ] 讲清幂等 key 的 P/S 状态、失败删 key、成功保留 key
- [ ] 讲清乐观锁 `version` 和行锁的分工
- [ ] 能读懂详情页的编辑/取消/延长门槛

### 1.2 时间分配（6h）

| 时间段 | 内容 | 产出 |
|--------|------|------|
| 0:00–1:00 | 概念：三层并发、锁、事务、幂等 | 一张并发选型图 |
| 1:00–2:30 | 后端走读：锁工具、预约服务、幂等切面 | 能口述锁与事务顺序 |
| 2:30–3:30 | 前端走读：详情页、编辑抽屉、状态门槛 | 能口述权限判断 |
| 3:30–5:00 | 手敲：并发脚本、幂等 curl、破坏实验 | 10 轮通过 + 10006 |
| 5:00–6:00 | 自测 + 复盘 + 举一反三 | 8 道题 + 复盘卡 |

### 1.3 前置

- 服务已启动（compose 或本地）
- Redis 可用（锁、幂等、限流都依赖它）
- `scripts/concurrent-reserve.sh` 可执行

### 1.4 今天的“一张图”

```text
创建/改期/取消/延长
  → 房间锁（roomId + date，跨天逐日）
  → 事务：
      会议室行锁
      会议行锁（部分路径）
      冲突检测/状态机
      UPDATE ... WHERE version = ?
  → 提交事务
  → 释放锁
```

记住三句话：

- **并发分三层：JVM → 数据库 → 分布式锁**
- **锁的范围必须 ≥ 事务的范围**
- **幂等解决重复提交，乐观锁解决并发更新丢失**

---

## 2. 面试先问

先自己想 30 秒，答案在第 6 节。

1. 锁 key 为什么不是会议 ID？锁粒度怎么选？
2. 为什么不能 `@Transactional` 包住锁？会有什么空窗？
3. 改期为什么必须和创建共用同一把房间锁？
4. 幂等和锁的区别？幂等 key 什么时候删？
5. 幂等 key 为什么要 P/S 两种状态？只用一个标记会怎样？
6. 乐观锁和悲观锁怎么选？`version` 解决什么？
7. 取消/延长为什么要加锁？它们不是只改一行吗？
8. 锁获取失败、事务回滚、Redis 抖动分别怎么处理？

> 这 8 个问题覆盖“锁 + 事务 + 幂等 + 失败模式”，是并发场景的高频追问。

---

## 3. 10 分钟概念

### 3.1 并发控制的三个层次

```text
第 1 层：JVM 内
  问题：同一实例多个线程改同一变量
  手段：synchronized / ReentrantLock
  局限：多实例失效

第 2 层：单库多事务
  问题：脏读、不可重复读、幻读、更新丢失、超卖
  手段：事务 + 隔离级别 + MVCC + 行锁/乐观锁
  典型：@Transactional、select ... for update、version

第 3 层：多实例
  问题：两个 JVM 同时操作同一资源
  手段：Redis 分布式锁（Redisson）
  典型：集群防重复提交、定时任务防重复执行
```

选型原则：**能用低层就不用高层**。

- 单 JVM：`synchronized`
- 单库：事务 + 行锁/乐观锁
- 跨实例：Redisson

### 3.2 锁 key 与粒度

```text
lock:meeting:{roomId}:{yyyyMMdd}
```

为什么不是：

- **会议 ID**：太细，挡不住“新建 vs 改期”的跨行竞态（新会议还没 ID）
- **全局锁**：太粗，所有预约串行
- **精确时段**：端点计算复杂，跨天/相邻容易错

“会议室 + 日期”刚好对齐“某天某房间的占用”。

### 3.3 跨天与锁顺序

跨天会议逐日加锁：

```text
日期用 TreeSet 升序
  → lock:meeting:1:20261020
  → lock:meeting:1:20261021
```

为什么排序：

- 两个人分别订跨天会议，如果加锁顺序相反，会互相等待 → 死锁
- 固定升序，所有请求按同一顺序拿锁，不会成环

### 3.4 Redisson 锁的关键参数

```java
lock.tryLock(3, -1, TimeUnit.SECONDS)
```

- `waitTime=3s`：拿不到最多等 3 秒，期间别人可能完成并释放
- `leaseTime=-1`：启用看门狗，自动续期
- 看门狗：业务没结束，锁不会因为超时被抢
- `isHeldByCurrentThread()`：只释放自己持有的锁
- `finally` 释放：无论业务成功/异常都要释放

### 3.5 锁与事务的顺序

```text
加锁 → 开事务 → 查冲突 → 落库 → 提交事务 → 释放锁
```

为什么不能反过来：

```text
开事务 → 加锁 → 提交事务 → 释放锁
```

问题：如果锁在事务提交前释放，后一个请求拿到锁但读不到未提交数据，冲突检测失效。

为什么不能 `@Transactional` 包住锁：

- `@Transactional` 是 AOP 代理，方法进入时开事务
- 如果在事务方法内部加锁/释放锁，锁的范围可能小于事务
- 更常见的是：锁在事务外层，但用 `@Transactional` 包住整个方法，锁释放发生在事务提交之后？取决于写法，容易写错
- 项目用 `TransactionTemplate` 显式控制：锁外开、事务锁内提

### 3.6 幂等：解决“同一个请求重复提交”

场景：

- 用户手抖点两次
- 网络超时，客户端重试
- 网关重试

幂等 key：

```text
X-Idempotency-Token: <UUID>
idem:{biz}:{userId}:{token}
```

状态机：

```text
SETNX key = P（处理中）
  ├─ 业务成功 → 改成 S（已完成），留到 TTL
  └─ 业务失败 → 删除 key（允许改参重试）

重复请求：
  看到 S → 10006（真重复）
  看到 P → 最多等 2 秒
      ├─ 首个成功 → 10006
      ├─ 首个失败删 key → 自己接手
      └─ 等不到 → 10005（忙）
```

为什么不是一个简单标记：

- 只标记“已处理”：首个请求失败时，重试会被误判为重复 → 用户以为提交成功，实际没订上
- P/S 双状态：区分“处理中”和“已完成”，失败可重试

### 3.7 乐观锁 vs 悲观锁

| 维度 | 悲观锁 | 乐观锁 |
|------|--------|--------|
| 思路 | 先锁后改 | 提交时检查版本 |
| 实现 | `select ... for update` | `version` / 条件更新 |
| 优点 | 不会冲突、不用重试 | 不加锁、吞吐高 |
| 缺点 | 锁等待、死锁风险 | 冲突时重试 |
| 场景 | 冲突多、强一致 | 读多写少、冲突少 |

项目里两种都用：

- 审批/改期：行锁（当前读）
- 普通改期字段：`version` 乐观锁
- 状态变更：CAS（`WHERE status = 前态`）

### 3.8 锁序：先会议室行 → 后会议行

审批通过要同时锁两把：

```text
会议室行锁（selectIdForUpdate）
会议行锁（selectByIdForUpdate）
```

顺序固定。为什么：

- 改期/取消也按同一顺序
- 顺序相反会交叉持锁 → 死锁

### 3.9 失败模式

| 失败 | 处理 |
|------|------|
| 抢锁超时 | 10007（系统繁忙） |
| 业务异常 | 事务回滚，finally 释放锁 |
| Redis 抖动 | 锁获取失败 → 10007；幂等抛异常 → 不 ack，消息重投 |
| 锁释放失败 | 记日志，等租约/看门狗兜底 |
| 事务提交失败 | 回滚，锁释放，客户端可重试 |

### 3.10 记忆法：三层、两锁、一顺序

```text
三层：JVM → 数据库 → 分布式
两锁：悲观锁（for update）+ 乐观锁（version）
一顺序：先会议室行 → 后会议行
幂等：P/S + 成功保留 + 失败删除
```

---

## 4. 代码走读

### 4.1 后端：锁工具

#### 4.1.1 MeetingLockHelper

文件：`server/meeting-service/src/main/java/com/smt/meeting/lock/MeetingLockHelper.java`

```java
public <T> T executeWithLock(Long roomId, LocalDateTime start, LocalDateTime end,
                             Supplier<T> action) {
    List<RLock> locks = new ArrayList<>();
    try {
        acquireInto(locks, roomId, start, end);
        return action.get();
    } finally {
        release(locks); // acquire 中途失败/action 异常都释放已持有的锁
    }
}
```

逐日取锁：

```java
private void acquireInto(List<RLock> locks, Long roomId, LocalDateTime start, LocalDateTime end) {
    SortedSet<LocalDate> dates = new TreeSet<>();
    for (LocalDate d = start.toLocalDate(); !d.isAfter(end.toLocalDate()); d = d.plusDays(1)) {
        dates.add(d);
    }
    acquireInto(locks, roomId, dates);
}
```

逐日 `tryLock`：

```java
for (LocalDate date : dates) {
    RLock lock = redisson.getLock("lock:meeting:" + roomId + ":" + date);
    try {
        if (!lock.tryLock(3, -1, TimeUnit.SECONDS)) {
            throw new BusinessException(ErrorCode.SYSTEM_BUSY);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BusinessException(ErrorCode.SYSTEM_BUSY);
    }
    locks.add(lock);
}
```

细节：

- `TreeSet` 升序：固定加锁顺序，防死锁
- `tryLock(3,-1)`：最多等 3 秒，看门狗续期
- `finally` 释放：acquire 中途失败也要释放已拿到的锁
- `isHeldByCurrentThread`：只释放自己的锁
- 周期会议用 `executeWithLockOnDates`：只锁真的要写的那几天，避免锁太多

#### 4.1.2 为什么用 Redisson 而不是原生 SETNX

原生 SETNX 要自己处理：

- 过期时间
- 误删（value 校验 + Lua）
- 可重入
- 阻塞等待
- 看门狗续期

Redisson 已经做稳了。面试重点不是“手写 SETNX”，而是：

- 锁粒度
- 锁与事务顺序
- 失败模式
- 看门狗/续期

### 4.2 后端：创建/改期/取消/延长

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/MeetingReserveService.java`

#### 4.2.1 创建

```java
public MeetingCreateVO create(MeetingCreateDTO dto, Long organiserId) {
    MeetingRoom room = loadAvailableRoom(dto.getRoomId());
    RoomStrategy strategy = roomStrategyMapper.selectByRoomId(room.getId());
    validateTime(dto.getStartTime(), dto.getEndTime(), strategy);

    boolean needApproval = isNeedApproval(strategy);
    int status = stateMachine.initialStatus(needApproval);

    return lockHelper.executeWithLock(room.getId(), dto.getStartTime(), dto.getEndTime(), () ->
        transactionTemplate.execute(tx -> {
            meetingRoomMapper.selectIdForUpdate(room.getId());
            ensureRoomNotMaintenanceAfterLock(room.getId());
            checkConflict(room.getId(), dto.getStartTime(), dto.getEndTime(), null);

            Meeting meeting = insertMeeting(dto, room.getId(), organiserId, status);
            saveParticipants(meeting.getId(), dto.getParticipants());
            stateMachine.logCreation(meeting.getId(), status, organiserId, null);
            eventPublisher.publishEvent(MeetingDomainEvent.created(...));
            return new MeetingCreateVO(...);
        }));
}
```

#### 4.2.2 改期

改期分两种：

1. **只改 title/remark**：不涉及时段，不抢锁，直接事务更新
2. **改时间/会议室**：走锁 + 事务 + 冲突复检 + 乐观锁

```java
if (!scheduleChanged) {
    transactionTemplate.executeWithoutResult(tx -> applyUpdate(...));
    return;
}

lockHelper.executeWithLock(room.getId(), dto.getStartTime(), dto.getEndTime(), () ->
    transactionTemplate.execute(tx -> {
        meetingRoomMapper.selectIdForUpdate(room.getId());
        ensureRoomNotMaintenanceAfterLock(room.getId());
        checkConflict(room.getId(), dto.getStartTime(), dto.getEndTime(), meetingId);
        applyUpdate(meetingId, ..., backToApproval);
        return null;
    }));

calendarCacheEvictor.evict(oldRoomId, oldStart, oldEnd);
```

为什么改期也要锁：

- 改期在改变“某时段是否被占用”
- 乐观锁只保护“同一行”，管不住“改到哪”与“新建”的跨行竞态
- 不抢锁：改期和新建各自读到旧快照，各自通过检测 → 两场重叠

#### 4.2.3 乐观锁更新

`applyUpdate`：

```java
int affected = meetingMapper.updateWithVersion(
    meetingId, title, roomId, start, end, remark, version, RESERVED);
if (affected == 0) {
    throw new BusinessException(MEETING_STATUS_NOT_ALLOWED,
        "会议状态已变化（可能已被取消或修改），请刷新后重试");
}
```

SQL：

```sql
UPDATE meeting
SET title=..., room_id=..., start_time=..., end_time=..., remark=...,
    version = version + 1
WHERE id = #{id} AND version = #{version} AND status = #{expectedStatus}
```

为什么同时校验 `status`：

- 改期与取消是两条写路径
- 取消只改 status，不动 version
- 只校 version 拦不住“改期 vs 取消”的交叉写

#### 4.2.4 取消

```java
lockHelper.executeWithLock(meeting.getRoomId(), meeting.getStartTime(), meeting.getEndTime(), () ->
    transactionTemplate.execute(tx -> {
        meetingRoomMapper.selectIdForUpdate(meeting.getRoomId());
        stateMachine.transition(meetingId, CANCEL, operatorId, reason);
        meetingMapper.updateCancelInfo(meetingId, reason);
        eventPublisher.publishEvent(MeetingDomainEvent.cancelled(...));
        return null;
    }));
```

为什么取消也要锁：

- 取消在释放时段
- 不锁：取消提交与并发冲突检测之间有空窗，后来者可能读到旧快照，报假冲突
- 锁序与 create/update 一致

#### 4.2.5 延长

```java
lockHelper.executeWithLock(roomId, start, lockEndBound, () ->
    transactionTemplate.execute(tx -> {
        Meeting locked = meetingMapper.selectByIdForUpdate(meetingId);
        requireOngoing(locked);
        requireNotEnded(locked);
        ensureRoomNotMaintenanceAfterLock(locked.getRoomId());
        long limitInside = computeExtendLimit(locked).maxDelayMinutes();
        LocalDateTime newEnd = locked.getEndTime().plusMinutes(minutes);
        List<MeetingConflictVO> conflicts = conflictChecker.findConflicts(
            locked.getRoomId(), locked.getStartTime(), newEnd, meetingId);
        int affected = meetingMapper.updateEndTime(meetingId, newEnd, locked.getVersion(), ONGOING);
        ...
    }));
```

关键：

- 延长扩大时段占用，必须锁
- 上限和写入目标都在锁内重算
- 以锁内读到的 `locked.endTime` 为基准，避免并发延长“时间倒退”
- `updateEndTime` 带 `version + status=30`

### 4.3 后端：幂等切面

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/aop/Idempotent.java`
- `server/meeting-service/src/main/java/com/smt/meeting/aop/IdempotentAspect.java`

注解：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String value();
    int ttlMinutes() default 10;
}
```

切面：

```java
@Around("@annotation(idempotent)")
public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
    String token = resolveToken();
    if (token == null) {
        log.warn("未带 X-Idempotency-Token，跳过幂等");
        return pjp.proceed();
    }

    String key = "idem:" + idempotent.value() + ":" + UserContext.getUserId() + ":" + token;
    Duration ttl = Duration.ofMinutes(idempotent.ttlMinutes());

    if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "P", ttl))) {
        return executeAndMark(pjp, key, ttl);
    }
    return handleDuplicate(pjp, key, ttl);
}
```

成功/失败：

```java
private Object executeAndMark(...) throws Throwable {
    try {
        Object result = pjp.proceed();
        redis.opsForValue().set(key, "S", ttl); // 成功保留
        return result;
    } catch (Throwable e) {
        redis.delete(key); // 失败删除，允许重试
        throw e;
    }
}
```

重复处理：

```java
for (int i = 0; i < 20; i++) {
    String state = redis.opsForValue().get(key);
    if ("S".equals(state)) throw new BusinessException(DUPLICATE_SUBMIT);
    if (state == null) {
        if (setIfAbsent(key, "P", ttl)) return executeAndMark(...);
        continue;
    }
    Thread.sleep(100);
}
throw new BusinessException(SYSTEM_BUSY);
```

为什么等 2 秒而不是立刻 10006：

- 首个请求可能还在处理
- 立刻 10006 会谎报“已提交”，但首个请求可能失败
- 等 2 秒：首个成功 → 10006；首个失败 → 自己接手；仍处理中 → 10005

切面顺序：

```text
审计(+10) → 幂等(+20) → 限流(+30) → 事务
```

重复提交在消耗限流额度之前被挡下。

### 4.4 后端：Mapper 锁与测试

文件：`server/meeting-service/src/main/resources/mapper/MeetingMapper.xml`

- `selectByIdForUpdate`：`SELECT ... FOR UPDATE`
- `updateStatus`：`WHERE id=? AND status=?`（状态机 CAS）
- `updateWithVersion`：`WHERE id=? AND version=? AND status=20`
- `updateEndTime`：`WHERE id=? AND version=? AND status=30`

测试/脚本：

- `server/meeting-service/src/test/java/com/smt/meeting/service/MeetingReserveLockTest.java`
- `scripts/concurrent-reserve.sh`
- `scripts/concurrent-modify.sh`

### 4.5 前端：详情页与状态门槛

文件：
- `web/src/pages/MeetingDetail.tsx`
- `web/src/components/MeetingEditDrawer.tsx`
- `web/src/components/CancelMeetingModal.tsx`
- `web/src/components/MeetingStatusTag.tsx`

`MeetingDetail`：

```tsx
const canCancel = (isOrganiser || isAdmin) && CANCELLABLE_STATUS.includes(detail.status)
const canEdit = isOrganiser && detail.status === 20
const canOperate = isOrganiser && detail.status === 30
```

- 编辑：仅组织人 + 已预约（20）
- 取消：组织人或 ADMIN + 可取消状态（10/20）
- 延长/提前结束：组织人 + 进行中（30）

`MeetingEditDrawer`：

- 打开时才 `loadRooms()`，避免详情页挂载就请求 `/rooms`
- 编辑提交走 `useAppRequest(updateMeeting)`
- 改期后详情刷新，状态可能从 20 回到 10

`CancelMeetingModal`：

- 取消原因必填（管理员代取消尤其）
- 成功后关闭 + 刷新

`MeetingStatusTag`：

- 状态码 → 中文/颜色
- 纯展示组件

### 4.6 边界与异常

| 场景 | 行为 | 说明 |
|------|------|------|
| 抢锁 3 秒失败 | 10007 | 系统繁忙 |
| 事务抛异常 | 回滚 + finally 释放锁 | 不泄漏锁 |
| acquire 中途失败 | 释放已拿到的锁 | 防永久锁死 |
| 锁释放失败 | 日志 + 看门狗兜底 | 不覆盖业务异常 |
| 幂等未带 token | 放行 + 告警 | 兼容旧联调 |
| 幂等首个失败 | 删 key，允许重试 | 不谎报成功 |
| 幂等首个处理中 | 等待 2 秒 → 10005 | 不谎报 10006 |
| 改期只改标题 | 不抢锁 | 时段没变 |
| 改期改时间 | 抢锁 + 乐观锁 | 跨行竞态 |
| 并发延长 | 锁内重算 end | 防时间倒退 |
| 取消 vs 改期 | 同锁序 | 防死锁 |

### 4.7 📘 笔记引用

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§五.1 并发控制的三个层次
> 类型：A（项目直接对应）
> 笔记结论：JVM 锁 → 数据库锁 → 分布式锁，能用低层不用高层。
> 项目落点：`MeetingLockHelper` 用 Redisson 处理跨实例；`selectByIdForUpdate`/version 处理单库并发。
> 差异/提醒：项目没有用 `synchronized` 解决跨实例；面试先讲“并发发生在哪一层”。

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§五.5 锁 / §五.6 乐观锁 vs 悲观锁
> 类型：A（项目直接对应）
> 笔记结论：悲观锁先锁后改，乐观锁提交时校验版本；行锁加在索引上，没走索引会锁全表。
> 项目落点：`selectByIdForUpdate`（悲观）、`updateWithVersion`/`updateEndTime`（乐观）。
> 差异/提醒：项目两种都用：审批/改期用行锁，普通改期字段用 version；不是二选一。

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§四.4 分布式锁 / §四.5 Redisson
> 类型：A（项目直接对应）
> 笔记结论：SETNX 要处理过期/误删/续期；Redisson 提供看门狗和可重入。
> 项目落点：`MeetingLockHelper` 的 `tryLock(3s, -1)`、`isHeldByCurrentThread` 释放。
> 差异/提醒：笔记里的原生 Redis 示例是原理，项目直接用 Redisson。

> 📘 **笔记引用**：《11.Spring Boot》§六.6 事务 @Transactional
> 类型：A（项目直接对应）
> 笔记结论：`@Transactional` 是 AOP 代理；同类自调用、非 public、受检异常都会让事务失效。
> 项目落点：`MeetingReserveService` 用 `TransactionTemplate`，因为锁必须包住事务。
> 差异/提醒：项目不是所有地方都用注解事务；锁 + 事务的顺序决定了必须用编程式事务。

> 📘 **笔记引用**：《11.Spring Boot》§四.4 AOP 切面编程
> 类型：A（项目直接对应）
> 笔记结论：AOP 适合日志、事务、缓存、幂等这类横切逻辑。
> 项目落点：`IdempotentAspect`、`RateLimitAspect`、`LogAspect`。
> 差异/提醒：缓存用 Spring Cache（也是代理），权限用 Spring Security，异常用 `@RestControllerAdvice`，不要都说成“自定义 AOP”。

> 📘 **笔记引用**：《2.React知识点》§四.4 闭包陷阱 / §四.5 useEffect 异步坑点
> 类型：A（项目直接对应）
> 笔记结论：effect 捕获的是那次渲染的 state；异步请求要处理竞态/卸载。
> 项目落点：`MeetingDetail` 的详情请求、`useWebSocket` 的清理。
> 差异/提醒：项目用 ahooks `useRequest` 管请求生命周期，闭包陷阱更多出现在自定义 hook 里。

> 📘 **笔记引用**：《10.Java基础》§十一 Java 并发+JVM
> 类型：C（纯基础补充）
> 笔记结论：线程安全要关注可见性、原子性、有序性；线程池复用要清理 ThreadLocal。
> 项目落点：`UserContext` 的 `finally clear`、定时任务线程池。
> 差异/提醒：这部分是基础题，不需要和项目每个类一一对应。

### 4.8 举一反三

#### 4.8.1 分布式锁还能用在哪

- 库存扣减：`lock:stock:{skuId}`
- 支付回调：`lock:pay:{orderId}`
- 定时任务防重复：`lock:job:{jobName}:{date}`
- 优惠券领取：`lock:coupon:{couponId}:{userId}`

套路：

```text
锁 key 与资源粒度对齐
固定加锁顺序
锁包住事务/临界区
失败可重试/可降级
```

#### 4.8.2 幂等还能用在哪

- 支付/退款
- 订单创建
- 库存扣减
- 消息消费
- 第三方回调

套路：

```text
业务唯一键 + 状态（P/S）+ TTL
成功保留、失败删除
重复请求返回上次结果/明确错误
```

#### 4.8.3 锁粒度选择

| 粒度 | 示例 | 优点 | 缺点 |
|------|------|------|------|
| 全局 | `lock:all` | 最简单 | 吞吐最差 |
| 业务域 | `lock:meeting` | 较简单 | 不同会议室互相阻塞 |
| 资源+日期 | `lock:meeting:{roomId}:{date}` | 平衡 | 跨天要逐日 |
| 精确时段 | `lock:meeting:{roomId}:{start}:{end}` | 最细 | 端点计算复杂、易错 |

#### 4.8.4 反例

- 锁 key 用会议 ID → 挡不住新建 vs 改期
- `@Transactional` 包住锁 → 锁释放早于提交
- 改期不加锁 → 双预订
- 幂等只用一个标记 → 首个失败后重试被误判重复
- 乐观锁只校 version 不校 status → 改期 vs 取消交叉写
- 锁不 `finally` 释放 → 永久锁死

---

## 5. 手敲练习

### 5.1 跑并发脚本

```bash
bash scripts/concurrent-reserve.sh 10 --repeat 10
```

验收：

- 每轮“成功 1 / 冲突 N”
- 10 轮全过
- 失败时看日志里的锁 key、冲突 SQL

### 5.2 幂等 curl

```bash
IDEM=$(uuidgen)

# 第一次
curl -s -X POST http://localhost:8080/api/meetings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Token: $IDEM" \
  -d '{...按 docs/04 的创建预约请求体...}'

# 第二次，同一个 token
curl -s -X POST http://localhost:8080/api/meetings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Token: $IDEM" \
  -d '{...同一请求体...}'
```

期望：

- 第一次 code=0
- 第二次 code=10006（或首个仍在处理时 10005）
- 数据库只有一场会议

### 5.3 破坏性实验（学习分支）

1. 把改期的 `lockHelper.executeWithLock` 注释掉 → 跑 `concurrent-modify.sh`，观察双预订
2. 把 `updateWithVersion` 的 `AND status = 20` 去掉 → 并发改期/取消交叉写
3. 把幂等失败时的 `redis.delete(key)` 去掉 → 首个失败后重试返回 10006

> 只用于理解，不要提交 main。

### 5.4 变体：给“延长”加最长时长

需求：最多延长到 20:00。

- `computeExtendLimit` 已经算 `min(下一场 - buffer, 20:00)`
- 测试：19:30 + 60 分钟 → 只允许 30 分钟
- 并发：两个延长请求同时提交，锁内重算，后一个以新 end 为基准

### 5.5 变体：幂等 token 过期

- 把 TTL 改成 1 分钟
- 1 分钟后用同一 token 再提交
- 期望：视为新请求（key 已过期）

---

## 6. 八道自测

### 题目

1. 锁 key 为什么是“房间 + 日期”，不是会议 ID？
2. 为什么不能 `@Transactional` 包住锁？
3. 改期为什么必须和创建共用同一把房间锁？
4. 幂等和锁的区别？幂等 key 什么时候删？
5. 幂等 key 为什么有 P/S 两种状态？
6. 乐观锁 `version` 和行锁分别解决什么？
7. 取消/延长为什么要加锁？
8. 抢锁失败、事务回滚、Redis 抖动分别怎么处理？

### 答案

1. 会议 ID 太细，挡不住“新建 vs 改期”的跨行竞态；整会议室太粗，不同日互相阻塞。房间+日期与“某天某房间的占用”对齐。
2. `@Transactional` 包住锁时，锁可能在事务提交前释放，后一个请求拿到锁读不到未提交数据，冲突检测失效。
3. 改期在改变“某时段是否被占用”，与创建是同一类写路径；不共用锁会各自读到旧快照，落成两场重叠会议。
4. 幂等解决“同一个请求重复提交”；锁解决“不同请求并发抢同一资源”。失败删 key，成功保留到 TTL。
5. P 表示首个请求还在处理，重复请求不能谎报成功；S 表示已完成，可以安全返回 10006。失败删 key 允许改参重试。
6. `version` 解决同一行并发更新丢失；行锁解决“读-判-写”期间别人插入/修改（当前读）。两者配合。
7. 取消在释放时段，延长在扩大时段占用，都属于“改变某时段是否被占用”；不锁会出现假冲突/重叠。
8. 抢锁失败 → 10007；事务异常 → 回滚 + finally 释放锁；Redis 抖动 → 锁获取失败/幂等抛异常，不 ack，消息重投（幂等是正确性前提，不降级放行）。

### 追问

- 如果锁的 leaseTime 不是 -1，业务执行超过租期会怎样？
- 如果 Redis 主从切换，Redisson 锁会丢吗？怎么缓解？
- 如果幂等 key 被误删，会有什么后果？
- 如果要把锁改成数据库悲观锁，性能和死锁风险怎么变？

---

## 7. 复盘卡

### 7.1 三句话

```text
1. 锁 key 与“房间 + 日期”对齐，跨天按日期升序逐日加锁。
2. 锁必须包住事务；改期、取消、延长都走同一把房间锁。
3. 幂等 P/S + 乐观锁 version，分别解决重复提交和并发更新丢失。
```

### 7.2 归纳：并发防护一张表

| 问题 | 手段 | 项目落点 |
|------|------|----------|
| 重复提交 | 幂等 P/S + SETNX | IdempotentAspect |
| 跨实例抢资源 | Redisson 锁 | MeetingLockHelper |
| 单库并发更新 | 行锁 / version | Mapper |
| 状态并发变更 | CAS | MeetingStateMachine |
| 锁死锁 | 固定锁序 | 会议室行 → 会议行 |
| 锁泄漏 | finally + isHeldByCurrentThread | MeetingLockHelper |

### 7.3 演绎：从原则推导新场景

原则：**锁的范围 ≥ 事务的范围；锁 key 与资源粒度对齐；固定顺序防死锁**。

由此推导：

- 为什么锁要包住事务？——否则提交前有空窗
- 为什么跨天要排序？——否则交叉持锁死锁
- 为什么取消也要锁？——取消改变占用
- 为什么改期只改标题不锁？——没有改变占用
- 为什么幂等失败删 key？——失败不是“已提交”

### 7.4 记忆法

```text
三层：JVM → 数据库 → 分布式
两锁：悲观锁（for update）+ 乐观锁（version）
一顺序：先会议室行 → 后会议行
幂等：P/S + 成功保留 + 失败删除
```

### 7.5 60 秒面试话术

> 预约并发用三层防护：幂等挡重复提交，Redisson 房间+日期锁挡跨实例并发，
> 锁内用会议室行锁 + 冲突复检 + 状态机 CAS 保证一致性。
> 锁必须包住事务，用 TransactionTemplate 而不是 @Transactional 包锁。
> 改期、取消、延长都走同一把房间锁，因为它们在改变时段占用。
> 幂等用 P/S 双状态：成功保留、失败删除，避免失败后重试被误判重复。

### 7.6 明日预告

Day 4 进入审批、MQ、通知和 WebSocket：AFTER_COMMIT、消费幂等、手动 ACK、死信。

---

### 附：今天要能打开的文件

- `server/meeting-service/src/main/java/com/smt/meeting/lock/MeetingLockHelper.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MeetingReserveService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/aop/Idempotent.java`
- `server/meeting-service/src/main/java/com/smt/meeting/aop/IdempotentAspect.java`
- `server/meeting-service/src/main/resources/mapper/MeetingMapper.xml`
- `server/meeting-service/src/test/java/com/smt/meeting/service/MeetingReserveLockTest.java`
- `scripts/concurrent-reserve.sh`
- `scripts/concurrent-modify.sh`
- `web/src/pages/MeetingDetail.tsx`
- `web/src/components/MeetingEditDrawer.tsx`
- `web/src/components/CancelMeetingModal.tsx`
- `web/src/components/MeetingStatusTag.tsx`
