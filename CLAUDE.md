# CLAUDE.md

> 本文件是给 AI 编程助手（Claude Code / Codex 等）的**项目入口指南**——Claude Code 启动时会自动加载它。
> 读完本文件 + 按「文档地图」按需深读对应文档，你就能接着往下开发。

---

## 1. 项目是什么

一个**会议室预约系统**，定位是**面试导向的全栈练手项目**（非生产系统）。核心解决两个工程问题：

1. **并发预约下的时段冲突控制**（分布式锁 + 幂等 + 乐观锁三层防护）
2. **会议状态生命周期的一致性**（状态机单一入口 + CAS + 审计日志）

- 使用人群：个人学习 + 面试演示
- 技术栈：
  - 前端：React 18 + TS + Vite + antd + Zustand + React Hook Form + zod
  - 后端：Java 17 + Spring Boot 3 + Spring Cloud（Gateway / Nacos / OpenFeign）+ MyBatis（**手写 XML**）+ Redisson
  - 中间件：MySQL 8 + Redis + RabbitMQ（M4 才接入）+ Nacos
- 微服务 4 个：
  - `gateway`（:8080）—— 统一入口 + JWT 鉴权 + 身份头注入
  - `auth-service`（:8081）—— 登录/双 token/用户/部门/角色
  - `meeting-service`（:8082）—— 会议/会议室/预约/审批（核心）
  - `notification-service`（:8083）—— 消费事件 → 站内信 → REST 查读 → WS 实时推送（T4.3~T4.6 已通）
- **日志**：各服务 `logging.pattern.level` 带 `[traceId:…]`；服务按约定路径启动时日志落在 `server/logs/`
  （`grep 'traceId:xxx' server/logs/*.log` 能一次串起一条请求的全程；网关也有访问日志行）

---

## 2. 文档地图（★ 动手前必读）

| 文档 | 内容 | 何时读 |
|------|------|--------|
| `docs/00-项目初心与目标.md` | **为什么做这个项目 + 三重目标 + 验收标尺**（防走偏） | 开工前 / 每个里程碑结束对照 |
| `docs/01-需求文档.md` | 需求 + 规则编号 R-1~R-14 + **v1.3 数据模型**（RBAC 五表/部门树/参会人简化） | 改业务规则前 |
| `docs/02-技术架构方案.md` | 技术选型 + 8 项决策 + 关键设计（双 token/分布式锁/索引/Feign） | 动架构前 |
| `docs/03-数据库设计.md` | **15 张表** DDL + 索引设计 + 面试决策点（为何无外键/为何半开区间） | 改表结构前 |
| `docs/04-接口设计.md` | 全部 API 契约 + **错误码表**（10000~60001）+ 身份头约定 | 写接口前 |
| `docs/05-开发计划.md` | **66 任务清单** + 每任务实现要点/验收标准 | 认领任务前 |
| `docs/07-项目理解指南.md` | M0+M1 理解指南（架构全景 + 4 条链路走读 + 8 个难点 + 10 个坑） | 理解已有代码 |
| `server/AI-RULES.md` | 后端编码规范（红线） | 写后端代码前 |
| `web/AGENT.md` | 前端规范 | 写前端代码前 |

---

## 3. 当前进度（2026-10-17 快照）

| 里程碑 | 状态 | 内容 |
|--------|------|------|
| M0 基础设施 | ✅ | Docker 4 容器 + 数据库 15 表 + Git |
| M1 骨架+认证+会议室 | ✅ | 24 任务：双 token 无感刷新、会议室 CRUD、策略、Feign、登录/布局、**T1.20 组织与用户** |
| M2 后端（预约核心） | ✅ **10/10** | 状态机 → 冲突检测 → 可用性/日历 → 创建预约 → 幂等 → Redisson 锁 → 并发脚本 → 列表详情 → 取消 → 修改（乐观锁） |
| M2 前端 | ✅ **3/3** | ✅ T2.F1 日历页（时间轴定位 + memo 行级组件化）｜✅ T2.F2 预约表单 Drawer（防抖冲突提示 + 幂等提交）｜✅ T2.F3 我的会议列表 + 详情（筛选分页 + 状态时间线 + 取消） |
| M3 审批+生命周期 | ✅ **8/8** | ✅ T3.1 待办/已办列表｜✅ T3.2 审批通过（复检+行锁）｜✅ T3.3 拒绝｜✅ T3.4 延长 + 提前结束｜✅ T3.5 定时流转｜✅ T3.6 对账补偿｜✅ T3.F1 审批中心（前端）｜✅ T3.F2 详情页延长/结束（前端） |
| M4 通知+MQ+打磨 | ✅ **12/12**（评审见 `docs/review-M4.md`） | ✅ T4.1 事件发布（`meeting.events` topic + 事务提交后投递）｜✅ T4.2 notification-service 骨架（:8083）｜✅ T4.3 消费落库站内信（幂等 + 手动 ACK + 死信）｜✅ T4.4 通知 REST（列表/未读/已读，契约补登 docs/04 3.3）｜✅ T4.5 会议提醒（扫描 + 幂等 key）｜✅ T4.6 WebSocket 推送（网关 `?token=` + 提交后推）｜✅ T4.7 Redis 缓存（会议室注解式 + 日历手写式）｜✅ T4.8 预约限流（ZSET+Lua + Nacos 动态阈值）｜✅ T4.9 traceId 服务侧（MDC + 网关访问日志）｜✅ T4.10 测试盘点（单测 52→80 例）｜✅ T4.11 审计切面（@Log + 独立 audit.log）｜✅ T4.F1 通知中心（铃铛角标 + 列表 + WS 实时更新） |
| M5 进阶+部署 | 🟡 **4/7** | ✅ T5.1 周期创建｜✅ T5.2 系列取消｜✅ T5.3 统计接口｜✅ T5.4 前端看板+周期入口；⏸ **T5.5~T5.7 容器化/部署按用户要求暂缓** |

