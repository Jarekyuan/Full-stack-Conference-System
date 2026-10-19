# Day 2：预约核心 + 日历/预约表单

> 时间：6 小时
> 目标：把“预约”这条最核心的业务链路讲清楚：状态机、半开区间、冲突检测、索引、日历布局、表单校验。
> 今天的手敲是**写一个半开区间冲突函数 + 单测 + 看 EXPLAIN**，这是面试最容易被追问的代码。

---

## 1. 今日目标

### 1.1 学完能做到

- [ ] 讲清 6 个会议状态、迁移规则、CAS 和状态日志
- [ ] 讲清半开区间 `[start, end)` 的数学含义和 SQL 写法
- [ ] 讲清 buffer、待审批不占时段、锁外快检/锁内复检
- [ ] 能读懂日历的几何换算、重叠泳道布局和预约表单校验链
- [ ] 能把“区间重叠”迁移到其他资源占用场景

### 1.2 时间分配（6h）

| 时间段 | 内容 | 产出 |
|--------|------|------|
| 0:00–1:00 | 概念：状态机、区间、buffer、索引 | 一张状态图 + 一张区间图 |
| 1:00–2:30 | 后端走读：状态机、冲突、创建、查询 | 能口述 create 两段式 |
| 2:30–3:30 | 前端走读：日历几何、布局、表单 | 能口述泳道算法 |
| 3:30–5:00 | 手敲：冲突函数、单测、EXPLAIN、变体 | 测试全绿 + EXPLAIN 结果 |
| 5:00–6:00 | 自测 + 复盘 + 举一反三 | 8 道题 + 复盘卡 |

### 1.3 前置

```bash
cd deploy && docker compose ps
# 9 个常驻容器 Up + mysql-init Exited(0)
```

如果只想跑后端单测，本地 MySQL/Redis/RabbitMQ 已启动即可。

### 1.4 今天的“一张图”

```text
预约请求
  → 锁外快检：房间可用？时间合法？需不需要审批？
  → 房间锁（roomId + date）
  → 事务：
      会议室行锁
      维护状态复查
      冲突检测（半开区间 + buffer）
      插入会议 + 参会人
      状态机日志
      领域事件
  → 提交事务 → 释放锁
```

记住三句话：

- **状态机 = 迁移表 + CAS + 日志**
- **冲突 = 半开区间 + status IN (20,30) + buffer**
- **锁外快检、锁内复检**

---

## 2. 面试先问

先自己想 30 秒，答案在第 6 节。

1. 为什么用半开区间 `[start, end)`，闭区间会误判什么场景？
2. 冲突检测的 SQL 怎么写？为什么是 `start < scanEnd AND end > scanStart`？
3. buffer 为什么是“扩展扫描区间”，而不是“扩展每个已有区间”？
4. 待审批的会议占不占时段？审批通过时怎么防被抢？
5. 冲突检测应该在锁内还是锁外？为什么？
6. 状态机为什么用迁移表 + CAS，而不是 if/else + 直接 UPDATE？
7. 同一会议室重叠会议，日历上怎么排布？相邻会议为什么不算重叠？
8. 预约表单的校验和后端校验怎么分工？

> 这 8 个问题覆盖“区间算法 + 事务 + 状态机 + 前端布局 + 表单”，是预约类系统的通用考点。

---

## 3. 10 分钟概念

### 3.1 业务背景：预约到底在解决什么

会议室的本质是**有限资源 + 时间区间**：

- 一个会议室，一天只有 08:00–20:00
- 同一时间只能有一场“占用态”的会议
- 待审批不占时段，但审批通过时必须保证不被抢
- 相邻时段可以衔接：10:00–11:00 与 11:00–12:00 不冲突

所以核心问题是：

> 给定一个候选区间，判断它和已有“占用态”会议是否重叠。

### 3.2 状态机：为什么不是 if/else

会议有 6 个状态：

```text
10 待审批 ──通过──> 20 已预约 ──开始──> 30 进行中 ──结束──> 40 已结束
   │                    │
   ├──拒绝──> 60 已拒绝  ├──取消──> 50 已取消
   └──取消──> 50 已取消  └──改到需审批会议室──> 10 待审批
```

如果写 if/else：

```java
if (status == 10 && action == APPROVE) status = 20;
else if (status == 10 && action == REJECT) status = 60;
else if (status == 20 && action == CANCEL) status = 50;
...
```

问题：

- 分支越加越多，容易漏
- 非法迁移没有统一拦截
- 并发下“读状态 → 判断 → 更新”有窗口
- 没有统一日志

状态机的做法：

```text
迁移表：Map<当前状态, Map<事件, 目标状态>>
校验：查表，查不到就是非法
更新：CAS（WHERE status = 前态）
日志：同一事务写 meeting_status_log
```

