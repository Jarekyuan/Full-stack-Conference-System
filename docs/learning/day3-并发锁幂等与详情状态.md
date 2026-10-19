# Day 3：并发/锁/幂等 + 详情状态

> 时间：6 小时
> 目标：把“并发预约”这条招牌链路讲透：锁粒度、锁与事务顺序、幂等、乐观锁、状态门槛。
> 今天的手敲是**跑并发脚本 + 写一个最小幂等测试**。

---

## 1. 今日目标

- [ ] 讲清锁 key 为什么是“会议室 + 日期”，跨天怎么加锁
- [ ] 讲清“锁必须包住事务”，以及改期为什么也要加锁
- [ ] 讲清幂等 key 的 P/S 状态和乐观锁 `version`
- [ ] 能读懂详情页的编辑/取消/延长门槛

---

## 2. 面试先问

1. 锁 key 为什么不是会议 ID？锁粒度怎么选？
2. 为什么不能 `@Transactional` 包住锁？
3. 改期为什么必须和创建共用同一把房间锁？
4. 幂等和锁的区别？幂等 key 什么时候删？
5. 乐观锁和悲观锁怎么选？`version` 解决什么？

---

## 3. 10 分钟概念

### 3.1 并发控制的三个层次

```text
第 1 层：JVM 内          synchronized / ReentrantLock
第 2 层：单库多事务       @Transactional / 行锁 / 乐观锁
第 3 层：多实例           Redisson 分布式锁
```

选型原则：**能用低层就不用高层**。跨实例才上 Redis 锁；单库并发优先事务 + 行锁/版本。

### 3.2 锁 key 与跨天

```text
lock:meeting:{roomId}:{yyyyMMdd}
```

- 粒度“会议室 + 日期”：比整会议室细（不同日不阻塞），比精确时段简单可靠
- 跨天会议逐日加锁，日期用 `TreeSet` 升序，多把锁固定顺序获取，避免死锁
- `tryLock(waitTime=3s, leaseTime=-1)`：拿不到最多等 3 秒；`-1` 启用 Redisson 看门狗自动续期
- `finally` 里只释放自己持有的锁

### 3.3 锁与事务的顺序

```text
加锁 → 开事务 → 查冲突 → 落库 → 提交事务 → 释放锁
```

锁的范围必须 ≥ 事务的范围。如果先提交事务再释放锁，中间没有空窗；如果锁释放后才提交，后一个请求拿到锁也读不到未提交数据，冲突检测失效。

### 3.4 幂等

同一个请求（同一个 `X-Idempotency-Token`）在窗口内只处理一次：

```text
SETNX idem:{biz}:{userId}:{token} = P   // 处理中
成功 → 改成 S（留到 TTL 到期）
失败 → 删除 key（允许改参重试）
重复请求：
  看到 S → 10006
  看到 P → 最多等 2 秒；首个失败删 key 则自己接手
  等不到 → 10005（忙），不谎报 10006
```

### 3.5 乐观锁

```sql
UPDATE meeting
SET title=..., version = version + 1
WHERE id = ? AND version = ? AND status = 20
```

影响 0 行 = 版本或状态被并发改过，按 40002 提示刷新重试。乐观锁只保护“同一行”，管不住“改到哪”的跨行竞态，所以改期仍要房间锁。

---

## 4. 代码走读

### 4.1 后端：锁、事务、幂等

#### 4.1.1 锁工具

文件：`server/meeting-service/src/main/java/com/smt/meeting/lock/MeetingLockHelper.java`

关键方法：

- `executeWithLock(roomId, start, end, action)`：覆盖 `[start 当天 .. end 当天]`
- `executeWithLockOnDates(roomId, dates, action)`：周期会议只锁“真的要写的那几天”
- `acquireInto`：`TreeSet` 升序，逐日 `tryLock(3s, -1)`；中途失败由 `finally` 释放已拿到的锁
- `release`：逐把 `isHeldByCurrentThread` 判断后释放，一把失败不影响其他锁