**M4 已全部完成（2026-10-16，12/12）**；**M5 做到 4/7**（周期会议 + 统计 + 看板），
**剩下的 T5.5~T5.7（Dockerfile×4 + compose + Nginx）按用户要求暂缓**——用户要先自行验证一些东西。

**T1.20 组织与用户（2026-10-17，v1.3 遗留项补齐）**：`/admin/users` 从 Placeholder 换成
「组织与用户」单页（左部门树 + 右成员表）。后端补了 `PUT /users/{id}`（改姓名/邮箱/部门/角色）、
`GET /users?includeChildren=`（部门子树筛选）、`/depts/tree` 的 `userCount`（直属人数），以及
`20006` 自锁保护（**不能停用自己 / 不能移除自己的 ADMIN 角色**）。
验证：`e2e-org-users.cjs` **20/20 连跑两次**、后端单测 **87 例全绿**、`npm run check` **26 例**、
`verify-review-batch4.sh` **33/33**、`verify-m3-approval.sh` **32/32**。

**T6.1 日历预约体验优化（2026-10-17）**：
- **过去时间**：日历过去格灰罩 + 点击兜底拦截；`RangePicker` 禁过去日期/时分；`bookingSchema` 增加
  “开始晚于现在”；后端沿用 `validateTime` 的 `30003`（脚本回归）。
- **时间轴/重叠**：行高 240→360、整点网格线、每 2 小时刻度；**20:00 只在最后一行显示**（否则与下一行
  08:00 在行边界叠在一起）；重叠会议按簇分泳道并排（`calendarLayout`，纯函数单测）。
- **会议块**：`HH:mm 主题` + `预订人：姓名`；待审批显示「待审批」，审批人显示「待你审批」；点击进详情。
- **四色图例**：已过期（灰，优先）→ 待审批（橙）→ 我预订（蓝）→ 邀请我的（绿）→ 其他（灰）。
- **会议室**：悬浮卡展示全部字段（容量/位置/设备/审批策略等）；关键字过滤（名称/位置/设备）。
- **参会人**：弹窗左部门树 + 右用户多选（替换搜索式 `UserPicker`）；后端
  `GET /users/search?keyword=&deptId=` 登录可访问、只列直属成员、无 email、上限 50。
- **日历契约**：返回 **10/20/30/40**；新增 `invited`（当前用户是否参会）——**在缓存读出后按请求用户
  实时装配，不写回缓存**（缓存键 room+week 共享，写进去会串用户）。
- 验证：后端 **91 例**、`verify-calendar-optimize.sh` **18/18 连跑两次**（含缓存隔离双向）、
  `e2e-calendar-enhanced.cjs` **23/23 连跑两次**、旧 `e2e-calendar.cjs` **14/14**（已改为自造数据）、
  `e2e-booking.cjs` **19/19**、`e2e-dashboard.cjs` **11/11**、`verify-m4-cache.sh` **31/31**、
  `verify-review-batch4.sh` **33/33**、`npm run check` **46 例**。

**M4 演示脚本（可照做）**：user1 登录前端（:5173）→ 另开一个浏览器/接口以 approver1 身份审批 →
user1 端**铃铛角标秒级 +1**（WS）→ 打开通知中心看到"审批通过"→ 点掉角标归零。
`web/e2e/e2e-notify-center.cjs` 就是这条演示的可执行版本。

**M2 后端补强（2026-10-15）**：修掉一处双重预订竞态——`update()`（改期）原先不经过预约锁，与"新建"并发时能各自通过冲突检测、同一时段落下两场会议（**实测 10 轮 7 轮复现**）。修法：改期纳入同一把房间锁 + 事务内加会议室行锁兜底（`selectIdForUpdate`），并补齐"凡是改变时段占用状态的写路径都要取行锁"这条设计约束（审批 T3.2 同此理）。回归脚本 `scripts/concurrent-modify.sh` 修复后 10/10 通过。

**M4 全量评审与修复（2026-10-16，见 `docs/review-M4.md`）**：两个独立 agent 交叉评审（后端 / 前端+脚本）+ 逐条复现，
9 条 Required 全部修完（其中 6 条是"注释/文档说的性质和代码实际行为不符"），
并补了 9 个验证脚本/2 个 e2e 的弱断言。修复过程中自己新造的 6 个坑全部被**重跑**抓出——
详见该文档的"复验"小节（含一条很有代表性的教训：`mvn clean` 会删掉正在运行的服务所依赖的 jar）。

**M2 全量评审与修复（2026-10-15，见 `docs/review-M2.md`）**：对代码做了一次从 M0 到现在的全量 review（4 Critical + 12 Required + 14 Optional + M1 存量 17 条 P1 逐条核实），随后分 4 批修完：
安全止血（Redis 口令/摘要存储、服务只绑回环、分页溢出、登录时序）→ 并发正确性（锁的异常收尾、状态守卫、幂等语义）→ 前端可见缺陷（竞态守卫、状态映射收敛等 7 条）→ 契约与工程（入参上限、PII 收口、框架异常映射、网关 traceId/CORS、前端 lint+测试+分包）。
**明确未修**的项集中记在 `review-M2.md`「五·补」小节（如 accessToken 真空期、登录限流、路由级懒加载），不要当成已完成。

---

## 4. 下一步：M4 通知 + MQ + 打磨（详见 05 文档 T4.1~T4.12）

