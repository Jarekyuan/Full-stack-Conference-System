# M5 + T6 代码评审记录

> **评审范围**：`git diff 634f015..f6cb9b3 -- server/ web/ scripts/ docs/ db/`，共 8 个从未评审过的提交：
> T5.1 `6f44968`、T5.2 `067651b`、T5.3 `f391955`、T5.4 `3194cf4`、T1.20 `89ccfdb`、
> T6.1 `d4b0e60`、T6.2 `e50be5b`、T6.3 `f6cb9b3`（106 文件，+7972/−573）。
>
> **此前已评审**：M1（review-M1）、M2（review-M2 + 四批修复）、M3（独立 code-reviewer，记录在 2026-10-15 日志）、
> M4（review-M4 + 两批修复）。**本轮是 M5 与 T6 的首次全量评审。**
>
> **方法**：多路只读评审（周期会议 / 统计与组织用户 / 日历与状态后果 / 前端列表通知与组织页 /
> 测试脚本与文档），前端日历与主题部分由我本人独立复核收口；所有结论逐条读代码**复现或推翻**后再收录。
> 关键指控用只读 API、`EXPLAIN`、Nacos 配置读取做了实测。评审分身的结论只当线索，不当结论。
>
> **时间**：2026-10-17

## 结论速览

| 分级 | 数量 | 说明 |
|---|---|---|
| Critical | 3 | 当天日期无法在 RangePicker 改时间（静默保留旧值）；周期取消可取消“已开始/进行中”的会议；验证脚本把限流永久留在关闭态（评审时 Nacos 实测为 0，已恢复） |
| Required | 16 | 错误时长、缓存失效不全、500、越权规则缺口、错实体写入、错颜色、暗色遮罩不可见、选人竞态、测试假绿等 |
| Optional | 25 | 体验/健壮性/可维护性；多数不阻塞合并 |
| Nit | 15 | 注释、文档、脚本命名等“与事实不符” |

**结论：不建议直接合并/上线。** 先修 Critical 3 条与 Required 中的 1~11、15、16 条；
测试类 Required 可与修复同批补。Optional/Nit 可排期。

> 本轮**没有发现**：SQL 注入（全 `#{}`）、通知越权读写、JWT 伪造、日历缓存跨用户串数据、
> 周期创建非原子落库、锁序死锁、密码/密钥硬编码。这些模块的主体实现是可靠的。

### 修复状态（2026-10-17 第一批，已实施）

| 项 | 状态 | 修复内容 |
|---|---|---|
| C1 | ✅ 已修 | `RecurringService` 取消按时间三分类（未来/进行中/历史），锁内重读后再分类；新增 10 例纯函数单测 |
| C2 | ✅ 已修 | 4 个脚本先装 `cleanup` trap 再 `ratelimit_disable_for_script`，结束时显式 `ratelimit_restore`；公共库增加**恢复读回确认**并拒绝把“已经是 0”当基线（避免 A 脚本没恢复、B 脚本备份 0 的自我延续）；Nacos 恢复为 10 |
| C3 | ✅ 已修 | 新增 `datePickerLimits`（不返回 `disabledSeconds`，当前分钟也禁用），两个 Drawer 共用；6 例单测 + `e2e-booking-today.cjs`（Playwright clock 固定今天 10:00，断言新值保留且 availability 参数同步） |
| R1 | ✅ 已修 | DTO `@Max(1439)`；`expand()` 改用 `LocalDateTime.plusMinutes` 并校验不跨天；脚本补 1500 分钟与 23:30+60 两条例 |
| R2 | ✅ 已修 | `CalendarCacheEvictor` 逐周删除；创建/取消的 evict 移到事务提交后；脚本补首/中/末三周缓存断言 |
| R3 | ✅ 已修 | 系列取消加 `@Valid`；脚本补超长 reason → 10001 且 3 场仍为 20 |
| R4 | ✅ 已修 | 新增 `ensureCancelDeadline`（与单场同一 R-6 规则）；3 例单测覆盖命中/未命中/无策略 |
| R5 | ✅ 已修 | 系列创建发**一条汇总 CREATED 事件**（第一场为载体）；脚本断言每个审批人收到一条 APPROVAL_TODO，不是逐场 N 条 |
| R15 | ✅ 已修 | 暗色过去遮罩改为 `slate-950/80`；e2e-theme 增加计算样式断言 |
| R16 | ✅ 已修 | `selectDept` 切换前 `cancelDebouncedSearch()`；e2e-calendar-enhanced 增加竞态断言 |

**验证（本批）**：后端 **110 例**全绿；`verify-m5-recurring.sh` **41/41（连跑两次）**、
`verify-m5-recurring-cancel.sh` **36/36（连跑两次）**、`verify-calendar-optimize.sh` **24/24**、
`verify-m5-stats.sh` **23/23**、`verify-m4-notify-api.sh` **43/43**；
`e2e-theme` **15/15（两次）**、`e2e-calendar-enhanced` **26/26（两次）**、
`e2e-booking` **19/19**、`e2e-booking-today` **4/4（两次）**、
`e2e-meeting-list` **21/21**、`e2e-calendar` **14/14**；前端 `npm run check` **59 例**通过。
四个端口 8080/8081/8082/8083 均在监听。
（`verify-m4-notify-api.sh` 的 cleanup 会停掉 8083，这是该脚本的既定行为；本轮跑完后已重启通知服务。）

### 修复状态（2026-10-17 第二批，已实施）

| 项 | 状态 | 修复内容 |
|---|---|---|
| R6 | ✅ 已修 | `Participants.userIds` 上限 100、元素非空/正数；`MeetingReserveService.saveParticipants` 查 `sys_user` 校验存在且启用，停用/不存在 → 10001；同时 `RecipientMapper.selectParticipantIds` 只取启用账号——停用参会人保留历史但不再收 MEETING_UPDATED/取消/提醒；脚本补停用/幽灵参会人 + `onUpdated` 双人对照断言 |
| R7 | ✅ 已修 | 新增 `LAST_ADMIN_PROTECTED(20007)`；降权/停用最后一名启用 ADMIN 时先 `SELECT ... FOR UPDATE` 锁 ADMIN 角色行再 count；脚本用“降权后的旧 token 停用唯一管理员”验证 |
| R8 | ✅ 已修 | H-1 增加 `selectDeletedRoomUsage`（`meeting` LEFT JOIN room，`r.id IS NULL`），已删除房间以 `已删除会议室#id` 出现在 items；脚本补删房后历史仍计入 |
| R9 | ✅ 已修 | `calendarCategory` 的 `expired` 增加 `status === 40`；单测与 calendar e2e 补“40 但 endTime 未来”场景；顺手修正 MeetingMapper/MeetingReserveService 的过时注释（N1） |
| R10 | ✅ 已修 | `MeetingEditDrawer` 打开时 `mutate(undefined)` 清旧 detail，渲染/提交都用 `detail.id === meetingId` 守卫；加载失败显示“重试”，Save 禁用；e2e 补“编辑 A 后改开 C，表单必须是 C” |
| R11 | ✅ 已修 | 前端 `chunkRoomIds` 按 50 分片并发，合并 items/failedRoomIds；6 例单测覆盖 0/50/51/120；同步修正 Calendar.tsx 的旧批量注释（N2） |

