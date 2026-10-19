# Day 2：预约核心 + 日历/预约表单

> 时间：6 小时
> 目标：把“预约”这条最核心的业务链路讲清楚：状态机、半开区间、冲突检测、日历布局、表单校验。
> 今天的手敲是**写一个半开区间冲突函数 + 单测**，这是面试最容易被追问的代码。

---

## 1. 今日目标

- [ ] 讲清 6 个会议状态和迁移规则
- [ ] 讲清半开区间冲突检测、buffer、锁内复检
- [ ] 能读懂日历布局和预约表单的校验链

---

## 2. 面试先问

1. 为什么用半开区间 `[start, end)`，而不是闭区间？
2. 冲突检测应该在锁内还是锁外？为什么？
3. 待审批的会议占不占时段？
4. 同一会议室重叠会议，日历上怎么排布？
5. 预约表单的校验和后端校验怎么分工？

---

## 3. 10 分钟概念

### 3.1 六个状态

```text
10 待审批 ──通过──> 20 已预约 ──开始──> 30 进行中 ──结束──> 40 已结束
   │                    │
   ├──拒绝──> 60 已拒绝  ├──取消──> 50 已取消
   └──取消──> 50 已取消  └──改到需审批会议室──> 10 待审批
```

- 40/50/60 是终态
- 30 进行中不能取消（业务矩阵明确规定）
- 创建时：需审批 → 10；免审 → 20

### 3.2 半开区间

占用区间是 `[start, end)`：

```text
已有：[10:00, 11:00)
新增：[11:00, 12:00)
重叠条件：existing.start < new.end AND existing.end > new.start
        10:00 < 12:00  AND  11:00 > 11:00  → 假 → 不冲突
```

相邻时段合法，这是会议室使用的真实语义。

### 3.3 buffer

如果会议室策略配置了 `bufferMinutes = 15`：

```text
新增 [10:00, 11:00) 的检测区间扩展为 [09:45, 11:15)
```

等价于把已有会议前后各放宽 15 分钟。`ConflictChecker` 选择“扩展扫描区间”而不是逐个扩展已有区间，少算一次。

### 3.4 锁外快检 vs 锁内复检

- 锁外：快速失败（房间是否存在、时间是否合法、是否维护）
- 锁内：房间行锁 + 维护状态复查 + 冲突检测 + 落库
- 冲突检测**必须在锁内**：锁保证同一房间同一时段串行，检测结果才可信

---

## 4. 代码走读

### 4.1 后端：状态机与冲突检测

#### 4.1.1 状态枚举

文件：`server/meeting-service/src/main/java/com/smt/meeting/enums/MeetingStatusEnum.java`

```java
PENDING_APPROVAL(10), RESERVED(20), ONGOING(30),
FINISHED(40), CANCELLED(50), REJECTED(60)
```

六个状态足够表达生命周期；原系统 12 个状态里很多是“展示态”，被收敛掉了。

#### 4.1.2 状态机

文件：`server/meeting-service/src/main/java/com/smt/meeting/statemachine/MeetingStateMachine.java`

三个关键点：

1. **迁移表驱动**：`Map<当前状态, Map<事件, 目标状态>>`，未登记的组合一律非法（fail-closed）。
2. **CAS 更新**：`updateStatus(meetingId, fromStatus, toStatus)`，`WHERE status = fromStatus`；影响 0 行说明并发已改，按 40002 处理。
3. **校验 + CAS + 日志在同一事务**：`@Transactional`，任何一步失败整体回滚；禁止业务代码直接 `setStatus`。

创建时 `initialStatus(needApproval)` 决定 10/20；`logCreation` 写一条 `from_status = NULL` 的初始日志。

面试一句话：

> 状态机不是 if/else，而是“迁移表 + CAS + 日志”三件套；CAS 才是真正防并发的那一步。