> M2（后端 10 + 前端 3）与 **M3（8 任务）均已全部完成**，下一步是 M4。
>
> **M3 收尾要点（做 M4 时会用到）**：
> - 审批闭环：待办/已办列表 → 通过（**复检 + 房间锁 + 双行锁**）→ 拒绝（终态）。
>   锁序固定「先会议室行 → 后会议行」，与 T2.10 一致（红线 #10）；反过来会与改期交叉持锁死锁。
> - 生命周期：`MeetingStatusTask` 每分钟把 20→30、20/30→40，Redisson 锁做多实例互斥；
>   每小时对账补偿（E-4）修"时间过了状态没推进"，并**只告警不自动修** status=10 的超时待审批。
> - 工具：`scripts/verify-m3-*.sh` 三个验证脚本（审批 / 延长 / 定时任务），每个都自起/自清数据。
> - ⚠️ `verify-m3-scheduler.sh` 会**接管 8082**（起两个实例抢任务锁），跑完要按默认配置重启服务。
>
> **M4 进度与交接（2026-10-16）**：
> - ✅ T4.1~T4.11 + T4.F1 全部完成，M4 收口。
> - 🔧 **死信拓扑已由 `MeetingMqTopology`（meeting-common）统一声明**，生产端/消费端都引用它——
>   队列参数（含 `x-dead-letter-exchange`）不可能漂移。⚠️ 若将来**再改**队列参数：
>   RabbitMQ 的参数是建立时定死的，必须**删掉老队列重建**（且两边同源改），否则 `PRECONDITION_FAILED`。
> - 📌 消息契约（`MeetingEventType`/`MeetingDomainEvent`/`MeetingEventMessage`）也在 `com.smt.common.mq`：
>   它们是**跨服务契约**，别把它们搬回 meeting-service。消费端用 `TypePrecedence.INFERRED` 反序列化
>   （类型取监听方法签名、不信任消息头 `__TypeId__`），所以消费端不依赖 FQCN，但共享模块保证字段不会漂移。
> - 🔄 **T4.5 已按"定时扫描"实现**（TTL+DLX 在这类延迟分布下不可用：预约可提前 30 天 → 同队列 TTL 相差 30 天，
>   队头必堵；分桶在桶内同样堵；延迟插件本机镜像没有）。**原因与三方案对比已写进 docs/02 5.4**，
>   提醒的幂等 key 是 `remind:sent:{meetingId}:{开始时刻}`（改期自动重新武装）。
>   ⚠️ 提醒任务与状态任务**共用 `meeting.task.enabled` 开关**；间隔分别是
>   `meeting.task.remind.delay-ms`（默认 60s）与 `meeting.task.status.delay-ms`。
> - ⚠️ 生产端（meeting-service）是**懒连接**，所以 `RabbitConfig` 里有 `ApplicationRunner` 主动声明拓扑；
>   消费端（notification-service）**连接是启动即建**的（要在启动时注册监听器），不存在这个问题。

### M2 前端三件套（均已完成为存档，详见 05 文档 T2.F1~F3）

### ✅ T2.F1 日历页（已完成，2026-10-15）
- **行 = 会议室、列 = 7 天**（多会议室同屏；非"一次一个会议室的选择器"）；每格内一条 08:00–20:00 时间轴，会议块按 start/end 百分比定位（块高 = 时长）
- 数据：`GET /rooms?status=0` + 对每个会议室**并发** `GET /rooms/{id}/calendar?week=`（calendar 接口是单会议室维度）
- 点空格 → 按**点击高度**反推时间（吸附 30 分钟，默认 1 小时）→ 打开预约 Drawer 并预填（T2.F2 已接入）
- 点会议块 → **阻止冒泡**（否则会误开"新增预约"），详情页留给 T2.F3
- 竞态：响应体带 `week`，只在与当前周一致时渲染（快速切周不画旧数据）
- 性能：`CalendarGrid` / `RoomRow` 双层 memo，空集合用模块级常量保引用稳定
- 状态色（**docs/04 §5.1 为准**）：10 待审橙 / 20 已预约**蓝** / 30 进行中**绿** / 40 已结束灰 / 50 已取消浅灰 / 60 已拒绝红

### ✅ T2.F2 预约表单（Drawer + RHF）已完成，2026-10-15
- 点日历空格 → 开 `BookingDrawer`（预填会议室 + 时间）；字段：主题 / 会议室 / 时间范围 / 参会人 / 备注
- 时间或会议室变化 → **防抖 500ms** 调 `POST /rooms/availability` → 红字冲突提示（带占用会议详情）
- 提交：打开表单时生成一次 `crypto.randomUUID()` 放 `X-Idempotency-Token` → 成功后刷新日历
- ⚠️ **10006 陷阱**：request.ts 对幂等重复是"静默解包成 undefined"（不抛异常），成功回调必须判空
- ⚠️ 提交 40001（被并发抢）→ 弹冲突 Modal；此时抽屉**有意不关闭**，让用户就地改时间
- 参会人搜索：`components/UserPicker.tsx`（防抖 500ms 调 `GET /users/search`，已选项靠 seen 表兜 label）

### ✅ T2.F3 我的会议列表 + 详情（已完成，2026-10-15）
- 列表：`GET /meetings?scope=mine`，**状态 + 关键字**两个筛选（关键字防抖 500ms，改筛选回第 1 页）+ 分页 + 状态色标签
- 详情：`GET /meetings/{id}`，Descriptions 基本信息 + 参会人 + **状态时间线**（`logs`，event 文案见 `MeetingStatusTag.tsx` 的映射）
- 取消：`POST /meetings/{id}/cancel`（body 可空）；按钮显示条件 = **组织人 + 状态 10/20**（状态机只允许这两态取消）
- `components/MeetingStatusTag.tsx`：状态 → 文案/配色的**唯一映射处**（列表/详情共用）
- 详情页**公开可查**（不校验归属）；数据权限只加在写操作上（取消/修改）
- 列表也带"查询键"竞态守卫；docs/04 已补登详情/取消的响应结构

---

## 5. 关键约定（红线，违反必返工）