**验证（第二批）**：后端 **114 例**全绿；`verify-state-consequences.sh` **34/34（两次）**、
`verify-m5-stats.sh` **27/27（两次）**、`e2e-calendar-enhanced` **28/28（两次）**、
`e2e-meeting-list` **22/22（两次）**、`npm run check` **65 例**；
回归 `verify-m5-recurring` **41/41**、`verify-m5-recurring-cancel` **36/36**、
`verify-calendar-optimize` **24/24**、`e2e-booking` **19/19**。

### 修复状态（2026-10-17 第三批，已实施）

| 项 | 状态 | 修复内容 |
|---|---|---|
| R12 | ✅ 已修 | 新增 `MaintenanceRecheckTest` 3 例：mock 房间 `selectById` 第一次可用、第二次维护，精确命中创建/改期/周期创建的**锁内复查**（30002 且不落库） |
| R13 | ✅ 已修 | `verify-m4-notify-api.sh` 批量删除混入 U2 的通知 id，断言 `deleted=2`、U2 行仍在 |
| R14.1 | ✅ 已修 | H-2 改为“先取基线、断言增量”（3 场/195 分钟/45 分钟），不再被历史数据凑数 |
| R14.2 | ✅ 已修 | 周期间隔断言改为 `GROUP_CONCAT(DATEDIFF(...))='0,7,14'` |
| R14.3 | ✅ 已修 | dashboard e2e 在 UI 里选“今天~今天”，`waitForResponse` + 汇总行与窄区间接口比对 |
| R14.4 | ✅ 已修 | 缓存隔离两个方向都先断言 `EXISTS cache:calendar:...=1` 再查第二个用户 |
| R14.5 | ✅ 已修 | 删除全部前先造一条未读并断言角标 1，再删并断言 0（不再恒真） |
| R14.6 | ✅ 已修 | dashboard 清理只删 `E2E周系列-%` 规则，并清 `cache:calendar:1:{week}` |
| R14.7 | ✅ 已修 | delete-all 改用一次性用户（创建→预约→通知→全删→删用户），共享 user1 收件箱不再被清空 |
| R14.8 | ✅ 已修 | 批量接口补 >50/空/非正 id 断言、单房间与批量会议集合对照；新增 `AvailabilityServiceTest` 覆盖单房间失败隔离与去重 |
| R14.9 | ✅ 已修 | theme e2e 阻断 `src/main.tsx`，断言 React 挂载前 `html.dark` 已存在 |
| R14.10 | ✅ 已修 | 非组织人权限改用 status=20 会议验证；e2e 补“编辑 A 后改开 C，表单是 C” |
| R14.11 | ✅ 已修 | org e2e 补“自己的 Switch 禁用”“有子部门时删除被拒” |
| R14.12 | ✅ 已修 | 停用用户搜索改用唯一 username，前置断言启用时可搜到 |

**Optional / Nit 本轮处理**：
- 已修：O1（取消锁内重读）、O2（冲突检测策略只查一次）、O3（批量空/非正/去重后 50）、
  O4（加载失败房间禁点 + 行标红）、O5（选人搜索文案）、O6（周期开关按 allowRecurring 禁用）、
  O7（重复结束日期上限相对今天）、O8（周期会议不支持参会人/备注的提示）、O9（详情切换不闪错误）、
  O10（会议列表页码回退）、O11（编辑自己时 ADMIN 选项禁用）、O12（WS 通知触发通知中心列表刷新）、
  O13（看板/通知/组织页错误态 + 重试）、O14（OrgUsers 拆成 `DeptFormModal` / `UserFormModal`，847→约 480 行）、
  O17（keyword/roleIds/统计区间上限）、O20（房间删除后锁内复查抛 30001）、
  O21（铃铛/部门操作 aria-label + 会议列表行/通知条目 Enter/Space 进入）、O22（dashboard 清理窗口）、
  O24（选人部门人数“含停用”说明）、O25（重复结束日期禁同日）。
- N1~N15 全部处理（N8 为历史快照数字，当前已写成 120 例）。

**明确不改（低风险，已记录）**：
- **O16** 部门删除与用户绑定是 check-then-act、树环数据会被丢弃：父级不可变，环只可能由手工改库产生；
  并发窗口影响低，未加锁。
- **O18** 用户资料/角色更新 last-write-wins：管理端低频；加 version 需要改表/契约，暂不做。

**验证（第三批，汇总）**：后端 **118 例**全绿；`verify-state-consequences` **39/39**、
`verify-m5-stats` **28/28**、`verify-calendar-optimize` **30/30**、`verify-m5-recurring` **41/41**、
`verify-m5-recurring-cancel` **36/36**、`verify-m4-notify-api` **46/46**；
`e2e-theme` **16/16**、`e2e-dashboard` **12/12**、`e2e-notify-center` **14/14**、
`e2e-org-users` **22/22**、`e2e-calendar-enhanced` **28/28**、`e2e-meeting-list` **22/22**、
`e2e-booking` **19/19**；`npm run check` **65 例**、`npm run build` 通过。

### 修复状态（2026-10-18 产品口径批，已实施）

| 项 | 状态 | 修复内容 |
|---|---|---|
| O23 | ✅ 已修 | 定义可预约窗口 **08:00–20:00、不可跨天**（docs/01 R-16）：后端 `validateTime` 拒绝窗口外/跨天；延长上限从 24:00 收到 20:00；前端 `datePickerLimits` 统一遮挡 + zod `businessHoursViolation`；统计脚本的跨天数据改为 SQL 造历史数据，继续验证裁剪 SQL |
| O15 | ✅ 已修 | ADMIN 代取消：ADMIN 可取消任何 10/20 会议，**原因必填**、**不受 R-6 限制**，走状态机/审计；`MEETING_CANCELLED` 在 operator≠organiser 时通知组织人；会议列表给 ADMIN 增加“全部会议”范围 + “代取消”，弹窗原因必填；详情页同步 |
| O19 | ✅ 已修 | `extend()` 在房间行锁/会议行锁之后复查维护状态（30002）；`MaintenanceRecheckTest` 覆盖；维护中不再允许延长 |