类比：红绿灯。不是“看到红灯就 if 一下”，而是有一张“当前灯 + 事件 → 下一个灯”的表；表里没有的组合就是非法。

### 3.3 半开区间：数学与直觉

占用区间是 `[start, end)`：

```text
10:00 ───────── 11:00
                11:00 ───────── 12:00
```

两个区间重叠的条件：

```text
existing.start < new.end  AND  existing.end > new.start
```

为什么用 `<` / `>` 而不是 `<=` / `>=`：

- 相邻：`11:00 < 12:00` 真，`11:00 > 11:00` 假 → 不冲突
- 闭区间：`11:00 <= 12:00` 真，`11:00 >= 11:00` 真 → 误判冲突

数学直觉：

- 把时间想成数轴上的半开线段
- 两个半开线段重叠，当且仅当“一个的开始 < 另一个的结束，且一个的结束 > 另一个的开始”

边界情况：

| 场景 | 示例 | 结果 |
|------|------|------|
| 相邻 | [10,11) vs [11,12) | 不冲突 |
| 交叉 | [10,11:30) vs [11,12) | 冲突 |
| 包含 | [10,12) vs [10:30,11) | 冲突 |
| 相同 | [10,11) vs [10,11) | 冲突 |
| 完全分离 | [10,11) vs [12,13) | 不冲突 |

### 3.4 buffer：锁定缓冲

有些会议室需要“前后缓冲”：

- 上一场 11:00 结束，下一场 11:15 开始
- buffer=15 → 下一场实际检测区间变成 `[11:00, ...)`，与上一场冲突

实现上有两种等价写法：

```text
写法 A：扩展每个已有区间 [start-buffer, end+buffer)
写法 B：扩展候选区间 [newStart-buffer, newEnd+buffer)
```

`ConflictChecker` 选 B：

```java
LocalDateTime scanStart = start.minusMinutes(buffer);
LocalDateTime scanEnd = end.plusMinutes(buffer);
return meetingMapper.selectConflicts(roomId, scanStart, scanEnd, excludeMeetingId);
```

为什么 B 更好：

- 只算一次，不用逐个扩展已有区间
- SQL 条件仍然简单
- 数学等价：把扫描窗口放宽 = 把每个已有区间放宽

### 3.5 索引：为什么冲突 SQL 能走 range

`meeting` 表索引：

```sql
KEY idx_room_time (room_id, start_time, end_time)
```

冲突 SQL：

```sql
WHERE m.room_id = #{roomId}
  AND m.status IN (20, 30)
  AND m.start_time < #{scanEnd}
  AND m.end_time > #{scanStart}
```

索引使用：

- `room_id` 等值 → 定位到该会议室
- `start_time` 范围 → `range` 扫描
- `end_time` 在索引里 → 覆盖，减少回表

EXPLAIN 期望：

```text
type=range
key=idx_room_time
rows 较小
```

注意：`status IN (20,30)` 不是索引前缀，但 `room_id + start_time` 已经把范围缩小；数据量大时可以再评估 `(room_id, status, start_time)`。

### 3.6 锁外快检 vs 锁内复检

```text
锁外：
  - 房间存在/可用
  - 时间合法（08:00–20:00、不跨天、不过去）
  - 是否需审批
作用：快速失败，避免无谓抢锁

锁内：
  - 会议室行锁（数据库兜底）
  - 维护状态复查（锁外可能过期）
  - 冲突检测（必须锁内）
  - 落库
作用：保证“检测 → 落库”之间没有别人插入
```

为什么冲突检测必须锁内：

- 锁外检测到落库之间，别的请求可能插入
- 锁保证同一房间同一时段串行，检测结果才可信

### 3.7 日历布局：重叠会议怎么排

同一会议室同一时段可能有多场会议（尤其待审批不占时段，可以和多场已预约并存）。

`calendarLayout.ts` 的算法：

```text
1. 按开始时间排序
2. 把时间上连通的会议聚成一簇
3. 簇内贪心分配泳道：
   - 找最早空出来的泳道（laneEnd <= start）
   - 没有就新开一条
4. 簇内按 lane/lanes 均分宽度
```

为什么相邻不算重叠：

- `laneEnd <= start`，上一场结束时间等于本场开始时间，可以复用同一泳道

类比：停车场车位。每场会议是一辆车，泳道是车位；车走了（结束）车位就可以给下一辆。

### 3.8 表单校验：前端挡“明显不合法”

`bookingSchema` 用 zod：

- 主题必填、最长 100
- 会议室必填
- 时间范围必选，结束 > 开始
- 不能预约过去
- 08:00–20:00，不能跨天
- 周期会议：重复结束日期必填且晚于开始