1. **文档先行**：改需求/表/接口前，先改 `docs/01~04`，代码对齐文档
2. **手写 MyBatis XML**（禁用 MP 便利方法）：显式 `resultMap` + `#{}` 参数化
3. **错误码必须登记 `ErrorCode` 枚举**，不许散落魔法数字；新增码同步 `docs/04` 错误码表
4. **状态变更必须走 `MeetingStateMachine.transition`**（迁移表驱动 + CAS 更新 + 写 `meeting_status_log`），**禁止裸 UPDATE 改 status**
5. **RBAC 多角色（v1.3）**：角色走 `sys_user_role` 关联，JWT 携带 `roles` 数组；鉴权用 `@PreAuthorize("hasRole('ADMIN')")`；**禁止在用户表加角色字段**；部门树（`sys_dept`）仅作组织结构、不参与权限
6. **参会人仅限系统用户**（v1.3 已砍外部参会人）：`meeting_participant` 有联合唯一键 `uk_meeting_user`
7. **并发防护三层**：幂等（`@Idempotent` + SETNX）→ 分布式锁（`lock:meeting:{roomId}:{date}`，跨天逐日加锁）→ 乐观锁（修改会议用 `version`）
8. **锁必须包住事务**：预约用 `TransactionTemplate`（锁外开、事务锁内提），别用 `@Transactional` 包住锁（会留"锁释放→事务提交"空窗）
9. **凡是改变"某时段是否被占用"的写路径，都必须走同一把房间锁 + 会议室行锁**：创建（T2.4）、改期（T2.10）、审批通过（T3.2）。漏掉任何一条，那条路径就是双重预订的口子——T2.10 未加锁时实测 **10 轮里 7 轮双重预订**（乐观锁只管"同一行"，管不住跨行竞态）
10. **数据库锁序固定「先会议室行 → 后会议行」**：审批要同时锁两把（防双人同审 + 防时段被抢），顺序反了会与改期交叉持锁死锁

---

## 6. 踩坑清单（血泪，别重蹈）

1. **`git add` 用明确文件路径**，别 `git add -A <目录>`（曾把外部进程删掉的 07 文档连同删除一并提交了）
2. **bash 里 `$VAR` 紧跟中文**会被吞进变量名（如 `$ROOM_ID）`）→ 一律用 `${VAR}` 界定
3. **验证接口看 body 的 `code`**，不是 HTTP 状态码（业务错误 = HTTP 200 + 非 0 code）
4. **`docker exec` 进 mysql 必须加 `--default-character-set=utf8mb4`**，否则插入中文乱码
5. **本地跑 jar 必须 `env -u SERVER__PORT`**（否则 Spring 读系统 `SERVER__PORT` 覆盖 yml 端口）
6. **Nacos 注册 IP 已固化 `127.0.0.1`**（休眠/换网后局域网 IP 会变导致服务发现连不上）
7. **`redhat.java` 扩展锁定 1.56.0，不要升级**——升级会引发一串极难排查的编译/启动故障（已定位并修复，2026-10-15）
   - **根因**：1.57.0 把内置 JDT core 升到 3.48、运行时 JDK 升到 25，引入了 JEP 513「Flexible Constructor Bodies」。**Lombok 生成的构造函数被它误判**成"super() 之前有语句"→ 每个 Lombok 类（`@RequiredArgsConstructor`/`@Data`…）都报 *"The Java feature 'Flexible Constructor Bodies' is only available with source level 25 and above"*。纯误报：javac 编译同一份代码完全正常。实测全工作区 241 处，且 22/22 个报错文件都是 Lombok 类（0 个非 Lombok）——这是确认误报的最快判据
   - **危害**：语言服务认为项目编译不过 → 把 **ecj"错误桩"class 写进 `target/classes`** → 桩类时间戳比源码新 → Maven 增量编译**跳过**它、原样打进 jar → 服务启动即崩（`Unresolved compilation problems` / `ClassFormatError: Invalid method Code length 0`）。10-14 日志记为"依赖下载抖动"的 `MojoExecutionException`、10-15 的 `MeetingUpdateDTO` ClassFormatError 都是此因
   - **处置**：已装回 1.56.0（JDT 3.47 + JDK 21，无该误报；实测报错 241 → 0）。⚠️ **在 VS Code 扩展面板对 redhat.java 点「忽略更新 / Ignore Updates」**，否则会被自动升回去、故障复现
   - **兜底习惯**：改了后端代码一律 `mvn clean package`（`clean` 能清掉任何残留桩类）
8. **服务与基础设施一律只绑回环 `127.0.0.1`**（2026-10-15 评审 C1/C2 后加的）：
   三个服务的 `application.yml` 有 `server.address: 127.0.0.1`，`deploy/docker-compose.infra.yml` 的端口映射全部写成 `127.0.0.1:xxxx:xxxx`；
   **Redis 同时加了 `requirepass`**（第 4 批补齐——此前只有端口收窄，免密这件事本身没解决）。
   - **为什么**：服务端**无条件信任** `X-User-*` 身份头（网关验签后注入的设计前提是"业务端口只在网关后面可达"）。
     绑 `0.0.0.0` 时，同网段任意机器直连 8081 伪造 `X-User-Roles: ADMIN` 就能读 ADMIN 接口、伪造 `X-User-Id` 就能操作他人会议；
     Redis 还无密码 + 存着会话凭证 → 实测一条 `nc` 就能读走 refreshToken 换 ADMIN token（详见 `docs/review-M2.md` C1/C2）。
   - **副作用**：服务不能再被局域网其他设备访问。要给面试官演示，需显式改 `server.address` 并自行承担风险，
     或改用「网关绑内网网卡 + 业务端口只对网关机开放」的部署形态。
   - **Redis 口令**：默认 `redis123`，用 `REDIS_PASSWORD` 环境变量覆盖（compose 与三个 yml 同源）。
     ⚠️ `docker exec smt-redis redis-cli PING` 现在会回 `NOAUTH`（这是正常的）；要手工连需 `-a` 或 `REDISCLI_AUTH`。
     ⚠️ 改 MySQL 口令必须 `down -v` 重建数据卷（只在首次初始化时读取），改 Redis 口令只需重启容器。
   - 顺手清理：改完后 Redis 里会残留修复前的**明文**反向索引 key（`auth:refresh:token:*` 后缀 36 字符的那批）——
     新代码按摘要查找、已不可达，可用 `--scan --pattern` 删掉。