**验证（2026-10-18）**：后端 **120 例**全绿；`verify-state-consequences` **49/49**、
`verify-m5-stats` **28/28**、`verify-calendar-optimize` **30/30**、`verify-m5-recurring` **41/41**、
`verify-m5-recurring-cancel` **36/36**、`verify-m4-notify-api` **46/46**；
`e2e-theme` **16/16**、`e2e-dashboard` **12/12**、`e2e-notify-center` **14/14**、
`e2e-org-users` **22/22**、`e2e-calendar-enhanced` **28/28**、`e2e-meeting-list` **25/25**、
`e2e-booking` **19/19**；`npm run check` **68 例**、`npm run build` 通过。

**仍未修**：仅 O16/O18 两条低风险项，见上方“明确不改”。

---

## Critical

### C1 系列取消用 `status` 冒充“未开始”，可取消已开始/进行中/早已过期的待审批会议

- **位置**：`server/meeting-service/src/main/java/com/smt/meeting/service/RecurringService.java:184-195`
- **问题**：`cancellable` 只看 `status ∈ {10,20}`，`ongoing` 只看 `status=30`，完全不看 `start_time`；
  注释“未开始 = 还能取消的（10/20）”与事实不符。
- **失败场景**：
  1. 会议 20:00 开始，20:00:30 时状态仍是 20（状态任务默认 **60 秒一轮**，
     `MeetingStatusTask.java:62-64`；任务停摆时窗口无限长）→ 系列取消把它 20→50，
    **一场正在进行的会被“取消”**。
  2. `status=10` 且时间已过的“卡死待审批”会议（对账只告警不自动收尾，`MeetingStatusTask.java:106-126`）
     会被系列取消 10→50；它属于历史场次，按 docs/05 T5.2 应计入 `skipped`。
- **违反**：docs/05 T5.2 验收“只取消未来场次 / 历史场次不受影响 / 进行中拦截”；
  docs/04 §4.9“`skipped` = 已结束/已开始的场次”。
- **建议**：锁内按时间三分类——`start > now` 才是未来；`start <= now < end` 或 `status=30` 视为进行中，
  整系列拒绝；`end <= now` 计入 skipped。分类必须在锁内重新读取后做（见 O1）。
- **置信度**：高（代码 + 任务间隔已核对）。

### C2 验证脚本覆盖限流恢复钩子，跑完把限流永久留在关闭态——评审时 Nacos 实测 `reserve-per-minute: 0`（已恢复）

- **位置**：`scripts/verify-m5-recurring.sh:25-26,48`、`scripts/verify-m5-recurring-cancel.sh:25-26,47`、
  `scripts/verify-m5-stats.sh:23-24,41`、`scripts/verify-m4-notify-api.sh:24-25,52`；
  公共库 `scripts/lib/ratelimit.sh:48-56`
- **问题**：`ratelimit_disable_for_script` 先安装 `trap "…; ratelimit_restore" EXIT INT TERM`，
  脚本随后又执行 `trap cleanup EXIT`，**后设置的 EXIT trap 会覆盖前者**（bash trap 是替换不是追加）。
  `ratelimit_restore` 只在 INT/TERM 时执行，正常结束不执行。
- **实测**：评审时 `curl http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=meeting-service.yml&group=DEFAULT_GROUP`
  返回 `reserve-per-minute: 0`；**评审结束前已手工恢复为仓库默认值 10 并读回确认**。
  （默认值见 `RateLimitProperties.java:23`。）
- **失败场景**：脚本正常跑完 → Nacos 永久停留在 0 → 后续 e2e 的 `disableRateLimit()` 把 0 当基线再“恢复”成 0，
  形成自我延续；`verify-m4-ratelimit.sh` 的前提被破坏，所有 10005 回归静默消失。
  这正是 CLAUDE.md 踩坑 #9/#14 说的“脚本把系统跑坏”。
- **建议**：脚本顶部先 `trap cleanup EXIT`，再 `source` + `ratelimit_disable_for_script`（参考
  `verify-state-consequences.sh:97-100` 的正确顺序）；或在 `cleanup` 里显式调用 `ratelimit_restore`。
  修复后把 Nacos 阈值恢复为 10，并复跑 `verify-m4-ratelimit.sh`。
- **置信度**：高（最小 bash 脚本已复核 + Nacos 只读实测）。

### C3 当天日期在 RangePicker 中无法改时间：显示新值后静默回退，提交仍是旧时段

- **位置**：`web/src/pages/BookingDrawer.tsx:299-308`（核心是 306 行 `disabledSeconds: () => [0..59]`）；
  `web/src/components/MeetingEditDrawer.tsx:173-181`（180 行同样问题）
- **问题**：`disabledTime` 对“今天”把**全部 60 个秒值**都标为禁用。rc-picker 的 `useInvalidate` 会校验
  选中时刻的秒（即使 `showTime.format` 只到分钟、秒列不展示），0 秒也在禁用列表里，
  于是今天任何值都被判为 invalid；`useRangeValue.triggerSubmit` 校验失败后不会调用 `onChange`，
  RHF 表单值不更新。
- **实测**（Playwright，只读，POST 被 abort 未落库）：打开下周一 18:00–19:00 的预约抽屉，在 RangePicker 里
  输入今天 22:00 并按 Enter，输入框**显示** `2026-10-17 22:00`；点“确定”后回退为 `2026-10-19 18:00`，
  没有发出任何新的 `/rooms/availability` 请求。用户若没注意到回退，点“提交预约”会订到旧时段；
  编辑会议同理会静默保留旧时间。
- **影响**：所有“当天预约/当天改期”的用户路径不可用（当前时间 21:53，今天的 22:00 仍在未来，
  所以不是被“过去时间”规则挡住，而是被 `disabledSeconds` 误伤）。该问题由 T6.1 引入。
- **建议**：删除 `disabledSeconds`（或返回 `[]`）；若要挡住“当前分钟”，把 `disabledMinutes` 改为包含
  `now.minute()`（当前实现是 `length: now.minute()`，不含当前分钟）。两个 Drawer 同改；
  补一条“今天改时间后 availability 请求参数同步变化”的 e2e（现有 e2e-booking 已改用下一周，覆盖不到）。
- **置信度**：高（rc-picker 源码 + Playwright 实测）。

---

## Required

### R1 `durationMinutes ≥ 1440` 绕过跨天检查，静默创建错误时长的会议

- **位置**：`RecurringService.java:240-245,252-253`；`RecurringCreateDTO.java:40-42`
- **问题**：时长用 `LocalTime.plusMinutes` 计算并按 24h 取模；DTO 只有 `@Min(1)`，没有上限。
  `endTime.isBefore(startTime)` 只能拦住“取模后早于开始”的情况，拦不住“取模后晚于开始”。
- **失败场景**：room 2（`max_duration=240`），`startMinutes=600, durationMinutes=1500`
  → `endTime=11:00`（同一天），`validateTime` 看到 60 分钟 → 成功创建 **10:00–11:00**，
  请求写的是 25 小时；`durationMinutes=1441` 得到 1 分钟会议。前端 RangePicker 允许跨天选择，
  正常用户也能触发。