前端只挡“明显不合法”；“最多提前 N 天、是否需审批、冲突”由服务端按策略判定。

为什么：

- 策略在数据库/服务端，前端拿不到完整规则
- 前端校验可以被绕过
- 服务端必须再校验一遍

### 3.9 记忆法：状态、区间、索引、布局

```text
状态：10 待审 → 20 已约 → 30 进行 → 40 结束
      10/20 可取消 → 50；10 可拒绝 → 60
区间：[start, end)，相邻不冲突；buffer 扩扫描
索引：room_id + start_time + end_time
布局：连通聚簇 + 贪心泳道
```

---

## 4. 代码走读

### 4.1 后端：状态机

#### 4.1.1 状态枚举

文件：`server/meeting-service/src/main/java/com/smt/meeting/enums/MeetingStatusEnum.java`

```java
public enum MeetingStatusEnum {
    PENDING_APPROVAL(10, "待审批"),
    RESERVED(20, "已预约"),
    ONGOING(30, "进行中"),
    FINISHED(40, "已结束"),
    CANCELLED(50, "已取消"),
    REJECTED(60, "已拒绝");

    private final int code;
    private final String label;
}
```

- 40/50/60 是终态
- 30 进行中不能取消（业务矩阵）
- 数字码落库，label 只用于展示

#### 4.1.2 迁移表

文件：`server/meeting-service/src/main/java/com/smt/meeting/statemachine/MeetingStateMachine.java`

```java
private static final Map<Integer, Map<MeetingEventEnum, Integer>> TRANSITIONS = Map.of(
    PENDING_APPROVAL.getCode(), Map.of(
        APPROVE, RESERVED.getCode(),
        REJECT, REJECTED.getCode(),
        CANCEL, CANCELLED.getCode()
    ),
    RESERVED.getCode(), Map.of(
        START, ONGOING.getCode(),
        END, FINISHED.getCode(),
        CANCEL, CANCELLED.getCode(),
        MODIFY, PENDING_APPROVAL.getCode()
    ),
    ONGOING.getCode(), Map.of(
        END, FINISHED.getCode()
        // 30 禁止 CANCEL
    )
);
```

设计点：

- **fail-closed**：查不到的组合一律非法，不默认放行
- **事件驱动**：不是“设置状态”，而是“发生事件 → 目标状态”
- **可读性**：一张表就是业务矩阵，评审时直接对照需求

#### 4.1.3 transition：校验 + CAS + 日志

```java
@Transactional
public void transition(Long meetingId, MeetingEventEnum event, Long operatorId, String remark) {
    Meeting meeting = meetingMapper.selectById(meetingId);
    if (meeting == null) throw new BusinessException(MEETING_NOT_FOUND);

    Integer fromStatus = meeting.getStatus();
    Integer toStatus = TRANSITIONS.getOrDefault(fromStatus, Map.of()).get(event);
    if (toStatus == null) throw new BusinessException(MEETING_STATUS_NOT_ALLOWED);

    int affected = meetingMapper.updateStatus(meetingId, fromStatus, toStatus);
    if (affected == 0) throw new BusinessException(MEETING_STATUS_NOT_ALLOWED);

    insertLog(meetingId, fromStatus, toStatus, event, operatorId, remark);
}
```

SQL：

```sql
UPDATE meeting
SET status = #{toStatus}
WHERE id = #{id} AND status = #{fromStatus}
```

为什么 CAS 是真正的并发防线：

- `selectById` 读到的状态可能已经过期
- `WHERE status = fromStatus` 保证只有一个请求能把状态改走
- 影响 0 行 → 40002

日志：

- `from_status`、`to_status`、`event`、`operator_id`、`remark`
- 创建时 `from_status = NULL`
- 延长会议状态不变（30 → 30），单独 `logExtension`
- remark 截断到 200（列宽），避免 1406 导致整笔回滚

### 4.2 后端：冲突检测

#### 4.2.1 ConflictChecker

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/ConflictChecker.java`

```java
public List<MeetingConflictVO> findConflicts(Long roomId, LocalDateTime start,
                                             LocalDateTime end, Long excludeMeetingId) {
    return findConflicts(roomId, start, end, excludeMeetingId, bufferMinutes(roomId));
}