9. **回归/验证脚本必须自清理，否则会"把系统跑坏"**（2026-10-15 第 4 批踩到）：
   - `scripts/concurrent-reserve.sh` 原先每轮留下 1 场 `status=20` 的会议 → 跑几轮后该会议室候选时段被自己填满，
     脚本开始报"连续 10 天候选时段均被占用"，**看起来像功能坏了，其实是被自己的数据堵死了**。已补 `cancel_slot` 自清理。
   - 残留数据还会**让后端单测误红**：`ConflictCheckerTest` 用固定时段 2026-11-10 10:00，
     该时段一旦有外部残留（哪怕是自己几小时前跑脚本留下的），断言就从 1 变 2 而失败。
     **单测红了先查库，别先怀疑代码**：`SELECT id,title FROM meeting WHERE status IN (10,20,30) AND start_time >= '...'`。
   - 判断"是不是自己造的"最省事的办法：看 `created_at` 是不是刚才、标题是不是 `并发验证-*` / `E2E*` / `C4验证-*`。
   - ⚠️ 2026-10-17：`RoomMapperTest` 断言“会议室恰好 6 间”，用户验证时新增了一间“大明宫”就红了。
     **种子数据的断言写成“至少 6 间且种子都在”，不要锁死总数**——管理员新增会议室是正常业务操作，
     不该让单测失败（也别为了跑绿去删用户数据）。

10. **用户可控字符串的长度上限必须与目标列宽对齐**（2026-10-15 M3 评审抓到，差点漏网）：
    - 审批意见 DTO 允许 500 字（`meeting_approval.comment` 也是 500），但它同时被写进
      `meeting_status_log.remark`（**VARCHAR(200)**）→ 写 250 字意见时严格模式（`STRICT_TRANS_TABLES`）
      直接 `1406 Data too long` → **审批 500 失败、整笔业务回滚**（会议停在待审批）。
    - **修法**：在**列宽的所有者**那里截断（`MeetingStateMachine.insertLog` 统一截断到列宽），
      而不是逐个改调用方——调用方还会增加，列宽不会。全文留在业务表，日志是摘要。
    - ⚠️ 截断本身也有坑：hutool 的 `StrUtil.maxLength(s, 200)` 会**额外追加 `"..."`**，
      得到 203 字符照样超宽。传参要把省略号算进预算（`200 - 3`）。
    - **自查方式**：新增/修改 DTO 时，对着 `db/01-schema.sql` 核一遍每个字符串字段的列宽；
      同型问题本次还查出 `sys_user.email`（列宽 100，DTO 只校验了 `@Email` 没限长）。
    - **教训**：修复必须配一条**写得进**的验收用例——这条 bug 是靠"250 字意见必须通过"的断言
      才在修复后又被抓出一次（第一次的修复就是错的）。

11. **跑 Maven 别被管道吞掉退出码**（2026-10-15 踩到，白跑两次）：
    `mvn ... | tail -20` 的 `$?` 是 `tail` 的退出码，**永远 0**；而 `-DskipTests` 只跳过"执行"、**仍然编译测试类**，
    测试编译失败一样算构建失败。写法：`mvn ... > /tmp/build.log 2>&1; echo $?`（或 `set -o pipefail`）。
    本次教训：`GlobalExceptionHandler.handleAccessDenied` 签名从 `Result` 改成 `ResponseEntity` 后，
    `CommonWebAutoConfigurationTest` 编译不过 → 构建其实失败了，却因为看的是管道退出码而误判为成功，
    导致后面"用旧 jar 启动服务 + 连新 Redis"打出一堆 `NOAUTH` 假故障。

12. **`mvn -pl <下游模块> package` 不带 `-am` 会静默链上 `~/.m2` 里的旧共享库**（2026-10-16 踩到，构建全绿、启动即崩）：
    给 `meeting-common` 加了新包后，用 `mvn -q -pl notification-service -DskipTests package` 打包 ——
    编译"成功"（**增量编译跳过了没变的源码**，复用了上次的 target/classes），`package` 却把 `~/.m2` 里的**旧**
    meeting-common jar 塞进了 fat jar，于是运行时炸 `NoClassDefFoundError: com/smt/common/mq/MeetingEventMessage`。
    **规矩**：改了共享库就用根目录的 `mvn -q -DskipTests clean install`（或至少给上游模块补 `-am`/先 install），
    别对下游模块单点 `package`。与踩坑 #7（桩类时间戳）是同一类："构建绿"不等于"跑的是新代码"。
    自检：`unzip -l <fat.jar> | grep meeting-common` 看出嵌套 jar 的时间戳，或解开看类在不在。
13. **验证脚本之间会互相破坏前提（消费者 vs 生产者断言）**（2026-10-16）：
    `verify-m4-mq.sh` 断言"消息**还留在队列里**"，而 notification-service 就是这些队列的消费者——
    它一在跑，消息在断言前就被 ack 掉了，脚本表现成"生产端没发消息"（实测 5/11，失败信息完全指向错误的方向）。
    已加前置守卫：脚本检测到 8083 在监听就直接报错退出，并提示改跑 `verify-m4-notify-consume.sh`（那个自起自收 8083）。
    **推广**：加了消费者之后，任何"读队列做断言"的脚本都要先想清楚"此刻谁在消费"。