- **建议**：用 `LocalDateTime.of(d, startTime).plusMinutes(duration)` 计算并校验
  `end.toLocalDate().equals(d)`；DTO 补 `@Max(1439)` 或 `startMinutes + durationMinutes < 1440`。
- **置信度**：高。

### R2 系列创建/取消的日历缓存只清首末两周，且失效发生在事务提交前

- **位置**：`RecurringService.java:143-146`（创建）、`:216-219`（取消）；`CalendarCacheEvictor.java:41-45`
- **问题**：`evict(start,end)` 只删 `startWeek` 与 `endWeek` 两个 key；系列隔周一场、跨 3 周以上时，
  **中间周缓存不会被清**。且调用位于 `transactionTemplate.execute` 内部，Redis 删除发生在 DB 提交之前。
- **失败场景**：先访问 W1/W2/W3 三周（缓存烘热），再创建覆盖三周的系列 → W1/W3 被删，W2 仍返回旧数据，
  最长约 6 分钟；evict 之后、commit 之前若有并发日历请求，会把未提交前的旧数据重新写回缓存，提交后仍是旧值。
  `scripts/verify-m5-recurring.sh:189-199` 只核对第一周，恰好掩盖了中间周问题。
- **建议**：按周区间逐周 DEL（或每个 occurrence 各删一次）；把 evict 移到事务返回之后（锁释放前），
  与 `AvailabilityService` 的 AFTER_COMMIT 语义一致；脚本补中间周断言。
- **置信度**：高。

### R3 系列取消请求体缺 `@Valid`，超长 reason 打 500 并整系列回滚

- **位置**：`server/meeting-service/src/main/java/com/smt/meeting/controller/MeetingController.java:112-116`；
  `MeetingMapper.xml:374-377`；`db/01-schema.sql:173`
- **问题**：单场 cancel 有 `@Valid`，系列 cancel 漏了；`MeetingCancelDTO.reason` 的 `@Size(max=200)` 不生效，
  而 `updateCancelInfo` 直接写 `VARCHAR(200)`。
- **失败场景**：`{"reason":"<250 字>"}` → MySQL STRICT 模式 `1406 Data too long` → 全局 500，
  整个系列回滚；单场路径返回 10001。
- **建议**：加 `@Valid`；同时在 `updateCancelInfo` 这个列宽所有者处截断（踩坑 #10 的既定做法）。
- **置信度**：高。

### R4 系列取消没有执行 R-6 取消截止

- **位置**：`RecurringService.java:174-225`（无策略查询）；对照 `MeetingReserveService.java:120-128`
- **问题**：单场 cancel 会查 `cancel_deadline_minutes` 并返回 30005；系列 cancel 完全没有这一步。
- **失败场景**：room 3/5（`need_approval=1, cancel_deadline=30`，`db/02-seed.sql:102-108`）的某场
  10 分钟后开始，组织人调系列取消 → 单场会 30005，系列却成功取消。
- **建议**：读一次策略，对每个候选场次检查 `now > start - deadline`；整系列全有或全无，
  直接 30005 并指出具体场次；若产品决定跳过该场，需同步改 docs/04。
- **置信度**：高（代码事实）；产品口径中高。

### R5 系列创建完全不发领域事件，需审批的系列不会产生 APPROVAL_TODO

- **位置**：`RecurringService.java:143-146`（create 事务内无 `publishEvent`）；
  `server/notification-service/.../NotificationService.java:87-97,153-156`
- **问题**：代码有意“不逐场发事件”以避免组织人收到 N 条 MEETING_CREATED，但同一事件也承载审批人通知；
  注释只论证了前者。
- **失败场景**：在 room 3/5 创建 3 场系列 → 3 条 status=10 进入审批中心，但审批人铃铛没有任何新通知；
  单场创建会发 APPROVAL_TODO。实时通知链路出现特例。
- **建议**：待审批系列至少发一次（或每场）CREATED 事件，或新增“系列创建”事件/字段以区分
  “不通知组织人、但要通知审批人”；若确实有意不发，必须在 docs/04 §4.9 写明这是例外。
- **置信度**：代码事实高；产品是否有意中。

### R6 参会人 ID 完全未校验：停用/不存在/null 都能写进会议

- **位置**：`MeetingReserveService.java:493-508`；`docs/04-接口设计.md:208-213`
- **问题**：`saveParticipants` 直接 `distinct → batchInsert`，不校验 id 存在、状态、null；
  `Participants.userIds` 也没有长度上限。`/users/search` 过滤 `status=1` 只是隐藏了入口。
- **失败场景**：`POST /api/meetings` 传 `participants.userIds:[<停用用户id>]` 返回 code=0；
  `meeting_participant` 落库后，通知服务的 `selectParticipantIds` 不带 status 过滤
  （`RecipientMapper.xml:9-13`），停用账号仍会收到会议变更/提醒/取消通知。
  不存在的 id 形成悬空参会人；`[null]` 触发 `user_id NOT NULL` → 500；userIds 无上限可制造大量行与通知。
- **建议**：创建前通过 `/internal/users/batch` 校验（需扩展返回 status，或新增“启用用户 id 集合”查询），
  拒绝不存在/停用 id；`@Size(max=100)` + 元素 `@NotNull`。若产品口径只要求选人列表不展示，
  请把 docs/04 的“也不应被拉进新会议”改为该范围并记录 auth 降级时的取舍。
- **置信度**：高（代码、表结构、收件人 SQL 均核对）。

### R7 “最后一名启用 ADMIN”不变式没有数据库层保证

- **位置**：`server/auth-service/.../UserService.java:117-160,208-217`；
  `server/gateway/.../JwtAuthFilter.java:89-97`
- **问题**：`20006` 只阻止“操作自己”，没有在校验操作后是否还剩启用 ADMIN。
- **失败场景**：
  1. A、B 两个 ADMIN 同时互相提交 `PUT /users/{对方}` 且 roleIds 不含 ADMIN：两个事务锁不同行，
     双双提交后系统无任何 ADMIN，只能手工改库。
  2. A 先停用/降权 B；B 的 accessToken 在 30 分钟内仍携带 ADMIN（网关只验签、不查库），
     B 可在这个窗口内停用/降权 A，同样锁死。
- **建议**：在 update/updateStatus 事务内增加“剩余启用 ADMIN 数”校验，并用行锁/统一 guard 锁串行化
  该不变式；为敏感管理接口补调用者 DB 状态/角色校验；补上述两个场景的测试。
- **置信度**：高。

### R8 H-1 统计从 `meeting_room` 出发，删除会议室让历史统计静默消失