public List<MeetingConflictVO> findConflicts(Long roomId, LocalDateTime start, LocalDateTime end,
                                             Long excludeMeetingId, int buffer) {
    LocalDateTime scanStart = start.minusMinutes(buffer);
    LocalDateTime scanEnd = end.plusMinutes(buffer);
    return meetingMapper.selectConflicts(roomId, scanStart, scanEnd, excludeMeetingId);
}
```

- `bufferMinutes(roomId)` 查 `room_strategy`，无策略按 0
- 批量/周期场景复用同一个 buffer，避免每场查一次策略
- 纯查询、无锁；并发保证由创建链路的锁 + 锁内复检承担

#### 4.2.2 冲突 SQL

文件：`server/meeting-service/src/main/resources/mapper/MeetingMapper.xml`

```sql
SELECT m.id AS meeting_id, m.title, m.start_time, m.end_time,
       m.organiser_id, u.real_name AS organiser_name
FROM meeting m
LEFT JOIN sys_user u ON u.id = m.organiser_id
WHERE m.room_id = #{roomId}
  AND m.status IN (20, 30)
  AND m.start_time < #{scanEnd}
  AND m.end_time > #{scanStart}
  <if test="excludeMeetingId != null">
      AND m.id != #{excludeMeetingId}
  </if>
ORDER BY m.start_time
```

逐条：

- `status IN (20,30)`：只有已预约/进行中占时段；10 待审批、40/50/60 不占
- `start_time < scanEnd AND end_time > scanStart`：半开区间重叠
- `excludeMeetingId`：改期排除自己
- `ORDER BY start_time`：返回给前端展示冲突详情

#### 4.2.3 EXPLAIN 怎么看

```sql
EXPLAIN SELECT ...
```

关注：

- `type`：`range` 比 `ALL` 好
- `key`：`idx_room_time`
- `rows`：扫描行数
- `Extra`：`Using index`（覆盖）更好

如果 `type=ALL`：

- 检查 `room_id` 是否有索引
- 检查是否对列做了函数/隐式转换
- 检查 `status` 的选择性

### 4.3 后端：创建预约

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/MeetingReserveService.java`

```java
public MeetingCreateVO create(MeetingCreateDTO dto, Long organiserId) {
    // 第一段：无锁前置校验
    MeetingRoom room = loadAvailableRoom(dto.getRoomId());
    RoomStrategy strategy = roomStrategyMapper.selectByRoomId(room.getId());
    validateTime(dto.getStartTime(), dto.getEndTime(), strategy);

    boolean needApproval = isNeedApproval(strategy);
    int status = stateMachine.initialStatus(needApproval);

    // 第二段：锁内复检 + 事务落库
    return lockHelper.executeWithLock(room.getId(), dto.getStartTime(), dto.getEndTime(), () ->
        transactionTemplate.execute(tx -> {
            meetingRoomMapper.selectIdForUpdate(room.getId());   // 数据库兜底
            ensureRoomNotMaintenanceAfterLock(room.getId());     // 锁外快检可能过期
            checkConflict(room.getId(), dto.getStartTime(), dto.getEndTime(), null);

            Meeting meeting = insertMeeting(dto, room.getId(), organiserId, status);
            saveParticipants(meeting.getId(), dto.getParticipants());
            stateMachine.logCreation(meeting.getId(), status, organiserId, null);

            eventPublisher.publishEvent(MeetingDomainEvent.created(...));
            return new MeetingCreateVO(meeting.getId(), status, needApproval, ...);
        }));
}
```

为什么要两段：

- 锁外快检：明显不合法（房间不存在、时间非法）不抢锁，减少锁竞争
- 锁内复检：锁外到锁内之间，房间可能被置维护、时段可能被抢
- 会议室行锁：Redis 锁失效时，数据库行锁仍是兜底
- 事件在事务内发布，AFTER_COMMIT 才发 MQ（Day 4）

### 4.4 后端：可用性与查询

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/AvailabilityService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MeetingQueryService.java`

`AvailabilityService`：

- `check`：返回 `available` + 冲突详情
- `calendar` / `calendars`：按周返回房间会议，带缓存
- `onMeetingChanged`：会议变更后失效日历缓存

`MeetingQueryService`：

- `page`：scope/status/roomId/日期/关键词/分页
- `detail`：会议 + 参会人 + 状态时间线

### 4.5 前端：日历

#### 4.5.1 Calendar 页面

文件：`web/src/pages/Calendar.tsx`

```tsx
const { data, loading, refresh } = useAppRequest<WeekPayload>(
  () => getWeekCalendar(week, roomIds),
  { refreshDeps: [week, roomIds] },
)
```

- `useAppRequest` 封装请求
- `refreshDeps`：切周/切会议室自动重查
- 点击空白格子 → `EmptySlot` → 打开 `BookingDrawer`

#### 4.5.2 几何换算

文件：`web/src/components/calendarGeometry.ts`

```ts
export const DAY_START_MINUTES = 8 * 60
export const DAY_END_MINUTES = 20 * 60
export const TOTAL_MINUTES = DAY_END_MINUTES - DAY_START_MINUTES
export const SNAP_MINUTES = 30
export const DEFAULT_DURATION_MINUTES = 60