14. **脚本的残留不止在数据库里，还在 Redis**（2026-10-16）：
    `verify-m4-notify-consume.sh` 第一次重跑时三条断言全红，原因是**上一轮**留下的 `mq:idem:{msgId}`（TTL 24h）
    让同 msgId 的消息被正确判成"重复"、脚本却期待"新插入"。数据库清了不等于干净。
    规矩：自清理要覆盖**它自己写过的每一个存储**（MySQL 行 / Redis 键 / MQ 队列与死信）；
    脚本开头也要把自己弄到干净状态，而不是指望上一次跑完清过。
    ⚠️ 顺带两条小坑：① 清孤儿数据时 `DELETE n FROM a n LEFT JOIN b`（多表 DELETE）在 mysql 客户端
    **没选库**时会报 `1046 No database selected`，而我把 stderr 扔进了 /dev/null → 看到的是"删除成功 11 行"的假象，
    实际一行没删。**验证类命令别吞 stderr**。② 日志断言别把全角括号写进模式（"已重试 3 次）"对不上"已重试 3 次，"）。
    ⚠️ 2026-10-17 T1.20 的 e2e 自清理又踩了一次：**多表 DELETE 前先 `USE smt_meeting;`**（即使表名写全了也会报 1046）。

15. **浏览器测 WebSocket 不能从 `about:blank` 起页面**（2026-10-16 踩到，表现为"全部连接失败"）：
    Chrome 的**本地网络访问保护**会拦掉"非本地源 → localhost"的连接，报
    `net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS`。危害不只是连不上——它让
    **"不带 token 连不上"这条断言假阳性通过**（所有连接都被拦，看起来像网关在正确拒绝）。
    正解：从真实前端源起页面（`page.goto('http://localhost:5173/')`），这也更贴近前端实际行为。
    **推广**：一条断言"通过了"，先问它有没有可能因为**别的原因**通过。

16. **RabbitMQ 管理 API 的队列路径必须带 vhost 段**（2026-10-16，同一个坑踩了两次）：
    `/api/queues/%2F/meeting.created` ✅ ／ `/api/queues/meeting.created` ❌（404）。
    危险之处在于它**不报错**——`curl | python -c "d.get('consumers') or 0"` 把 404 体解析成 `0`，
    于是"URL 写错了"看起来完全像"消费者没接上"，排查方向直接跑偏。
    T4.3 的脚本里踩过一次、T4.5 重写时又漏了 → **结论：这种带固定前缀的调用别每次手写 URL**，
    抽成一个函数，错只可能错一次。
17. **验证脚本的"相对时间"会随脚本自身的运行时长漂移**（2026-10-16）：
    T4.5 的负例是"20 分钟后开始的会议不该被提醒"，而脚本自己跑 4 分钟——跑到后段那场会已经漂进
    15 分钟提醒窗口、领到一条**完全正确**的提醒，负例断言于是失败（看起来像产品坏了）。
    **规矩**：给时间敏感的负例留出**大于脚本总时长**的余量；或者断言前重新校准基准时间。
    ⚠️ 2026-10-17 T6.1 又踩到它的"固定日期版"：`bookingSchema.test.ts` 的基线写死 `2026-10-15`，
    到 10-17 就变成过去时间；`e2e-booking` / `e2e-dashboard` 用"本周六"，而当天恰好是周六且已过 10:00。
    **规矩**：测试数据用**相对于今天的未来日期**（或像 e2e-booking 现在这样固定“下一周”），
    不要写死绝对日期；加“过去时间不可预约”这类规则时，必须同时扫一遍所有固定日期夹具。
18. **通知表的断言必须带 `type` 过滤**（2026-10-16，同一类错犯了三次）：
    同一场会在同一用户那里有多条通知（MEETING_CREATED + MEETING_REMIND…），
    只按 `meeting_id` 查会把"预约成功"当成"会议提醒"来核标题/内容/已读位。
    写通知相关断言时，`type` 与 `meeting_id` 一起写。

19. **脚本里起了长驻进程后，别再裸用 `wait` / 别让管道被子进程抓住**（2026-10-16，同一类坑栽了两次）：
    `verify-m4-cache.sh` 第 0 节起了一个**永不退出**的 java 服务（自己接管 8082），第 6 节想做并发压测时写了裸 `wait`——
    裸 `wait` 等的是**所有**后台子进程，于是它一直在等那个 java，**脚本挂了 10 分钟没有任何输出**，
    而服务端一切正常（现场 curl 一遍所有接口都是 200）。排查方向极易跑偏成"接口卡住了"。
    → 正解：`wait` 要**显式列出 pid**（`for ... & PIDS="$PIDS $!"; done; wait $PIDS`）。
    ⚠️ 同类前科：`verify-m3-scheduler.sh` 当年是**管道**被子进程抓住（`cmd | tail` 里的长驻 java 不关 stdout，tail 永远等不到 EOF）。
    **判据**：脚本"没输出、也没退出"时，先看它在等谁——`pgrep -P <脚本pid>` 一眼就能看出是不是在等自己起的服务。

20. **`mvn clean` 会把"正在运行的服务所依赖的 jar"删掉，后果很隐蔽**（2026-10-16 踩到，整轮验证全红）：
    串跑脚本写成"先 `mvn clean test`、再跑验证脚本"，而 `clean` 删掉了当时**正在运行**的三个服务所依赖的 jar：
    - **正在跑的 JVM 不会立刻死**（类已加载），但**懒加载的 jar 内资源会崩**——
      auth-service 在读 `META-INF/services/...` 时报 `JarUrlConnection.getInputStream` 失败，接口回 500；
    - 任何"按端口 kill 再按 jar 路径重启"的脚本（如 `verify-m4-cache.sh`）**没有 jar 可重启** → 服务再也起不来。
    于是表象是"11 个脚本 + 2 个 e2e 全红"，根因却与业务毫无关系。
    **规矩**：`构建 + 单测 → (重)启动服务 → 跑脚本`，**中途绝不再 clean/构建**。
    （另：`mvn test` 不产出 jar，所以"clean test"之后连包都不在——要用 `mvn clean install` 一步到位做完构建与单测。）
    这也解释了为什么值得把这条和踩坑 #12 放一起看：**构建产物与正在跑的进程，是两个世界**。
    ⚠️ 2026-10-17 复现了这条的"后半段"：上一轮回归停掉 notification-service 后没重启，
    前端通知中心 `/api/notifications` 直接 503（网关路由不到实例）。**回归收尾必须确认四个端口都在**
    （8080/8081/8082/8083），否则表象是"通知功能坏了"。