- **位置**：`server/meeting-service/src/main/resources/mapper/StatsMapper.xml:27-33`；
  `RoomService.java:123-131`（只拦 10/20/30）
- **问题**：H-1 以 `meeting_room` 为左表，而 40/50/60 的历史会议不阻止会议室删除；
  删除后这些会议不再出现在任何 `items`，也不进 `totalMinutes`，而 H-2 仍按 `meeting` 统计 → 两口径不一致。
- **失败场景**：管理员删除一间已结束使用的会议室，再查它上个月的使用率：数字静默变小；
  同一查询在删除前后结果不同，无法复核。
- **建议**：二选一——① 禁止删除仍有历史会议引用的房间，或软删除保留名称；
  ② H-1 从 `meeting` 出发 LEFT JOIN room，对已删除房间用 `COALESCE(r.name, '已删除会议室#id')` 兜底。
  无论选哪种，把口径写进 docs/04 4.10 并在 `verify-m5-stats.sh` 补回归断言。
- **置信度**：高（行为确定）；口径未定义（中）。

### R9 提前结束（status=40、endTime 未到）的会议仍显示为“我预订/邀请我的”活跃块

- **位置**：`web/src/components/calendarAppearance.ts:62`；`server/.../MeetingMapper.xml:138`；
  `MeetingReserveService.java:335-359`（finish 不改 end_time）
- **问题**：T6.1 把 40 纳入日历查询，但 `expired` 只按 `endTime <= now` 判断；
  提前结束后 `endTime` 仍在未来，于是 10:00–11:00 的会在 10:10 提前结束后仍以蓝色/绿色显示到 11:00，
  与实际已释放的时段矛盾。docs/04 §5.1 写的是“`endTime` 已过（含 status=40）”。
  对应单测 `calendarAppearance.test.ts:71-75` 名称与断言也自相矛盾（名写“不误判为我的”，断言却是 `mine`）。
- **建议**：`expired = meeting.status === 40 || !dayjs(endTime).isAfter(now)`；同步修正单测名称/断言。
  后端排除 `status=40 AND end_time > now` 会违背“40 要展示”的文档，不建议。
- **置信度**：高。

### R10 编辑 Drawer 在切换会议后会拿上一场的 `detail` 提交

- **位置**：`web/src/components/MeetingEditDrawer.tsx:56-58,70-84,86-104,112-118`
- **问题**：ahooks `useRequest` 的 `run` 不会清空旧 `data`（成功才 setData，失败/new run 都不清）；
  `onSubmit` 用 `detail.id/version`，Save 只受 `saving` 控制，不校验 `detailLoading` 或 `detail.id === meetingId`。
- **失败场景**：打开 A 的编辑（详情已加载）→ 关闭 → 打开 B。B 的详情请求慢或失败时，`detail` 仍是 A；
  失败后 `detailLoading=false`，抽屉直接渲染 A 的表单。点保存 → `PUT /meetings/{A.id}`，带 A 的 version：
  A 未被并发修改时会成功并发出 A 的 MEETING_UPDATED，用户以为在编辑 B。
- **建议**：打开时清空 `detail`（`mutate(undefined)` / `onBefore`，或渲染用 `detail?.id === meetingId`）；
  Save 的 disabled 加 `detailLoading || detail?.id !== meetingId`；`onSubmit` 首行再判一次；
  失败时渲染错误 + 重试，不要落回旧表单。
- **置信度**：高（代码 + ahooks 源码确认）。

### R11 批量日历把全部房间一次性发给上限 50 的接口：第 51 个房间时整页加载失败

- **位置**：`web/src/pages/Calendar.tsx:51-66`；`server/.../AvailabilityController.java:47-55`
- **问题**：前端 `getRoomCalendars(rooms.map(r => r.id), weekStr)` 不分片；后端 `roomIds.size() > 50`
  直接 10001。T6.3 之前逐房间请求没有这个上限，因此这是新引入的规模回归。
- **失败场景**：管理员建到 51 间可用会议室后，日历页整个批量请求被拒，`payload` 为空，
  页面没有任何会议室/会议（只剩一条错误 toast）。
- **建议**：前端按 50 分片并发并在前端合并 `items/failedRoomIds`；或后端把上限与产品确认后提高，
  并写明“一次最多 50”是接口约束而非前端可忽略的错误。
- **置信度**：高。

### R12 T6.2 新加的“锁内复查维护状态”在创建/改期/周期创建上没有被真正验证

- **位置**：`scripts/verify-state-consequences.sh:150-206`；
  `MeetingReserveService.java:68-73,186-190`；`RecurringService.java:105`
- **问题**：脚本 ②③ 都是“先把房间置为维护，再发请求”，命中的是抢锁**之前**的 `loadAvailableRoom` 快检；
  把 `ensureRoomNotMaintenanceAfterLock` 整行删掉，②③ 仍然全绿；周期创建完全没有用例。
  只有审批通过的锁内分支有覆盖（脚本 ④ + 单测）。
- **建议**：补 `MeetingReserveService`/`RecurringService` 单测，用 Mockito 让
  `meetingRoomMapper.selectById` 第一次返回可用、第二次返回维护（`thenReturn(available, maintenance)`），
  断言 30002 且未落库；脚本补一条“维护房中创建周期会议 → 30002”。
- **置信度**：高。

### R13 批量删除“只删自己的”安全护栏没有测试

- **位置**：`scripts/verify-m4-notify-api.sh:231-258`；`NotificationMapper.xml:88-95`
- **问题**：批量删除只传了 U1 自己的 id；若把 `AND user_id = #{userId}` 删掉，测试仍然通过。
  单条删除测了别人的 id → 60001，但批量链路未覆盖。
- **建议**：批量 body 混入一条 U2 的通知 id，断言 `deleted` 只算自己的条数、U2 那行仍在。
- **置信度**：高。

### R14 多项验证断言会假绿/有副作用，需要与修复同批补

