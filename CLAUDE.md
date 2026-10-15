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
- 微服务 4 个（notification-service 尚未创建，M4 建）：
  - `gateway`（:8080）—— 统一入口 + JWT 鉴权 + 身份头注入
  - `auth-service`（:8081）—— 登录/双 token/用户/部门/角色
  - `meeting-service`（:8082）—— 会议/会议室/预约/审批（核心）
  - `notification-service`（:8083）—— 未开发

---

## 2. 文档地图（★ 动手前必读）

| 文档 | 内容 | 何时读 |
|------|------|--------|
| `docs/00-项目初心与目标.md` | **为什么做这个项目 + 三重目标 + 验收标尺**（防走偏） | 开工前 / 每个里程碑结束对照 |
| `docs/01-需求文档.md` | 需求 + 规则编号 R-1~R-14 + **v1.3 数据模型**（RBAC 五表/部门树/参会人简化） | 改业务规则前 |
| `docs/02-技术架构方案.md` | 技术选型 + 8 项决策 + 关键设计（双 token/分布式锁/索引/Feign） | 动架构前 |
| `docs/03-数据库设计.md` | **15 张表** DDL + 索引设计 + 面试决策点（为何无外键/为何半开区间） | 改表结构前 |
| `docs/04-接口设计.md` | 全部 API 契约 + **错误码表**（10000~40004）+ 身份头约定 | 写接口前 |
| `docs/05-开发计划.md` | **66 任务清单** + 每任务实现要点/验收标准 | 认领任务前 |
| `docs/07-项目理解指南.md` | M0+M1 理解指南（架构全景 + 4 条链路走读 + 8 个难点 + 10 个坑） | 理解已有代码 |
| `server/AI-RULES.md` | 后端编码规范（红线） | 写后端代码前 |
| `web/AGENT.md` | 前端规范 | 写前端代码前 |

---

## 3. 当前进度（2026-10-15 快照，76 commits）

| 里程碑 | 状态 | 内容 |
|--------|------|------|
| M0 基础设施 | ✅ | Docker 4 容器 + 数据库 15 表 + Git |
| M1 骨架+认证+会议室 | ✅ | 23 任务：双 token 无感刷新、会议室 CRUD、策略、Feign、登录/布局 |
| M2 后端（预约核心） | ✅ **10/10** | 状态机 → 冲突检测 → 可用性/日历 → 创建预约 → 幂等 → Redisson 锁 → 并发脚本 → 列表详情 → 取消 → 修改（乐观锁） |
| M2 前端 | ✅ **3/3** | ✅ T2.F1 日历页（时间轴定位 + memo 行级组件化）｜✅ T2.F2 预约表单 Drawer（防抖冲突提示 + 幂等提交）｜✅ T2.F3 我的会议列表 + 详情（筛选分页 + 状态时间线 + 取消） |
| M3 审批+生命周期 | ✅ **8/8** | ✅ T3.1 待办/已办列表｜✅ T3.2 审批通过（复检+行锁）｜✅ T3.3 拒绝｜✅ T3.4 延长 + 提前结束｜✅ T3.5 定时流转｜✅ T3.6 对账补偿｜✅ T3.F1 审批中心（前端）｜✅ T3.F2 详情页延长/结束（前端） |
| M4 通知+MQ+打磨 | ⬜ | 12 任务 |
| M5 进阶+部署 | ⬜ | 7 任务 |

**当前聚焦：M4 通知 + MQ + 打磨**（M2/M3 已全部完成并逐条实测）。

**M2 后端补强（2026-10-15）**：修掉一处双重预订竞态——`update()`（改期）原先不经过预约锁，与"新建"并发时能各自通过冲突检测、同一时段落下两场会议（**实测 10 轮 7 轮复现**）。修法：改期纳入同一把房间锁 + 事务内加会议室行锁兜底（`selectIdForUpdate`），并补齐"凡是改变时段占用状态的写路径都要取行锁"这条设计约束（审批 T3.2 同此理）。回归脚本 `scripts/concurrent-modify.sh` 修复后 10/10 通过。

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

10. **跑 Maven 别被管道吞掉退出码**（2026-10-15 踩到，白跑两次）：
    `mvn ... | tail -20` 的 `$?` 是 `tail` 的退出码，**永远 0**；而 `-DskipTests` 只跳过"执行"、**仍然编译测试类**，
    测试编译失败一样算构建失败。写法：`mvn ... > /tmp/build.log 2>&1; echo $?`（或 `set -o pipefail`）。
    本次教训：`GlobalExceptionHandler.handleAccessDenied` 签名从 `Result` 改成 `ResponseEntity` 后，
    `CommonWebAutoConfigurationTest` 编译不过 → 构建其实失败了，却因为看的是管道退出码而误判为成功，
    导致后面"用旧 jar 启动服务 + 连新 Redis"打出一堆 `NOAUTH` 假故障。

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

# 3. 前端
cd web && npm run dev    # http://localhost:5173
```

**验证与测试**（都要求上面三个服务已启动；退出码非 0 = 断言失败，可直接接 CI）：

```bash
# 后端
bash scripts/concurrent-reserve.sh 5 --repeat 10   # 5 个客户端抢同一时段 → 恰好 1 成功（跑完自清理槽位）
bash scripts/concurrent-modify.sh 10               # 改期 vs 新建抢同一时段 → 时段只落 1 场
bash scripts/verify-review-batch4.sh               # 第 4 批修复的 33 条逐项断言（R7/R8/P1-*）
bash scripts/verify-m3-approval.sh                # T3.1~T3.3：待办/已办、复检 50001、并发双人同审、拒绝
bash scripts/verify-m3-extend.sh                  # T3.4：延长上限两场景、40006、复检兜底、提前结束
bash scripts/verify-m3-scheduler.sh               # T3.5/T3.6：定时流转、多实例互斥、幂等、对账补偿（⚠️ 会接管 8082，跑完需自行重启服务）
cd server && mvn -q test -Dtest='!AuthFeignClientTest#batchUsersShouldDegradeWhenAuthDown'  # 单测（那条降级用例需 auth 停机时单独跑）
#   ⚠️ 别用 `mvn ... | tail`：管道会把退出码换成 tail 的（见踩坑 #10）

# 前端
cd web && npm run check     # = tsc --noEmit + eslint + vitest（26 例）
node e2e/e2e-calendar.cjs   # playwright E2E（需先 npm i --no-save playwright + dev server；详见 web/e2e/README.md）
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