面试一句话：

> 锁 key 是“房间 + 日期”，跨天按日期升序逐日加锁；`leaseTime=-1` 用看门狗续期，锁的范围必须包住事务。

#### 4.1.2 创建/改期/取消

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/MeetingReserveService.java`

创建 `create`：

```text
锁外快检：房间存在/可用、时间合法、算初始状态
锁内事务：
  meetingRoomMapper.selectIdForUpdate(roomId)   // 数据库兜底
  ensureRoomNotMaintenanceAfterLock             // 锁外快检可能过期
  checkConflict(roomId, start, end, null)        // 锁内检测
  insert + participants + logCreation
  publishEvent(created)
```

改期 `update`：

- 只改 `title/remark`：不涉及时段，**不抢锁**
- 改了时间/会议室：
  - `executeWithLock(newRoomId, newStart, newEnd)`
  - 会议室行锁 + 维护复查 + `checkConflict(..., meetingId)` 排除自身
  - `updateWithVersion(..., version, status=20)` 乐观锁
  - 目标房间需审批 → 状态机 `MODIFY` 回 10
  - 事务提交后清旧房间/旧周日历缓存

取消 `cancel`：

- 归属校验（组织人/ADMIN）→ 取消截止 R-6
- **也取房间锁 + 会议室行锁**：取消在释放时段，同样属于“改变某时段是否被占用”
- 状态机 `CANCEL`（10/20 → 50），写取消原因，发事件

延长 `extend`：

- 锁内重算上限和写入目标；以锁内读到的 `endTime` 为基准，避免并发延长“时间倒退”
- `updateEndTime(..., version, status=30)` 影响 0 行 → 40002

#### 4.1.3 幂等切面

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/aop/Idempotent.java`
- `server/meeting-service/src/main/java/com/smt/meeting/aop/IdempotentAspect.java`

关键点：

- 读请求头 `X-Idempotency-Token`；没带则告警放行（前端按约定必带）
- key = `idem:{value}:{userId}:{token}`，TTL 默认 10 分钟
- `SETNX` 成功 → `P`（处理中）→ 业务成功改 `S`；失败删 key
- 重复请求看到 `S` → 10006；看到 `P` → 轮询等待（20 × 100ms = 2s）
- 首个请求失败删 key → 等待者抢过来执行，保住“失败可重试”
- 等不到 → 10005，不谎报 10006
- `@Order(HIGHEST_PRECEDENCE + 20)`：审计(+10) → 幂等(+20) → 限流(+30)，重复提交不消耗限流额度

#### 4.1.4 Mapper 的锁

文件：`server/meeting-service/src/main/resources/mapper/MeetingMapper.xml`

- `selectByIdForUpdate`：`SELECT ... FOR UPDATE`，审批/改期/取消时锁会议行
- `updateStatus`：`WHERE id=? AND status=?`，状态机 CAS
- `updateWithVersion`：`WHERE id=? AND version=? AND status=20`
- `updateEndTime`：`WHERE id=? AND version=? AND status=30`
- 锁序：**先会议室行 → 后会议行**（审批同时锁两把，顺序反了会死锁）

#### 4.1.5 测试与脚本

- `server/meeting-service/src/test/java/com/smt/meeting/service/MeetingReserveLockTest.java`
- `scripts/concurrent-reserve.sh`
- `scripts/concurrent-modify.sh`

脚本会自动登录、挑空闲时段、并发提交、断言“恰好 1 个成功”，可重复跑。

### 4.2 前端：详情页与状态门槛

文件：
- `web/src/pages/MeetingDetail.tsx`
- `web/src/components/MeetingEditDrawer.tsx`
- `web/src/components/CancelMeetingModal.tsx`
- `web/src/components/MeetingStatusTag.tsx`

`MeetingDetail`：