| # | 脚本 | 问题 | 修法 |
|---|---|---|---|
| R14.1 | `scripts/verify-m5-stats.sh:156-169` | H-2 用绝对阈值 `>= 3/195/240`，会被库中既有历史数据凑数；“参与别人的会”逻辑失效也可能通过 | 先取基线，断言增量 3 场/195 分钟（45 分钟只能来自参与关系），或用一次性用户 |
| R14.2 | `scripts/verify-m5-recurring.sh:115-116` | “每场间隔 7 天”只验证 3 个不同日期偏移，0/1/2 天也返回 3 | 断言 `GROUP_CONCAT(DATEDIFF(...))='0,7,14'` |
| R14.3 | `web/e2e/e2e-dashboard.cjs:107-120` | “切换区间”只点开日期框按 Escape，随后 Node 直接 fetch API；页面即使不响应 `refreshDeps` 也绿 | UI 选“今天~今天”，`waitForResponse` 断言请求参数与 `usage-summary` 同步变化 |
| R14.4 | `scripts/verify-calendar-optimize.sh:173-186` | 缓存隔离没断言第一次请求真的写入缓存；缓存完全没启用时也绿 | 两次请求之间断言 `EXISTS cache:calendar:1:$FUTURE_WEEK = 1`，反向同理 |
| R14.5 | `web/e2e/e2e-notify-center.cjs:196-200` | “全部删除后角标 0”是恒真：前一步“全部已读”已把角标清零 | 删除前造一条未读，先断言角标=1，再删并断言 0（用 REST 未读数核对） |
| R14.6 | `web/e2e/e2e-dashboard.cjs:200-227` | 清理 `DELETE meeting_recurring WHERE id NOT IN (...)` 会删全库孤儿规则；最后 GET 日历烤热缓存但未 DEL，留下幽灵会议 | 按本次唯一 title 查/删规则；finally 清 `cache:calendar:1:{weekStart}` |
| R14.7 | `scripts/verify-m4-notify-api.sh:231-261` | “全部删除”直接清空共享账号 user1 的历史通知，不可恢复 | 用一次性用户专用于删除测试，或只删本次 meeting_id 对应的通知 |
| R14.8 | `scripts/verify-calendar-optimize.sh:7,130-143,188-207`、`web/e2e/e2e-calendar-enhanced.cjs:121-127` | 批量接口只测 happy path，没有 >50、`failedRoomIds`、与单房间逐字段一致；脚本头部声称“取消/拒绝不在查询范围”，但只插了 40，没有 50/60 夹具 | 补 51 个 id → 10001；构造单房间失败断言其余正常；单/批量对照；插 50/60 断言日历查不到 |
| R14.9 | `web/e2e/e2e-theme.cjs:65-72` | 刷新后等 networkidle 再查 `html.dark`，无法证明内联脚本在 React 挂载前执行；删掉防闪烁脚本也绿 | 阻断主 bundle / early screenshot，断言 React 挂载前已带 dark；暗色 ECharts 至少断言图例颜色 |
| R14.10 | `web/e2e/e2e-meeting-list.cjs:205-215` | 非组织人权限用一场 status=10 的会议验证——组织人本来也没有编辑按钮，删掉 `organiserId===me` 也测不出；详情页编辑/取消、A→B 切换 Drawer 均无 e2e | 用 user1 组织的 status=20 会议换 user2 验证；补详情页编辑/取消与 A/B 切换用例 |
| R14.11 | `web/e2e/e2e-org-users.cjs:149-175` | 未测“自己的状态 Switch 禁用”和“有子部门时删除被拒”；`page.getByText('已停用')` 是全局文本匹配 | 定位 admin 自己那行断言 Switch disabled；对“总公司”删除并断言“存在子部门”；状态改用行内文本 + switch 状态 |
| R14.12 | `scripts/verify-state-consequences.sh:220-226` | 停用用户搜索排除用 `deptId=2` 断言；临时用户 id 最大，而 `searchBrief` 是 `ORDER BY id LIMIT 50`，研发部启用用户 ≥50 时会被截断，去掉 `status=1` 也可能通过 | 改用唯一 `keyword=t62_user_<stamp>`；先断言启用时能搜到，再断言停用后搜不到 |

### R15 暗色模式下“过去时段灰罩”不可见

- **位置**：`web/src/components/CalendarGrid.tsx:228`（遮罩 `dark:bg-slate-900/80`）、
  `:214`（整日灰底 `dark:bg-slate-950`）、`:335`（日历容器 `dark:bg-slate-900`）
- **问题**：遮罩颜色与容器底色相同（都是 `slate-900` / rgb(15,23,42)），叠加后没有任何视觉差异；
  整日已过去时叠加在 `slate-950` 上也几乎无法区分。
- **实测**：暗色下读取计算样式，遮罩 `rgba(15,23,42,0.8)`、容器 `rgb(15,23,42)`；
  `e2e-theme` 只断言遮罩元素存在，发现不了“存在但看不见”。
- **失败场景**：暗色用户看到今天已过去的小时与未来小时外观一致；点击没有反应（点击兜底拦截生效），
  用户不知道为什么。T6.1 的“过去时段置灰”在暗色下实际失效。
- **建议**：换成有对比的深色遮罩（如 `dark:bg-slate-950/80`、`dark:bg-black/40`），
  或给今天的过去区加边框/斜纹；在 e2e-theme 里加一条暗色日历的计算样式/截图像素断言。
- **置信度**：高（计算样式实测）。

### R16 部门树切换时未取消关键字防抖，旧部门结果覆盖新部门

- **位置**：`web/src/components/DeptUserPicker.tsx:61-67`（`debouncedSearch`）、`:98-105`（`selectDept` 直接 `runSearch`，未 cancel）
- **问题**：输入框 onChange 按“当时的 deptId”排 400ms 防抖；随后点击另一个部门会立即发新请求，
  但旧防抖计时器仍会触发，并且是最后一次 `runSearch`，按 ahooks“最后一次请求生效”规则覆盖 UI。
- **实测请求顺序**：默认研发部（deptId=2），输入“张”后立刻点产品部（deptId=3）：
  `deptId=2` → `keyword=张&deptId=3` → `keyword=张&deptId=2`；最终列表是研发部结果，
  而树高亮的是产品部。
- **建议**：解构 `useDebounceFn` 的 `cancel`，`selectDept` 先 `cancel()` 再 `runSearch`；
  或部门切换也走同一个防抖函数/统一 effect（最后参数生效）。补一条 e2e。
- **置信度**：高（请求顺序实测）。

---

## Optional（建议排期，不阻塞）