export function slotFromClickRatio(ratio: number) {
  const raw = DAY_START_MINUTES + ratio * TOTAL_MINUTES
  const start = Math.min(
    clampMinutes(Math.round(raw / SNAP_MINUTES) * SNAP_MINUTES),
    DAY_END_MINUTES - SNAP_MINUTES,
  )
  return {
    startMinutes: start,
    endMinutes: Math.min(start + DEFAULT_DURATION_MINUTES, DAY_END_MINUTES),
  }
}
```

为什么要纯函数：

- 像素 ↔ 时刻最容易算错
- 抽出来可以用单测覆盖：吸附、边界、跨天
- 组件只负责 DOM 测量

`blockGeometry`：

```ts
const start = clampMinutes(toMinutes(meeting.startTime))
const end = sameDay ? clampMinutes(toMinutes(meeting.endTime)) : DAY_END_MINUTES
return {
  style: {
    top: `${((start - DAY_START_MINUTES) / TOTAL_MINUTES) * 100}%`,
    height: `${((end - start) / TOTAL_MINUTES) * 100}%`,
  },
  minutes: end - start,
}
```

跨天会议裁到窗口末端，避免负高度。

#### 4.5.3 重叠布局

文件：`web/src/components/calendarLayout.ts`

```ts
export function layoutMeetings(meetings: CalendarMeeting[]): Map<number, MeetingLane> {
  const sorted = [...meetings].sort(...)
  const result = new Map()
  let cluster = []
  let clusterEnd = -Infinity

  const flush = () => {
    const laneEnds: number[] = []
    const laneById = new Map()
    for (const meeting of cluster) {
      const start = dayjs(meeting.startTime).valueOf()
      const end = dayjs(meeting.endTime).valueOf()
      let lane = laneEnds.findIndex((laneEnd) => laneEnd <= start)
      if (lane === -1) {
        lane = laneEnds.length
        laneEnds.push(end)
      } else {
        laneEnds[lane] = end
      }
      laneById.set(meeting.meetingId, lane)
    }
    const lanes = laneEnds.length
    for (const meeting of cluster) {
      result.set(meeting.meetingId, { lane: laneById.get(meeting.meetingId)!, lanes })
    }
    cluster = []
    clusterEnd = -Infinity
  }

  for (const meeting of sorted) {
    const start = dayjs(meeting.startTime).valueOf()
    if (cluster.length > 0 && start >= clusterEnd) flush()
    cluster.push(meeting)
    clusterEnd = Math.max(clusterEnd, dayjs(meeting.endTime).valueOf())
  }
  flush()
  return result
}
```

算法拆解：

1. 排序：按开始时间，开始相同按 meetingId
2. 聚簇：`start >= clusterEnd` 表示与当前簇完全不重叠，先收掉上一簇
3. 贪心泳道：`laneEnd <= start` 复用；否则新开
4. 簇内统一 `lanes`，按 `lane/lanes` 均分宽度

#### 4.5.4 预约表单

文件：
- `web/src/pages/BookingDrawer.tsx`
- `web/src/pages/bookingSchema.ts`

`bookingSchema` 关键：

```ts
export const bookingSchema = z.object({
  title: z.string().trim().min(1).max(100),
  roomId: z.number({ required_error: '请选择会议室' }),
  timeRange: z.tuple([
    z.custom<Dayjs>((v) => dayjs.isDayjs(v), '请选择会议时间'),
    z.custom<Dayjs>((v) => dayjs.isDayjs(v), '请选择会议时间'),
  ], { required_error: '请选择会议时间', invalid_type_error: '请选择会议时间' }),
  userIds: z.array(z.number()),
  remark: z.string().max(500).optional(),
  repeatWeekly: z.boolean().default(false),
  repeatEndDate: z.custom<Dayjs>((v) => dayjs.isDayjs(v)).optional(),
})
.refine((v) => v.timeRange[0].isBefore(v.timeRange[1]), { path: ['timeRange'], message: '结束时间必须晚于开始时间' })
.refine((v) => v.timeRange[0].isAfter(dayjs()), { path: ['timeRange'], message: '不能预约过去的时间' })
.superRefine((v, ctx) => {
  const msg = businessHoursViolation(v.timeRange[0], v.timeRange[1])
  if (msg) ctx.addIssue({ code: 'custom', path: ['timeRange'], message: msg })
})
```

为什么 `timeRange` 要同时处理 `null` 和非法元素：

- rc-picker 清空传 `null`，不是 `[null, null]`
- `z.custom<Dayjs>()` 不带校验等于恒真
- 不加 `invalid_type_error` 会漏出英文默认错误

#### 4.5.5 会议列表

文件：`web/src/pages/MeetingList.tsx`

- 筛选：状态/会议室/日期
- 分页
- 操作列按状态给编辑/取消/详情
- 时间格式统一 `YYYY-MM-DD HH:mm`

### 4.6 边界与异常

| 场景 | 行为 | 说明 |
|------|------|------|
| 相邻时段 | 不冲突 | 半开区间 |
| buffer=15 | 前后各扩 15 分钟 | 扫描区间扩展 |
| 待审批 | 不占时段 | status IN (20,30) |
| 审批通过被抢 | 50001 + 冲突详情 | 复检 |
| 改期 | 排除自身 + 房间锁 | 跨行竞态 |
| 跨天会议 | 日历裁到 20:00 | 避免负高度 |
| 点到 20:00 | 预填 19:30–20:00 | 避免 start=end |
| 清空时间 | zod 自定义错误 | 处理 null |
| 过去时间 | 前端 disabled + 后端校验 | 双保险 |
| 周期会议 | 逐场检测 + 只锁需要日期 | 避免锁太多 |

### 4.7 📘 笔记引用

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§二.2 索引基础 / §二.3 SQL 优化
> 类型：A（项目直接对应）
> 笔记结论：联合索引要遵循最左前缀；`WHERE room_id AND start_time/end_time` 的 range 查询要能走索引。
> 项目落点：`MeetingMapper.xml` 的 `selectConflicts`；`idx_room_time (room_id, start_time, end_time)`。
> 差异/提醒：项目没有为每条 SQL 做 EXPLAIN 截图，但索引设计和 `type=range` 是验收点。

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§五.2 事务基础 / §五.3 隔离级别
> 类型：A（项目直接对应）
> 笔记结论：事务保证一组操作原子性，但不等于互斥；并发改同一行仍要锁/版本。
> 项目落点：`MeetingReserveService.create` 的 `TransactionTemplate` + 房间行锁。
> 差异/提醒：项目用编程式事务 `TransactionTemplate`，因为锁必须包住事务。

> 📘 **笔记引用**：《11.Spring Boot》§六.1 MyBatis / §六.2 MyBatis 原理
> 类型：A/B（项目直接对应 + 有差异）
> 笔记结论：MyBatis 用 XML 写 SQL、`resultMap` 映射、`#{}` 参数化防注入。
> 项目落点：`MeetingMapper.xml` 的 `selectConflicts`、`selectByIdForUpdate`。
> 差异/提醒：项目以手写 XML 为主，MyBatis-Plus 只是依赖，没有大量用便利方法。