21. **MyBatis 的显式 `resultMap` 按 setter 映射 → 不可变 record 用不了**（2026-10-16 T5.3 踩到）：
    `ReflectionException: There is no setter for property named 'roomId' in 'class ...'`。
    两条出路：① 投影类型用**可变 Bean**（本项目规矩是"SQL 显式 resultMap"，所以选它）；
    ② 用 `resultType` + 列别名走**构造器路径**（record 天然适配，但列名要与组件名对齐）。
    ⚠️ 同一次还撞了它的兄弟：让 mapper 去填一个 **SQL 里不存在的字段**（服务端算的派生值）会报
    `IndexOutOfBoundsException: Index 4 out of bounds for length 4`（构造函数参数比结果集列多）。
    **约定：mapper 的返回类型只放 SQL 真查得到的东西**，派生值（占用率、百分比…）在 Service 里算。

22. **`insertBatch` 要回填主键**（2026-10-16，实现 WS 推送时发现）：
    MyBatis 的 `<foreach>` 多值 INSERT 默认**不回填自增主键**——通知落库后拿不到 id，
    推送体只能推一条没有 id 的通知（前端没法"标记已读"，等于推了个半成品）。
    加上 `useGeneratedKeys="true" keyProperty="id"` 即可：Jdbc3KeyGenerator 对"单值集合参数"
    会按顺序把主键回填到每个元素上（e2e 里断言了"推送的 id 能在 REST 列表里查到"）。

23. **antd 两字按钮会自动插空格，Playwright 的 `name: '保存'` 匹配不到**（2026-10-17 T1.20 踩到）：
    antd Button 默认 `autoInsertSpace`，两个汉字之间渲染成 "保 存 / 编 辑 / 删 除 / 取 消"，
    **accessible name 也跟着带空格**。e2e 里两字按钮一律用正则：`/保\s*存/`、`/编\s*辑/`、`/删\s*除/`、`/取\s*消/`；
    四个字以上（新增用户 / 删除部门）不受影响。

24. **共享缓存里不能放“用户相关”字段**（2026-10-17 T6.1，日历“邀请我的”）：
    日历缓存键是 `cache:calendar:{roomId}:{weekStart}`——**所有用户共享**。把 `invited`
    （当前用户是否参会）直接算进缓存的 VO，A 用户的“邀请我的”会串给 B。
    **正确做法**：缓存只存基础数据；读出来之后按 `UserContext` 实时装配（`decorateInvited`），
    **装饰后的对象绝不写回缓存**。验证必须包含**两个方向**：先 A 后 B、先 B 后 A 都不串。
    推广：任何 `room/week/dept` 维度共享的缓存，都不能带 `userId` 维度的字段。

---

## 7. 怎么跑起来

```bash
# 1. 基础设施（MySQL/Redis/RabbitMQ/Nacos）
docker compose -f deploy/docker-compose.infra.yml up -d

# 2. 后端（改了代码先打包，然后各开一个终端）
cd server && mvn -q -DskipTests clean install
cd server && env -u SERVER__PORT java -jar gateway/target/gateway-1.0.0-SNAPSHOT.jar
cd server && env -u SERVER__PORT java -jar auth-service/target/auth-service-1.0.0-SNAPSHOT.jar
cd server && env -u SERVER__PORT java -jar meeting-service/target/meeting-service-1.0.0-SNAPSHOT.jar
cd server && env -u SERVER__PORT java -jar notification-service/target/notification-service-1.0.0-SNAPSHOT.jar

# 3. 前端
cd web && npm run dev    # http://localhost:5173
```

**验证与测试**（都要求上面三个服务已启动；退出码非 0 = 断言失败，可直接接 CI）：