#### 4.1.3 冲突检测

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/ConflictChecker.java`

```java
LocalDateTime scanStart = start.minusMinutes(buffer);
LocalDateTime scanEnd = end.plusMinutes(buffer);
return meetingMapper.selectConflicts(roomId, scanStart, scanEnd, excludeMeetingId);
```

SQL（`server/meeting-service/src/main/resources/mapper/MeetingMapper.xml`）：

```sql
SELECT ...
FROM meeting m
WHERE m.room_id = #{roomId}
  AND m.status IN (20, 30)          -- 只有已预约/进行中占时段
  AND m.start_time < #{scanEnd}
  AND m.end_time > #{scanStart}
```

- `status IN (20,30)`：待审批（10）不占时段
- 半开区间条件：`start < scanEnd AND end > scanStart`
- `excludeMeetingId`：改期时排除自己
- 索引：`room_id + start_time/end_time` 走 range

#### 4.1.4 创建预约

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/MeetingReserveService.java`

`create` 分两段：

```text
第一段（无锁，快速失败）：
  loadAvailableRoom → select strategy → validateTime → initialStatus

第二段（锁内 + 事务）：
  executeWithLock(roomId, start, end)
    → transactionTemplate.execute
      → meetingRoomMapper.selectIdForUpdate(roomId)   // 数据库兜底
      → ensureRoomNotMaintenanceAfterLock             // 锁外快检可能过期
      → checkConflict(roomId, start, end, null)        // 锁内检测
      → insertMeeting + saveParticipants
      → stateMachine.logCreation
      → eventPublisher.publishEvent(created)
```

注意：事件在事务内发布，但由 `AFTER_COMMIT` 监听器在提交后转投 MQ；回滚则不发（Day 4 展开）。