> 📘 **笔记引用**：《2.React知识点》§二.4 受控/非受控组件 + §四.1 Hooks 基础
> 类型：A（项目直接对应）
> 笔记结论：受控组件由 state 驱动；表单库把受控逻辑收敛到 `control`。
> 项目落点：`BookingDrawer` 用 React Hook Form + antd `Controller`。
> 差异/提醒：项目没有手写 `useState` 管每个字段，统一交给 RHF。

> 📘 **笔记引用**：《2.React知识点》§四.5 useEffect 异步坑点
> 类型：A（项目直接对应）
> 笔记结论：异步请求要处理竞态/卸载，晚返回的旧响应不能覆盖新状态。
> 项目落点：`Calendar` 切周请求、`MeetingDetail` 请求。
> 差异/提醒：普通请求由 ahooks 管生命周期；自定义请求要自己处理 token。

> 📘 **笔记引用**：《3.Ts基础》§7 类型收窄 & 可辨识联合
> 类型：C（纯基础补充）
> 笔记结论：用联合类型 + 类型守卫把“状态/事件”的非法组合挡在编译期。
> 项目落点：`bookingSchema` 的 `z.infer`、`MeetingStatusEnum` 的 code/label。
> 差异/提醒：项目状态码是数字枚举，不是 TS 可辨识联合；思路相通。

> 📘 **笔记引用**：《4.前端工程化》§4.1 测试分层 / §4.2 组件测试原则
> 类型：B（项目有差异）
> 笔记结论：纯逻辑单测、组件测试、E2E 分层，不要重复投入。
> 项目落点：`web/tests/components/calendarGeometry.test.ts`、`calendarLayout.test.ts`；组件行为交给 Playwright E2E。
> 差异/提醒：项目没有引入 jsdom/组件测试库，纯逻辑优先。

### 4.8 举一反三

#### 4.8.1 区间重叠还能用在哪

- 酒店/机票库存：入住/起飞时间区间
- 教室/实验室排课
- 员工排班
- 服务器资源占用（CPU/GPU 时间段）
- 优惠券有效期重叠

公式不变：

```text
A.start < B.end AND A.end > B.start
```