- `useAppRequest(() => getMeeting(meetingId))` 拉详情
- `canEdit = isOrganiser && status === 20`
- `canCancel = (isOrganiser || isAdmin) && CANCELLABLE_STATUS.includes(status)`
- `canOperate = isOrganiser && status === 30`（延长/提前结束）
- 操作成功后 `refresh()`，状态时间线/按钮随之变化

`MeetingEditDrawer`：

- 打开时才 `loadRooms()`，避免详情页挂载就请求 `/rooms`
- 只读展示 + 编辑表单；提交走 `useAppRequest(updateMeeting)`
- 改期后详情刷新，状态可能从 20 回到 10

`CancelMeetingModal`：

- 取消原因必填（管理员代取消尤其）
- 成功后关闭弹窗 + 刷新详情

`MeetingStatusTag`：

- 状态码 → 中文标签/颜色
- 纯展示组件，不掺业务判断

### 4.3 📘 笔记引用

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

---

## 5. 手敲练习

### 5.1 跑并发脚本

确保服务已启动（compose 或本地），执行：

```bash
bash scripts/concurrent-reserve.sh 10 --repeat 10
```

验收：

- 每轮输出“成功 1 / 冲突 N”
- 10 轮全部通过
- 如果失败，先看服务日志里的锁 key 和冲突 SQL

### 5.2 手写幂等测试（二选一）

**方式 A：curl 重放**

1. 生成一个 token：

   ```bash
   IDEM=$(uuidgen)
   ```

2. 用同一个 `X-Idempotency-Token` 连续提交两次创建预约（请求体按 `docs/04` 的 `POST /api/meetings` 填）。
3. 验收：第一次 code=0；第二次 code=10006（或首个请求还在处理时 10005）。

**方式 B：写一个最小的状态机测试**

在 `web/tests` 或后端测试里模拟 P/S：

```java
@Test
void duplicateAfterSuccessIsRejected() {
    // 伪代码：P → S 后，重复请求应返回 10006
    assertEquals(10006, duplicateResult(stateS()));
}
```

验收：能解释“为什么失败要删 key、成功要保留 key”。

### 5.3 观察锁与事务的顺序

在 `MeetingReserveService.create` 里临时加日志（学习分支）：

```java
log.debug("before lock");
// executeWithLock ...
log.debug("after lock");
```

确认日志顺序是：`before lock → 锁内 SQL → 事务提交 → after lock`。

---

## 6. 五道自测

### 题目

1. 锁 key 为什么是“房间 + 日期”，不是会议 ID？
2. 为什么不能 `@Transactional` 包住锁？会有什么空窗？
3. 改期为什么必须和创建共用同一把房间锁？
4. 幂等 key 为什么有 P/S 两种状态？失败为什么要删 key？
5. 乐观锁 `version` 和行锁分别解决什么问题？

### 答案

1. 会议 ID 太细，挡不住“新建 vs 改期”的跨行竞态；整会议室太粗，不同日会互相阻塞。房间 + 日期与“某天某房间的占用”对齐。
2. `@Transactional` 包住锁时，锁在事务提交前释放，后一个请求拿到锁读不到未提交数据，冲突检测失效。必须锁外开、事务锁内提。
3. 改期在改变“某时段是否被占用”，与创建是同一类写路径；不共用锁会出现两边各自读到旧快照、各自通过检测，落成两场重叠会议。
4. P 表示首个请求还在处理，重复请求不能谎报成功；S 表示已完成，可以安全返回 10006。失败删 key 是为了允许用户改参数后重试。
5. `version` 解决同一行并发更新丢失；行锁解决“读-判-写”期间别人插入/修改（当前读），两者配合使用。

---

## 7. 复盘卡

```text
今天最重要的 3 句话：
1. 锁 key 与“房间 + 日期”对齐，跨天按日期升序逐日加锁。
2. 锁必须包住事务；改期、取消、延长都走同一把房间锁。
3. 幂等 P/S + 乐观锁 version，分别解决重复提交和并发更新丢失。

可以讲给面试官的 1 个点：
“10 轮里 7 轮双重预订”的修复：创建加了锁，但改期没走同一把锁。
```