| # | 位置 | 问题 / 建议 |
|---|---|---|
| O1 | `RecurringService.java:182-207` | 系列取消在锁外读会议、锁内不复检；并发取消第二次会以 40002 失败，而不是文档承诺的 `cancelled=0`；定时任务把某场 20→30 也得到笼统 40002。锁内重读并重算三类集合。 |
| O2 | `RecurringService.java:275-291` | 冲突检测 N+1：每场一次 `findConflicts`，内部再查一次策略；60 场时在持锁状态做约 120 次查询。锁内读一次策略并按最小/最大日期一次范围查询后内存分组。 |
| O3 | `AvailabilityController.java:47-55` | 批量日历空 `roomIds` 返回 code=0 + 空数组；`-1` 返回一个“房间 -1”的空日历（已实测）；50 上限在去重前判断（51 个重复 id 被拒）。显式拒绝空列表/非正 id；先去重再判上限。 |
| O4 | `Calendar.tsx:169-178`；`CalendarGrid.tsx:367` | `failedRoomIds` 的房间仍渲染成可点击的空周（`daysByRoom.get(room.id) ?? EMPTY_DAYS`），用户可在加载失败的房间里点空格预约；后端冲突校验能兜底，但应禁用该行点击或显示明确的失败态。 |
| O5 | `DeptUserPicker.tsx:191,98-105` | 输入框写“可跨部门”，但选中部门后关键字与 deptId 取交集（后端契约如此）；用户在本部门搜不到其他部门的人。要么搜关键字时清掉 deptId，要么改文案。 |
| O6 | `BookingDrawer.tsx:334-352` | “每周重复”开关对所有房间都显示，`allowRecurring=0` 的房间要到提交才 40007；应按 `selectedRoom.allowRecurring` 禁用并提示。 |
| O7 | `BookingDrawer.tsx:351,363-368` | 重复结束日期上限按 `开始日 + advanceDays` 算，后端按 `今天 + advanceDays` 逐场校验；开始日越晚，UI 允许的结束日越会超限，提交时才报“第 N 场不合适”。上限改为 `dayjs().add(advanceDays,'day')`。 |
| O8 | `BookingDrawer.tsx:158-170` | 周期创建只发 `title/roomId/dayOfWeek/startMinutes/durationMinutes/startDate/endDate`，表单里填的参会人与备注被静默丢弃；应提示或禁用这两个字段。 |
| O9 | `MeetingDetail.tsx:122-135` | `/meetings/A → /meetings/B` 首帧 `data` 仍是 A、`loading` 尚未置 true，`detail` 被 id 守卫置空 → 先闪“会议不存在”。`if (!detail && (loading || (data && data.id !== meetingId)))` 显示 Spin。 |
| O10 | `MeetingList.tsx:198-214` | 取消/编辑导致 total 缩到当前页之外时没有 `maxPage` 回退（OrgUsers/NotificationList 有），会停在空页。 |
| O11 | `OrgUsers.tsx:324,427-431` | 编辑自己时只显示警告，ADMIN 选项仍可移除，提交后才 20006；同表单其他修改一起失败。`isSelf` 时固定保留 ADMIN。 |
| O12 | `NotificationList.tsx:55-58` | 页面不订阅 WS：停留在通知中心时新通知只更新角标，列表与“共 N 条”不刷新。可在 store 增加 `notificationTick` 并加入 `refreshDeps`（不要复用 `version`，避免 reload→setUnreadCount 自触发）。 |
| O13 | `Dashboard.tsx:193-210`、`NotificationList.tsx:236`、`OrgUsers.tsx:675-695` | 接口失败与“空数据”不可区分：失败只弹 toast，主体仍显示“暂无数据/还没有通知/暂无部门”。读取 `error` 渲染错误态 + 重试。 |
| O14 | `OrgUsers.tsx`（847 行） | 违反 web/AGENT.md“单文件超 300 行考虑拆分”。拆出 `DeptFormModal`、`UserFormModal`、`DeptTreePanel`/`DropdownActions`，页面只留查询与刷新编排。 |
| O15 | `UserService.java:138-160`；`MeetingReserveService.java:110-120` | 停用仍组织未来会议的用户后，这些会议继续占房，管理员没有代取消/转交入口。停用前检查并提示，或给 ADMIN 代取消/转交；至少写进 docs/05 T6.2。 |
| O16 | `DeptService.java:80-93`、`UserService.java:117-136`、`DeptService.java:29-50` | 部门删除与用户绑定是 check-then-act，可能留下悬空 dept_id；`tree()` 对 parent 成环的节点静默丢弃。加部门行锁/`FOR UPDATE`；树组装加 visited + WARN。 |
| O17 | `UserController.java:36-39`、`UserUpdateDTO.java:32-34`、`StatsService.java:47-59` | keyword 无长度上限、roleIds 无元素上限、统计日期无最大跨度；补 `@Size`/最大天数（如 366）。 |
| O18 | `UserService.java:117-136` | 用户资料/角色更新是 last-write-wins，无 version/行锁；并发编辑丢更新。沿用会议 version 或 `SELECT ... FOR UPDATE`。 |
| O19 | `MeetingReserveService.java:258-327` | 延长会扩大占用，但没有维护状态复查；房间在会议进行中被置维护后仍可延长。按产品口径决定是否补 `ensureRoomNotMaintenanceAfterLock` 并写文档。 |
| O20 | `MeetingReserveService.java:431-436` | 房间在快检后被删除时 `selectIdForUpdate` 返回 null 被忽略，helper 静默放行，随后插入悬空 room_id 的会议。`current == null` 时抛 30001。 |
| O21 | `MeetingList.tsx:204`、`NotificationList.tsx:252-254`、`OrgUsers.tsx:844`、`MainLayout.tsx:130-136` | 可点击的 Table 行/通知条目只有 onClick，无键盘支持；部门树 MoreHorizontal 与铃铛是纯图标，无可访问名称。补 `role/tabIndex/onKeyDown` 与 `aria-label`。 |
| O22 | `web/e2e/e2e-dashboard.cjs:171-196,210-227` | 系列通过 UI 创建，id 直到 194-195 行才登记到 `created`；提交成功但随后异常/中断时，finally 不知道这些 id，3 场会议会残留。finally 按唯一 title 兜底查删。 |
| O23 | `BookingDrawer.tsx:299-308`；`calendarGeometry.ts:69-86`；`MeetingReserveService.java:444-464` | 08:00–20:00 之外仍可创建会议（如未来日 21:00–23:00），但日历 `blockGeometry` 把两端都 clamp 到 20:00，块高 0、完全不可见；统计按每天 720 分钟算也会出现 >100% 的使用率。若 08–20 是营业时段，两个 RangePicker 禁用 0–7 点与 21–23 点并由后端兜底；若允许 24 小时，则日历需增加“窗口外会议”标记。 |
| O24 | `DeptUserPicker.tsx:36`；`SysDeptMapper.xml:39` | 树上的部门人数 `COUNT(*)` 包含停用用户，而右侧 `/users/search` 只列启用用户；会出现“研发部（3）”点进去只有 2 人。计数子查询加 `u.status=1`，或文案改成“总人数”。 |
| O25 | `BookingDrawer.tsx:364-369`；`bookingSchema.ts:53-56` | 周期结束日期 picker 允许选开始当天（`isBefore(startOfDay)`），zod 要求严格更晚（`isAfter(...,'day')`），提交时才报错；picker 改为 `!d.isAfter(start.startOf('day'))`。 |

---

## Nit（注释/文档/命名与事实不符）