#### 4.8.2 状态机还能用在哪

- 订单：待支付 → 已支付 → 已发货 → 已完成/退款
- 审批流：草稿 → 待审 → 通过/拒绝
- 工单：新建 → 处理中 → 已解决 → 关闭
- 支付：初始化 → 支付中 → 成功/失败/退款

套路：

```text
迁移表 + 事件 + CAS + 日志 + 定时补偿
```

#### 4.8.3 纯函数拆分

以下逻辑都适合抽成纯函数：

- 时间/像素换算
- 区间判重
- 分页/排序
- 状态映射
- 表单校验规则
- 文本切分/时间估算

好处：可单测、可复用、组件更薄。

#### 4.8.4 反例

- 用闭区间判重 → 相邻时段误判
- 冲突检测放锁外 → 并发下双预订
- 只在前端校验 → 绕过表单直接调 API
- 状态直接 UPDATE → 没有日志/非法迁移
- 冲突 SQL 不带 status → 待审批把时段锁死

---

## 5. 手敲练习

### 5.1 基础：写半开区间函数 + 单测

在 `server/meeting-service/src/test/java/com/smt/meeting/service/` 下新建（学习分支）：

```java
package com.smt.meeting.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class ConflictIntervalPracticeTest {

    static boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd,
                            LocalDateTime bStart, LocalDateTime bEnd) {
        return aStart.isBefore(bEnd) && aEnd.isAfter(bStart);
    }

    private static LocalDateTime t(int hour, int minute) {
        return LocalDateTime.of(2026, 10, 20, hour, minute);
    }

    @Test
    void adjacentIsNotConflict() {
        assertFalse(overlaps(t(10, 0), t(11, 0), t(11, 0), t(12, 0)));
    }

    @Test
    void crossingIsConflict() {
        assertTrue(overlaps(t(10, 0), t(11, 30), t(11, 0), t(12, 0)));
    }

    @Test
    void containingIsConflict() {
        assertTrue(overlaps(t(10, 0), t(12, 0), t(10, 30), t(11, 0)));
    }

    @Test
    void sameRangeIsConflict() {
        assertTrue(overlaps(t(10, 0), t(11, 0), t(10, 0), t(11, 0)));
    }

    @Test
    void bufferBoundary() {
        assertTrue(overlaps(t(9, 45), t(11, 15), t(11, 10), t(11, 30)));
        assertFalse(overlaps(t(9, 45), t(11, 15), t(11, 15), t(11, 30)));
    }
}
```

Run:

```bash
cd server
mvn -pl meeting-service -Dtest=ConflictIntervalPracticeTest test
```

验收：5 个测试全绿；能解释每个用例。

### 5.2 看 EXPLAIN

```bash
docker exec -it smt-mysql mysql -uroot -proot123 smt_meeting
```

```sql
EXPLAIN
SELECT m.id, m.start_time, m.end_time
FROM meeting m
WHERE m.room_id = 1
  AND m.status IN (20, 30)
  AND m.start_time < '2026-10-20 12:00:00'
  AND m.end_time > '2026-10-20 10:00:00';
```

期望：

```text
type=range
key=idx_room_time
```

如果 `type=ALL`，检查索引/数据量/隐式转换。

### 5.3 变体：buffer 0 vs 15

1. 把某个会议室策略的 `buffer_minutes` 改成 15
2. 已有会议 10:00–11:00
3. 尝试预约 11:10–11:30
4. 期望：buffer=15 时冲突；buffer=0 时不冲突

验收：能用 `scanStart/scanEnd` 解释结果。

### 5.4 破坏性实验（学习分支）

- 把 `selectConflicts` 的 `status IN (20,30)` 去掉 → 待审批会议也占时段，观察可用性接口
- 把 `start_time < scanEnd AND end_time > scanStart` 改成 `<=`/`>=` → 相邻时段变冲突
- 把冲突检测移到锁外 → 并发脚本更容易复现双预订

### 5.5 变体：加“最长时长”校验

需求：每场会议最长 2 小时。

- 后端：`validateTime` 加 `Duration.between(start,end) <= 2h`
- 前端：`bookingSchema.superRefine` 加同样规则
- 测试：3 小时被拒，2 小时通过

验收：前后端都拦截；错误提示一致。

---

## 6. 八道自测

### 题目

1. 为什么用半开区间？闭区间会误判什么场景？
2. 冲突 SQL 为什么是 `start < scanEnd AND end > scanStart`？
3. buffer 为什么是“扩展扫描区间”？
4. 待审批会议为什么不占时段？通过时怎么防被抢？
5. 冲突检测为什么必须在锁内？
6. 状态机为什么用迁移表 + CAS？
7. 日历重叠会议怎么排布？相邻为什么不算重叠？
8. 前端校验和后端校验怎么分工？