#### 4.1.5 可用性与日历

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/AvailabilityService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MeetingQueryService.java`

- `AvailabilityService.check`：返回 `available` + 冲突详情
- `AvailabilityService.calendar` / `calendars`：按周返回房间会议，带缓存
- `MeetingQueryService.page` / `detail`：列表分页、详情 + 参会人 + 状态时间线

### 4.2 前端：日历与预约表单

#### 4.2.1 日历页

文件：`web/src/pages/Calendar.tsx`

- `useAppRequest<WeekPayload>` 拉周数据，`refreshDeps` 跟周切换
- `CalendarGrid` 负责渲染
- 点击空白格子 → `EmptySlot` → 打开 `BookingDrawer`

#### 4.2.2 日历几何

文件：`web/src/components/calendarGeometry.ts`

纯函数，不依赖 React/DOM：

- `DAY_START_MINUTES=480`（08:00）、`DAY_END_MINUTES=1200`（20:00）
- `SNAP_MINUTES=30`：点击吸附粒度
- `slotFromClickRatio`：把点击比例换成预填时段，上限 19:30，避免 `start=end=20:00`
- `blockGeometry`：会议块 `top/height` 百分比，跨天会议裁到窗口末端
- `pastRatio`：今天已过去比例，给过去时段盖灰罩

#### 4.2.3 重叠布局

文件：`web/src/components/calendarLayout.ts`

`layoutMeetings`：

1. 按开始时间排序，把时间上连通的会议聚成一簇
2. 簇内贪心：放进最早空出来的泳道（`laneEnd <= start`，相邻不算重叠）
3. 簇内所有会议按 `lane/lanes` 均分宽度

纯函数，方便单测“相邻不算重叠”“不同簇互不挤压”。

#### 4.2.4 预约表单

文件：
- `web/src/pages/BookingDrawer.tsx`
- `web/src/pages/bookingSchema.ts`

`bookingSchema` 用 zod：

- `title` 必填、最长 100
- `roomId` 必填
- `timeRange` 是 `[Dayjs, Dayjs]`，同时处理 `null`（rc-picker 清空）和非法元素
- `end > start`、不能预约过去、08:00–20:00、不能跨天
- 周期会议：`repeatWeekly` + `repeatEndDate`
- 表单校验只挡“明显不合法”；“最多提前 N 天”由服务端按策略判定

`BookingDrawer` 用 RHF + zodResolver；提交走 `useAppRequest` 的 `runAsync`，失败由统一 toast 处理；幂等由后端 `@Idempotent` 兜底。

#### 4.2.5 会议列表

文件：`web/src/pages/MeetingList.tsx`

- 筛选：状态/会议室/日期
- 分页
- 操作列按状态给编辑/取消/详情
- 时间格式统一 `YYYY-MM-DD HH:mm`

### 4.3 📘 笔记引用

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§二.2 索引基础 / §二.3 SQL 优化
> 类型：A（项目直接对应）
> 笔记结论：联合索引要遵循最左前缀；`WHERE room_id AND start_time/end_time` 的 range 查询要能走索引。
> 项目落点：`MeetingMapper.xml` 的 `selectConflicts`；半开区间条件 `start < scanEnd AND end > scanStart`。
> 差异/提醒：项目没有为每条 SQL 做 EXPLAIN 截图，但索引设计和 `type=range` 是验收点。

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§五.2 事务基础 / §五.3 隔离级别
> 类型：A（项目直接对应）
> 笔记结论：事务保证一组操作原子性，但不等于互斥；并发改同一行仍要锁/版本。
> 项目落点：`MeetingReserveService.create` 的 `TransactionTemplate` + 房间行锁。
> 差异/提醒：项目用编程式事务 `TransactionTemplate`，因为锁必须包住事务，不能直接用 `@Transactional` 包锁。

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

---

## 5. 手敲练习：半开区间冲突函数

### 5.1 任务

在 `server/meeting-service/src/test/java/com/smt/meeting/service/` 下新建一个临时测试类（学习分支，不提交 main）：

```java
package com.smt.meeting.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class ConflictIntervalPracticeTest {

    /** [aStart, aEnd) 与 [bStart, bEnd) 是否重叠 */
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
        // 已有 10:00-11:00，buffer=15 → 扫描区间 09:45-11:15
        // 新增 11:10-11:30 与扫描区间重叠 → 冲突
        assertTrue(overlaps(t(9, 45), t(11, 15), t(11, 10), t(11, 30)));
        // 新增 11:15-11:30 与扫描区间相邻 → 不冲突
        assertFalse(overlaps(t(9, 45), t(11, 15), t(11, 15), t(11, 30)));
    }
}
```

运行：

```bash
cd server
mvn -pl meeting-service -Dtest=ConflictIntervalPracticeTest test
```

验收：

- 5 个测试全部通过
- 能解释每个用例为什么冲突/不冲突
- 能说出 buffer 为什么是“扩展扫描区间”而不是“扩展已有区间”

### 5.2 进阶（可选）

把 `overlaps` 换成“分钟数 + buffer”的版本，验证 `[10:00,11:00)` 与 `[11:00,12:00)` 在 buffer=0 时不冲突、buffer=15 时冲突。

---

## 6. 五道自测

### 题目

1. 为什么用半开区间？闭区间会误判什么场景？
2. 冲突检测为什么必须在锁内？锁外检测会有什么问题？
3. 待审批会议为什么不占时段？通过时怎么防被抢？
4. 日历重叠会议怎么排布？相邻会议为什么不算重叠？
5. 预约表单校验和后端校验怎么分工？

### 答案

1. 半开区间 `[start,end)` 让相邻时段合法（11:00 结束、11:00 开始不冲突）；闭区间会把这种正常衔接判成冲突。
2. 锁外检测到落库之间，别的请求可能插入；锁内检测保证同一房间同一时段串行，结果可信。锁外只做快速失败。
3. 待审批不占时段，避免“待审批”把资源锁死；审批通过时必须复检 + 房间锁 + 会议室行锁，失败返回 50001。
4. 按时间连通聚簇，簇内贪心分配泳道，按 `lane/lanes` 均分宽度；相邻会议 `laneEnd <= start`，不算重叠。
5. 前端只挡明显不合法（必填、顺序、营业时间、过去时间）；“最多提前 N 天、是否需审批、冲突”由服务端按策略判定，前端展示错误。

---

## 7. 复盘卡

```text
今天最重要的 3 句话：
1. 半开区间让相邻时段合法，冲突检测必须锁内做。
2. 状态机 = 迁移表 + CAS + 日志，三者同一事务。
3. 日历布局是“连通聚簇 + 贪心泳道”，纯函数可单测。

可以讲给面试官的 1 个点：
“待审批不占时段 + 审批通过复检”是资源利用率和一致性的取舍。
```