```bash
# 后端
bash scripts/concurrent-reserve.sh 5 --repeat 10   # 5 个客户端抢同一时段 → 恰好 1 成功（跑完自清理槽位）
#   ⚠️ 两个 concurrent 脚本会**临时通过 Nacos 关掉限流**（T4.8 起预约有限流，几十次连发必撞），退出时恢复
#   ⚠️ 它们用 room 1，而 room 1 上有 2026-10-15 留下的 20 场 active 会议 → 会报"连续 10 天候选时段均被占用"；
#      换 `ROOM_ID=6` 或先清理那批历史数据
bash scripts/concurrent-modify.sh 10               # 改期 vs 新建抢同一时段 → 时段只落 1 场
bash scripts/verify-review-batch4.sh               # 第 4 批修复的 33 条逐项断言（R7/R8/P1-*）
bash scripts/verify-m3-approval.sh                # T3.1~T3.3：待办/已办、复检 50001、并发双人同审、拒绝
bash scripts/verify-m3-extend.sh                  # T3.4：延长上限两场景、40006、复检兜底、提前结束
bash scripts/verify-m3-scheduler.sh               # T3.5/T3.6：定时流转、多实例互斥、幂等、对账补偿（⚠️ 会接管 8082，跑完需自行重启服务）
bash scripts/verify-m4-mq.sh                       # T4.1：五类领域事件真投到 broker（routingKey/payload 核对，自清理）
bash scripts/verify-m4-notification-skeleton.sh     # T4.2：notification-service 骨架（自起自收 8083，跑完不留进程）
bash scripts/verify-m4-notify-consume.sh           # T4.3：五类事件落站内信、幂等、两条失败路径进死信（自起自收 8083）
bash scripts/verify-m4-notify-api.sh               # T4.4：列表倒序/分页、未读数、已读幂等、跨用户越权 60001（自起自收 8083）
bash scripts/verify-m4-remind.sh                   # T4.5：提醒窗口/收件人/幂等/窗口外与待审批不提醒/改期重新武装
bash scripts/verify-m4-cache.sh                    # T4.7：命中判据用 SQL 次数、写路径清缓存、随机/空值 TTL、single-flight
bash scripts/verify-m4-ratelimit.sh                # T4.8：滑动窗口拦截、Nacos 改阈值不重启生效、0=关闭、ZSET 形态
bash scripts/verify-m4-traceid.sh                  # T4.9：网关+服务两侧同 id、无头请求兜底、非法值被重生成
bash scripts/verify-m4-audit.sh                    # T4.11：审计留痕（成功/失败/五类动作/不记全文/带 traceId）
bash scripts/verify-m5-recurring.sh                # T5.1：周期创建（展开/N 场共享 recurring_id/冲突清单/40007/幂等）
bash scripts/verify-m5-recurring-cancel.sh         # T5.2：系列取消（未来场次/历史不受影响/进行中拦截，约 2 分钟）
bash scripts/verify-m5-stats.sh                    # T5.3：统计（接口 vs 独立 SQL 对账/裁剪/口径/权限）
#   ⚠️ **凡是会连续造会的脚本，开头都要装限流开关**（T4.8 之后预约有用户级限流 10 次/分钟）：
#      `source "$(dirname "$0")/lib/ratelimit.sh"` + `ratelimit_disable_for_script`
#      （e2e 用 `web/e2e/lib/ratelimit.cjs` 的 `disableRateLimit()`）。
#      不装的话脚本会撞 10005，**报出来却是"创建失败"，看着像功能坏了**——整轮串跑时踩到过。
#      限流本身由 verify-m4-ratelimit.sh 专门验证；这些脚本只是借用同一个开关（含读回确认 + 退出恢复）
#   ⚠️ **串跑顺序**：构建+单测 → (重)启动服务 → 跑脚本，**中途绝不再 clean/构建**（见踩坑 #20）
#   📌 审计日志在 `server/logs/audit.log`（logback-spring.xml 的 AUDIT appender，留存 90 天）；
#      它**不随测试清理**——审计只追加、不删改
#   ⚠️ 本脚本 grep `server/logs/*.log` → 服务必须**按约定路径**启动（`nohup java -jar … > logs/<svc>.log`）
cd server && mvn -q test -Dtest='RateLimitAspectTest' -Dsurefire.failIfNoSpecifiedTests=false  # 限流降级放行（单测）
#   ⚠️ 本脚本会**接管 8082**（要读服务的 SQL 日志），跑完自动按默认配置重启；判据是"SQL 执行次数"而不是耗时
#   ⚠️ 本脚本用**默认 60 秒扫描间隔**跑，全程约 4 分钟（要等两三轮扫描）——慢是有意的，验的就是真实配置
#   ⚠️ verify-m4-mq.sh 与上面两个脚本**不能同时跑**：MQ 脚本要求 8083 停着（消费者会把待断言的消息吃掉），
#      脚本已加守卫会明确报错——这属于设计，不是 bug
cd server && mvn -q clean test -Dtest='!AuthFeignClientTest#batchUsersShouldDegradeWhenAuthDown' -Dsurefire.failIfNoSpecifiedTests=false  # 单测 76 例（那条降级用例需 auth 停机时单独跑）
#   ⚠️ 多类选择用**逗号**（`-Dtest=A,B,C`）；用加号会一个都不跑，而 `failIfNoSpecifiedTests=false` 让"没匹配到"也算成功
#   ⚠️ 覆盖率（可选）：`mvn -pl meeting-service org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report`
#      读数字要看第二眼：80% 的未覆盖指令是 VO/DTO 的 getter/setter；且脚本级端到端验证 jacoco 测不到（详见 docs/05 T4.10）
#   ⚠️ 别用 `mvn ... | tail`：管道会把退出码换成 tail 的（见踩坑 #11）
#   ⚠️ `-Dtest='!...'` 必须配 `-Dsurefire.failIfNoSpecifiedTests=false`：否则**没有测试的模块**（gateway）
#      会以 "No tests matching pattern" 判定失败——看着像构建挂了，其实只是过滤器没匹配到任何用例

# 前端
cd web && npm run check     # = tsc --noEmit + eslint + vitest（26 例）
node e2e/e2e-calendar.cjs   # playwright E2E（需先 npm i --no-save playwright + dev server；详见 web/e2e/README.md）
node e2e/e2e-notify-ws.cjs  # T4.6：真浏览器验 WS 推送（连接/鉴权拒连/隔离/多标签页/秒级推送/会话注销）
```

**演示账号**（密码均为 `admin123`/`user123`）：

| 账号 | 密码 | 角色（RBAC 多角色） | 部门 |
|------|------|------|------|
| admin | admin123 | ADMIN + APPROVER | 行政部 |
| approver1 / approver2 | user123 | APPROVER | 产品部 / 研发部 |
| user1 / user2 / user3 | user123 | USER | 研发部×2 / 产品部 |

---

## 8. 开发历史速查

- 完整开发过程：`git log`（每个任务一个 commit，message 含实现要点 + 实测验收结果，M2 的 10 个任务从状态机到乐观锁全在里面）
- WorkBuddy 的每日决策/踩坑日志：`../.workbuddy/memory/2026-10-*.md`（10-12 起，含架构决策、评审修复、v1.3 数据模型升级全过程）