### 答案

1. 半开区间让相邻时段合法（11:00 结束、11:00 开始不冲突）；闭区间会把这种正常衔接判成冲突。
2. 两个区间重叠当且仅当“一个开始 < 另一个结束，且一个结束 > 另一个开始”；`<`/`>` 保证端点相接不算重叠。
3. 扩展扫描区间与逐个扩展已有区间数学等价，但只算一次、SQL 简单；buffer 前后各放宽。
4. 待审批不占时段，避免资源被长期锁死；审批通过时房间锁 + 会议室行锁 + 锁内复检，失败返回 50001。
5. 锁外检测到落库之间别人可能插入；锁保证同一房间同一时段串行，检测结果才可信。
6. 迁移表把业务矩阵显式化、fail-closed；CAS 用 `WHERE status=前态` 防并发；同事务写日志保证可追溯。
7. 按时间连通聚簇，簇内贪心分配泳道，按 `lane/lanes` 均分宽度；`laneEnd <= start` 允许复用泳道，所以相邻不算重叠。
8. 前端挡明显不合法（必填、顺序、营业时间、过去时间）；策略/冲突/审批由服务端判定，前端展示错误。

### 追问

- 如果要支持“跨天会议”，冲突 SQL 和日历布局要怎么改？
- 如果会议室有“设备”约束，冲突检测要不要考虑设备？
- 如果数据量到百万级，索引怎么调整？
- 如果要做“候补队列”，状态机怎么扩展？

---

## 7. 复盘卡

### 7.1 三句话

```text
1. 半开区间让相邻时段合法，冲突检测必须锁内做。
2. 状态机 = 迁移表 + CAS + 日志，三者同一事务。
3. 日历布局是“连通聚簇 + 贪心泳道”，纯函数可单测。
```

### 7.2 归纳：预约链路一张表

| 阶段 | 组件 | 关键动作 | 失败 |
|------|------|----------|------|
| 锁外 | MeetingReserveService | 房间/时间/策略校验 | 40001/30003 等 |
| 加锁 | MeetingLockHelper | 房间+日期锁 | 10007 |
| 锁内 | 事务 | 行锁、维护复查、冲突检测 | 40001/50001 |
| 落库 | Mapper | 插入会议/参会人/日志 | 回滚 |
| 事件 | Publisher | 事务内发布 | AFTER_COMMIT 发 MQ |

### 7.3 演绎：从原则推导新场景

原则：**先缩小范围，再做精确判断；先快速失败，再做重操作**。

由此推导：

- 为什么先按 room_id 过滤？——缩小范围
- 为什么先锁外校验？——快速失败，减少锁竞争
- 为什么冲突检测在锁内？——精确判断需要稳定快照
- 为什么状态机先查表？——fail-closed，避免漏分支
- 为什么前端只做明显校验？——策略在服务端，前端无法拿到完整规则

### 7.4 记忆法

```text
状态：10 待审 → 20 已约 → 30 进行 → 40 结束；10/20 可取消，10 可拒绝
区间：[start,end)，相邻不冲突；buffer 扩扫描
索引：room_id + start_time + end_time
布局：连通聚簇 + 贪心泳道
```

### 7.5 60 秒面试话术

> 预约冲突用半开区间 `[start,end)`，SQL 条件是 `start < scanEnd AND end > scanStart`，
> 只查 status 20/30，待审批不占时段；buffer 通过扩展扫描区间实现。
> 创建分两段：锁外快检，锁内用房间锁 + 会议室行锁 + 冲突复检 + 落库；
> 状态变更统一走迁移表 + CAS + 状态日志。前端日历用纯函数做像素/时间换算，
> 重叠会议按连通聚簇 + 贪心泳道布局。

### 7.6 明日预告

Day 3 进入并发：Redisson 锁、锁与事务顺序、幂等、乐观锁。

---

### 附：今天要能打开的文件

- `server/meeting-service/src/main/java/com/smt/meeting/enums/MeetingStatusEnum.java`
- `server/meeting-service/src/main/java/com/smt/meeting/statemachine/MeetingStateMachine.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/ConflictChecker.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MeetingReserveService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/AvailabilityService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MeetingQueryService.java`
- `server/meeting-service/src/main/resources/mapper/MeetingMapper.xml`
- `web/src/pages/Calendar.tsx`
- `web/src/components/CalendarGrid.tsx`
- `web/src/components/calendarGeometry.ts`
- `web/src/components/calendarLayout.ts`
- `web/src/pages/BookingDrawer.tsx`
- `web/src/pages/bookingSchema.ts`
- `web/src/pages/MeetingList.tsx`