| # | 位置 | 问题 |
|---|---|---|
| N1 | `MeetingMapper.java:57-61`、`MeetingReserveService.java:351` | 仍写“日历只展示 10/20/30”，T6.1 已改为 10/20/30/40。 |
| N2 | `Calendar.tsx:4-5,34` | 文件头仍写“对每个会议室并发取该周日历（Promise.all 扇出）”、字段注释“见下方 allSettled 说明”，T6.3 已改为单次批量请求。 |
| N3 | `RecurringService.java:87`；`LogAspect.java:93-104` | `RecurringCreateVO` 是 record，访问器为 `recurringId()`；`extractIdFromResult` 只找 `meetingId/getMeetingId/getId`，审计 target 永远记 `-`。 |
| N4 | `docs/04-接口设计.md:161-164` | 40007/40008 被放在 `**6xxxx 通知**` 标题下；应移到 40006 之后。 |
| N5 | `docs/03-数据库设计.md:292-297` | 声称 `idx_recurring` 让系列查询 `type=ref + Using index（覆盖索引）`；实测 `EXPLAIN` 只有 `ref`，Extra 为 NULL（查询选全部列，仍回表）。 |
| N6 | `docs/04-接口设计.md:850-855` | `totalMinutes: 540` 与唯一 item 的 180 不一致，`usageRate 0.0081` 也只对应 180；补省略号或改数字。 |
| N7 | `MeetingMapper.xml:60`、`RecurringConflictVO.java:12` | 注释“未来场次在前”“冲突 start_time 落在时间窗内（同一天）”与实际升序查询/半开区间重叠判定不符。 |
| N8 | `CLAUDE.md:65,80,97,114` | 进度写“后端单测 87/91/99 例”，静态 `@Test` 计数为 89/93/101；surefire 当前 99（`AuthFeignClientTest` 的 2 例不在报告里）。以一次 `mvn test` 汇总为准，并说明该 Feign 用例单独跑。 |
| N9 | `scripts/verify-calendar-optimize.sh:18`、`verify-state-consequences.sh:20`、`web/e2e/e2e-dashboard.cjs:45` | 脚本变量不统一：两个脚本读 `BASE`，其余读 `BASE_URL`；e2e-dashboard 硬编码 `redis123`，同批 e2e 支持 `REDIS_PASSWORD`。统一为 `BASE="${BASE_URL:-...}"` 与 `${process.env.REDIS_PASSWORD || ...}`。 |
| N10 | `StatsController.java:23` | 注释引用“docs/04 3.4”，该文档只有 3.1/3.2/3.3；统计权限在 3.2 与 4.10。 |
| N11 | `DeptSaveDTO.java:20-23` | `status` 没有 `@Min(0) @Max(1)`；直接调 API 可写 status=2，前端只按 0/1 解释。 |
| N12 | `web/src/api/notification.ts:36-37` | 导出的 `deleteNotification` 没有调用方（UI 只有勾选批量删除），接口回归无前端覆盖；要么补单条删除入口，要么删导出并在文档注明由批量覆盖。 |
| N13 | `web/e2e/e2e-notify-center.cjs:142-150` | 未读 ≥99 时 antd Badge 显示“99+”，脚本解析为 99，期望 `before+1` 永远等不到；期望值改 `Math.min(before+1, 99)` 或先清未读。 |
| N14 | `scripts/verify-m5-stats.sh:95-96` | 断言名写“全部启用会议室数”，SQL 实际是 `SELECT COUNT(*) FROM meeting_room`（含 status=1 维护中）；当前种子无维护房所以未暴露。 |
| N15 | `scripts/verify-state-consequences.sh:216-218` | “停用后 refresh → 20003”也会因 `AuthService.refresh` 先轮换再查 status 而通过，不能证明 `removeByUser` 真的执行；补 Redis 断言 `auth:refresh:{id}` 与反向摘要键都不存在。 |

---

## 评审中确认“没问题”的部分

- **并发与锁**：周期创建的 `executeWithLockOnDates` 用 TreeSet 升序取锁、finally 释放已持有锁；
  锁 ⊇ 事务、锁内先取会议室行锁；与单场共用 `lock:meeting:{roomId}:{date}`；未发现新的死锁面。
- **周期创建原子性**：规则 + N 场 + N 条状态日志在同一个 `TransactionTemplate` 内；
  任一冲突/校验失败整笔回滚；`insertBatch` 的 `useGeneratedKeys` 回填正确。
- **日历缓存**：key 仅 room+week；`invited` 在缓存读出后按用户装饰、绝不写回；空周 TTL 1 分钟；
  single-flight 200ms + 150ms 复查 + 异常降级回源；两个方向的用户隔离脚本都有断言。
- **通知删除**：单条/批量/全部 SQL 都带 `user_id`；批量去重；单条别人的/不存在统一 60001；
  返回实际删除数；未读数实时 COUNT，删除后天然一致。
- **权限**：`/users`、`/depts` 写接口、`/stats/room-usage` 均有 `@PreAuthorize`；
  `/users/search` 不含 email；通知不接受客户端 userId；会议编辑/取消后端二次校验归属与状态。
- **统计口径**：半开区间、`end` 含全天、状态 20/30/40、区间裁剪、除零、跨天会议、H-2 用 EXISTS
  避免重复，均与 docs/04 4.10 一致；`EXPLAIN` 命中 `idx_room_time` / `idx_status_start`。
- **暗色**：未发现 `dark:...dark:` 重复类；Tailwind `darkMode:'class'`、antd 算法、持久化键、
  防闪烁脚本的键和结构一致；ECharts 颜色随主题显式切换、背景透明。
- **类型/构建**：评审期间 `npm run tsc`、`npm run lint`、`npm run test`（53/53）通过；
  `git status` 干净；本次评审未修改业务代码。

---

## 建议的修复顺序

> 三批（C1-C3、R1-R16、Optional/Nit）已全部处理；剩余仅为“明确不改”的产品口径项与
> O21 的表格行键盘支持，均在上方记录。

1. ~~**C3 + C1 + R1 + R4 + R3 + R5**：当天 RangePicker、周期会议的状态/时间/校验/通知。~~ ✅
2. ~~**C2 + R14.7**：脚本限流恢复与共享账号数据安全；Nacos 阈值已恢复为 10。~~ ✅（C2）/待办（R14.7）
3. ~~**R2 + R9 + R11 + R15**：缓存失效、40 的颜色、批量日历 50 上限、暗色遮罩。~~ ✅
4. ~~**R6 + R7 + R8 + R16**：参会人校验、最后管理员不变式、统计删除房间口径、选人竞态。~~ ✅
5. **R10（✅）+ R12 + R13 + R14**：前端错实体写入已完成；剩余验证体系补强。
6. Optional/Nit 按排期清理；`OrgUsers` 拆分可单独一批。

> **给下一次评审的提醒**：本轮 Required 里有 8 条属于“测试看起来在验、实际验不到”
> （R12/R13/R14），与 M4 评审的 R5~R9 同一类。功能代码本身的质量在提升，
> 但**验证体系的可信度**仍是这个项目最薄弱的环节。
