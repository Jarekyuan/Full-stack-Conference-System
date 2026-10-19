# 全栈学习计划与教程 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-10-19-fullstack-learning-plan-design.md` 写出 5 天 × 6 小时的全栈学习计划、5 篇日教程和前后端文件地图。

**Architecture:** 先写总览 `docs/09` 作为索引和掌握矩阵；再按天写 5 篇教程，每篇固定 7 段结构并引用用户笔记；最后按模块生成 `docs/10` 文件地图（222 个后端生产 Java 文件 + 66 个前端源文件），用脚本核对数量、路径和引用。

**Tech Stack:** Markdown、Spring Boot 3 / Spring Cloud / MySQL / Redis / RabbitMQ、React 18 / TypeScript / Vite / antd、Docker / Nginx / GitHub Actions。

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `docs/09-全栈学习计划.md` | 总览、5 天日程、掌握矩阵、检查点、笔记引用索引、使用方法 |
| `docs/learning/day1-全栈全景与登录链路.md` | Day 1 教程 |
| `docs/learning/day2-预约核心与日历表单.md` | Day 2 教程 |
| `docs/learning/day3-并发锁幂等与详情状态.md` | Day 3 教程 |
| `docs/learning/day4-审批MQ通知与前端联动.md` | Day 4 教程 |
| `docs/learning/day5-会议纪要与部署面试.md` | Day 5 教程 |
| `docs/10-前后端文件地图.md` | 后端 222 + 前端 66 个文件的中文职责地图 |

> 笔记在项目外：`项目实践/前端笔记/` 4 篇、`项目实践/后端笔记/` 4 篇。
> 教程用「📘 笔记引用」块引用书名 + 章节，不大段复制原文。

---

## Task 1: 总览 `docs/09`

**Files:**
- Create: `docs/09-全栈学习计划.md`

- [ ] **Step 1: 写文件头和使用说明**

包含：
- 标题、面向 1-3 年全栈岗位、5 天 × 6h = 30h
- 使用方式：建议开 `study/fullstack-30h` 分支；每天结束做 5 道自测 + 3 句话复盘
- 运行方式链接：`README.md` 的 compose / 本地开发；默认 `local + mock`
- 链接到 spec、`docs/08`、`docs/10`

- [ ] **Step 2: 写 5 天日程表**

表格列：Day / 主题 / 后端 / 前端 / 手敲 / 教程链接。内容与 spec 第 5 节一致，链接到：
- `learning/day1-全栈全景与登录链路.md`
- `learning/day2-预约核心与日历表单.md`
- `learning/day3-并发锁幂等与详情状态.md`
- `learning/day4-审批MQ通知与前端联动.md`
- `learning/day5-会议纪要与部署面试.md`

- [ ] **Step 3: 写掌握矩阵和检查点**

掌握矩阵用三行：⭐ / 🔍 / ⚪，内容照 spec 第 4 节。
检查点表：
- Day 1：画架构 + 登录链路
- Day 2：冲突检测单测通过
- Day 3：并发 10 轮通过
- Day 4：MQ 幂等 + WS 通知
- Day 5：`verify-deploy` 15/15 + 15 分钟演示

- [ ] **Step 4: 写取舍、风险、非目标和成功标准**

- 取舍：单库/无分布式事务/最终一致；通知只做站内信 + WS；不深挖词级时间戳
- 风险：如果落后，砍 `CalendarGrid` 几何细节、缓存/限流深挖；保护 Day 3 锁和 Day 5 纪要/部署
- 非目标：不从零重写、不逐行讲测试、不深挖 ECharts/Word、不教 Java/React 基础、不做 K8s/多租户
- 成功标准：15 分钟演示、20+ 追问、手写 3 个核心片段、按业务 1 分钟内定位文件

- [ ] **Step 5: 写笔记引用索引、差异速查和了解清单**

索引表列：笔记文件 / 重点章节 / 对应 Day / 类型（A/B/C/D）；覆盖 spec 4.5.3。
差异速查表至少包含：
- Ribbon → Spring Cloud LoadBalancer
- Sentinel → Resilience4j + Feign fallback
- Druid → HikariCP
- PageHelper → `PageBounds` / `PageResult`
- pnpm → npm
- Next.js/SSR → Vite + React 18 SPA
- 完整 Spring Security → 网关 JWT + `@PreAuthorize`
- `BaseContext` → `UserContext`
- `HandlerInterceptor` → Filter + 网关 `GlobalFilter`
- `@Async` → MQ + 定时任务
- Vue vs React → 只作为对比题

“了解即可”单列：
- 《1.前端基础》HTML/CSS/浏览器安全/SQL 注入
- 《2.React知识点》SSR/Hydration/RSC/React 19
- 《3.Ts基础》条件类型/infer/工具类型
- 《4.前端工程化》Monorepo/微前端/Qiankun/Next.js
- 《10.Java基础》JVM/设计模式/JS 对比 Java
- 《11.Spring Boot》Bean 生命周期/循环依赖
- 《12...》主从复制/分库分表/Redis 高级
- 《13...》Sentinel/Ribbon/完整上线清单

- [ ] **Step 6: 验证**

Run:
```bash
test -f docs/09-全栈学习计划.md
grep -c '^## ' docs/09-全栈学习计划.md   # >= 5
grep -c 'learning/day' docs/09-全栈学习计划.md  # >= 5
grep -c '类型：' docs/09-全栈学习计划.md  # >= 4
grep -nE 'TBD|TODO|待定' docs/09-全栈学习计划.md && exit 1 || true
```
Expected: 文件存在，章节和链接数量达标，无占位符。

- [ ] **Step 7: Commit**

```bash
git add docs/09-全栈学习计划.md
git commit -m "docs: 新增全栈学习计划总览"
```

---

## Task 2: Day 1 教程

**Files:**
- Create: `docs/learning/day1-全栈全景与登录链路.md`

**必须包含的 7 个二级标题：**
1. `## 1. 今日目标`
2. `## 2. 面试先问`
3. `## 3. 10 分钟概念`
4. `## 4. 代码走读`
5. `## 5. 手敲练习`
6. `## 6. 五道自测`
7. `## 7. 复盘卡`

> 每篇只允许这 7 个 `##` 标题；自测答案用 `### 答案` 放在第 6 节内，不要新增 `##`。

- [ ] **Step 1: 写今日目标和面试先问**

今日目标（3 条）：
- 能画出 4 服务架构和登录请求链路
- 能启动 infra / 后端 / 前端并解释端口
- 能用 `docs/10` 按业务定位登录相关文件

面试先问（5 条）：
- 浏览器登录到拿到 token 发生了什么？
- 为什么认证放网关？服务内为什么还要 `@PreAuthorize`？
- JWT 双 token 怎么刷新？401 后前端怎么处理？
- Filter 和 Interceptor 的区别？
- 多 tab 登录为什么会互相挤掉，怎么解决？

- [ ] **Step 2: 写 10 分钟概念**

用 ASCII 图画出：
```text
浏览器 → Nginx/Vite → gateway → auth-service → JWT
                              └→ meeting-service（X-User-* 头）
```
解释 4 个服务的职责、网关白名单、身份头注入、前端 `/api` 代理。

- [ ] **Step 3: 写后端代码走读**

按调用链讲，至少覆盖：
- `server/gateway/src/main/java/com/smt/gateway/filter/JwtAuthFilter.java`
- `server/gateway/src/main/resources/application.yml`（路由、白名单、CORS）
- `server/auth-service/src/main/java/com/smt/auth/controller/AuthController.java`
- `server/auth-service/src/main/java/com/smt/auth/service/AuthService.java`
- `server/auth-service/src/main/java/com/smt/auth/store/RefreshTokenStore.java`
- `server/meeting-common/src/main/java/com/smt/common/util/JwtUtil.java`
- `server/meeting-common/src/main/java/com/smt/common/filter/UserHeaderFilter.java`
- `server/meeting-common/src/main/java/com/smt/common/filter/TraceIdFilter.java`
- `server/meeting-common/src/main/java/com/smt/common/context/UserContext.java`
- `server/meeting-common/src/main/java/com/smt/common/result/Result.java`
- `server/meeting-common/src/main/java/com/smt/common/result/ErrorCode.java`

每处写：它解决什么问题、关键方法、面试一句话。

- [ ] **Step 4: 写前端代码走读**

至少覆盖：
- `web/src/main.tsx`
- `web/src/router.tsx`
- `web/src/components/RouteGuards.tsx`
- `web/src/pages/Login.tsx`
- `web/src/layouts/MainLayout.tsx`
- `web/src/api/request.ts`
- `web/src/api/token.ts`
- `web/src/stores/userStore.ts`

重点讲：
- axios 拦截器、401 刷新队列、错误归一化
- sessionStorage 多 tab 隔离
- Zustand persist 的边界（客户端状态 vs 服务端状态）

- [ ] **Step 5: 写 📘 笔记引用（至少 3 条）**

必须包含（A/B/C 类）：
- 《11.Spring Boot》§一.4 Spring MVC 执行流程 → A → 网关/auth 请求链
- 《11.Spring Boot》§五.3 拦截器与过滤器 → B → 项目用 Filter + 网关，不是 HandlerInterceptor
- 《11.Spring Boot》§五.4 ThreadLocal → A → `UserContext`
- 《13.微服务+部署》§二.1 Nacos / §二.4 Gateway → A
- 《2.React知识点》§四.1 Hooks 基础 / §四.6 自定义 Hooks → A → `useAppRequest` / `useWebSocket`
- 《3.Ts基础》§10 React 实战速查 → A/C

格式：
```markdown
> 📘 **笔记引用**：《11.Spring Boot》§五.3 拦截器与过滤器
> 类型：B（项目有差异）
> 笔记结论：Filter 拦所有请求，Interceptor 只拦 Controller。
> 项目落点：`UserHeaderFilter` / 网关 `JwtAuthFilter`。
> 差异/提醒：项目没有用 HandlerInterceptor；面试先讲区别再讲选型。
```

- [ ] **Step 6: 写手敲练习**

任务：
1. 启动 infra、后端、前端（命令写全）
2. `curl` 登录，保存 token；带 token 请求 `/api/rooms`
3. 打开浏览器 Network，对照请求头
4. 在 `web/src/api/request.ts` 临时加一条 `console.debug`（或写一个 request 单测）
验收：
- 能展示 login 响应里的 `accessToken` / `refreshToken`
- 能看到 `/api/rooms` 带 `Authorization`
- 能解释 401 后的刷新流程

- [ ] **Step 7: 写五道自测和复盘卡**

5 道题 + 文末答案：
1. 网关如何把用户身份传给下游？
2. access token 过期后前端怎么处理？
3. 为什么用 sessionStorage 而不是 localStorage？
4. Filter 和 Interceptor 的区别？
5. `@PreAuthorize` 在哪个服务、依赖什么头？

复盘卡模板：
```text
今天最重要的 3 句话：
1.
2.
3.
可以讲给面试官的 1 个点：
```

- [ ] **Step 8: 验证**

Run:
```bash
test -f docs/learning/day1-全栈全景与登录链路.md
grep -c '^## ' docs/learning/day1-全栈全景与登录链路.md   # 7
grep -c '📘 \*\*笔记引用\*\*' docs/learning/day1-全栈全景与登录链路.md  # >= 3
for p in server/gateway/src/main/java/com/smt/gateway/filter/JwtAuthFilter.java web/src/api/request.ts web/src/api/token.ts; do test -e "$p" || exit 1; done
grep -nE 'TBD|TODO|待定' docs/learning/day1-全栈全景与登录链路.md && exit 1 || true
```
Expected: 7 个二级标题、>=3 条笔记引用、关键路径存在、无占位符。

- [ ] **Step 9: Commit**

```bash
git add docs/learning/day1-全栈全景与登录链路.md
git commit -m "docs: 新增 Day1 全栈全景与登录链路教程"
```

---

## Task 3: Day 2 教程

**Files:**
- Create: `docs/learning/day2-预约核心与日历表单.md`

**7 个二级标题同 Task 2。**

- [ ] **Step 1: 今日目标 + 面试先问**

目标：
- 讲清 6 状态和迁移规则
- 讲清半开区间冲突检测和 buffer
- 能读懂日历布局和预约表单

面试先问：
- 为什么用半开区间？
- 冲突检测在锁内还是锁外？为什么？
- 待审批会议占不占时段？
- 日历重叠会议怎么布局？
- 表单校验和提交幂等怎么配合？

- [ ] **Step 2: 10 分钟概念**

画状态机：
```text
10 待审批 → 20 待开始 → 30 进行中 → 40 已结束
     ├→ 50 已取消
     └→ 60 已拒绝
```
画半开区间：
```text
[10:00, 11:00) 与 [11:00, 12:00) 不冲突
```
解释 buffer 的含义。

- [ ] **Step 3: 后端代码走读**

至少覆盖：
- `server/meeting-service/src/main/java/com/smt/meeting/statemachine/MeetingStateMachine.java`
- `server/meeting-service/src/main/java/com/smt/meeting/enums/MeetingStatusEnum.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/ConflictChecker.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MeetingReserveService.java`（创建校验链）
- `server/meeting-service/src/main/java/com/smt/meeting/service/AvailabilityService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MeetingQueryService.java`
- `server/meeting-service/src/main/resources/mapper/MeetingMapper.xml`（冲突 SQL）
- `server/meeting-service/src/main/java/com/smt/meeting/entity/Meeting.java`
- `server/meeting-service/src/main/java/com/smt/meeting/entity/RoomStrategy.java`

重点：迁移表、CAS、半开区间 SQL、buffer、可用性/日历查询。

- [ ] **Step 4: 前端代码走读**

至少覆盖：
- `web/src/pages/Calendar.tsx`
- `web/src/components/CalendarGrid.tsx`
- `web/src/components/calendarGeometry.ts`
- `web/src/components/calendarLayout.ts`
- `web/src/pages/BookingDrawer.tsx`
- `web/src/pages/bookingSchema.ts`
- `web/src/pages/MeetingList.tsx`

重点：时间轴定位、重叠布局、RHF + zod、提交幂等、列表筛选。

- [ ] **Step 5: 📘 笔记引用（至少 3 条）**

必须包含：
- 《12.MySQL+Redis+并发锁》§二.2 索引基础 / §二.3 SQL 优化 → A
- 《12...》§五.2 事务基础 / §五.3 隔离级别 → A
- 《11.Spring Boot》§六.1 MyBatis / §六.2 MyBatis 原理 → A/B
- 《2.React知识点》§二.4 受控/非受控 + §四.1 Hooks → A
- 《3.Ts基础》§7 类型收窄 / §10 React 实战速查 → A/C
- 《4.前端工程化》§4.1 测试分层 / §4.2 组件测试原则 → B

- [ ] **Step 6: 手敲练习**

任务：
1. 在 `server/meeting-service/src/test/java/com/smt/meeting/service/ConflictCheckerTest.java` 增加或新建一个 `hasConflict` 纯函数测试，覆盖：
   - 相邻不冲突
   - 交叉/包含/相同冲突
   - buffer 边界
2. Run: `cd server && mvn -pl meeting-service -Dtest=ConflictCheckerTest test`
验收：测试全绿；能解释每个用例为什么冲突/不冲突。

- [ ] **Step 7: 五道自测 + 复盘卡**

题目：
1. `[10:00,11:00)` 与 `[11:00,12:00)` 为什么不算冲突？
2. 状态机为什么用迁移表而不是 if/else？
3. buffer 在冲突检测里怎么体现？
4. 创建预约时事务边界在哪？
5. 日历重叠会议怎么排布？

- [ ] **Step 8: 验证 + Commit**

```bash
test -f docs/learning/day2-预约核心与日历表单.md
grep -c '^## ' docs/learning/day2-预约核心与日历表单.md   # 7
grep -c '📘 \*\*笔记引用\*\*' docs/learning/day2-预约核心与日历表单.md  # >= 3
cd server && mvn -pl meeting-service -Dtest=ConflictCheckerTest test
cd ..
grep -nE 'TBD|TODO|待定' docs/learning/day2-预约核心与日历表单.md && exit 1 || true
git add docs/learning/day2-预约核心与日历表单.md
git commit -m "docs: 新增 Day2 预约核心与日历表单教程"
```

---

## Task 4: Day 3 教程

**Files:**
- Create: `docs/learning/day3-并发锁幂等与详情状态.md`

**7 个二级标题同 Task 2。**

- [ ] **Step 1: 今日目标 + 面试先问**

目标：
- 讲清锁粒度、锁与事务顺序、改期也要加锁
- 讲清幂等 key 和乐观锁
- 能读懂详情页状态门槛

面试先问：
- 为什么锁 key 是房间 + 日期？
- 锁内事务怎么开？为什么不能用 `@Transactional` 包住锁？
- 改期为什么也要加锁？
- 幂等和锁的区别？
- 乐观锁和悲观锁怎么选？

- [ ] **Step 2: 10 分钟概念**

画三层并发：
```text
JVM 内 synchronized → 单库事务/行锁/乐观锁 → 多实例 Redisson
```
画时间线：加锁 → 开事务 → 查冲突 → 落库 → 提交 → 释放锁。

- [ ] **Step 3: 后端代码走读**

至少覆盖：
- `server/meeting-service/src/main/java/com/smt/meeting/service/MeetingReserveService.java`（创建/改期/取消）
- `server/meeting-service/src/main/java/com/smt/meeting/aop/Idempotent.java`
- `server/meeting-service/src/main/java/com/smt/meeting/aop/IdempotentAspect.java`
- `server/meeting-service/src/main/resources/mapper/MeetingMapper.xml`（version/行锁）
- `server/meeting-service/src/test/java/com/smt/meeting/service/MeetingReserveLockTest.java`
- `scripts/concurrent-reserve.sh`
- `scripts/concurrent-modify.sh`

- [ ] **Step 4: 前端代码走读**

至少覆盖：
- `web/src/pages/MeetingDetail.tsx`
- `web/src/components/MeetingEditDrawer.tsx`
- `web/src/components/CancelMeetingModal.tsx`
- `web/src/components/MeetingStatusTag.tsx`

重点：状态门槛、编辑/取消权限、打开抽屉才加载会议室、详情页 effect。

- [ ] **Step 5: 📘 笔记引用（至少 3 条）**

必须包含：
- 《12...》§五.1 并发控制的三个层次 → A
- 《12...》§五.5 锁 / §五.6 乐观锁 vs 悲观锁 → A
- 《12...》§四.4 分布式锁 / §四.5 Redisson → A
- 《11.Spring Boot》§六.6 事务 @Transactional → A
- 《11.Spring Boot》§四.4 AOP → A
- 《2.React知识点》§四.4 闭包陷阱 / §四.5 useEffect 异步坑 → A
- 《10.Java基础》§十一 Java 并发+JVM → C

- [ ] **Step 6: 手敲练习**

任务：
1. Run: `bash scripts/concurrent-reserve.sh 10 --repeat 10`（服务已启动）
2. 写一个最小幂等测试或 `IdempotentAspect` 测试：
   - 第一次成功
   - 第二次返回 10006
3. 在 `MeetingReserveLockTest` 里观察锁 + 事务的行为
验收：并发脚本 10 轮“恰好 1 个成功”；幂等测试通过。

- [ ] **Step 7: 五道自测 + 复盘卡**

题目：
1. 锁 key 为什么不是会议 ID？
2. 锁释放和事务提交的顺序为什么重要？
3. 幂等 key 什么时候清理？
4. 乐观锁 `version` 解决了什么？
5. 改期为什么必须走同一把房间锁？

- [ ] **Step 8: 验证 + Commit**

```bash
test -f docs/learning/day3-并发锁幂等与详情状态.md
grep -c '^## ' docs/learning/day3-并发锁幂等与详情状态.md   # 7
grep -c '📘 \*\*笔记引用\*\*' docs/learning/day3-并发锁幂等与详情状态.md  # >= 3
grep -nE 'TBD|TODO|待定' docs/learning/day3-并发锁幂等与详情状态.md && exit 1 || true
git add docs/learning/day3-并发锁幂等与详情状态.md
git commit -m "docs: 新增 Day3 并发锁幂等与详情状态教程"
```

---

## Task 5: Day 4 教程

**Files:**
- Create: `docs/learning/day4-审批MQ通知与前端联动.md`

**7 个二级标题同 Task 2。**

- [ ] **Step 1: 今日目标 + 面试先问**

目标：
- 讲清待审批不占时段 + 审批复检 + 锁序
- 讲清 AFTER_COMMIT、消费幂等、手动 ACK、死信
- 能读懂审批中心、通知中心和 WS 联动

面试先问：
- 待审批不占时段，通过时怎么防被抢？
- 为什么不能在事务内直接发 MQ？
- 消费端怎么保证不重复落库？
- WebSocket 怎么经过网关？
- 缓存什么时候失效？限流怎么做的？

- [ ] **Step 2: 10 分钟概念**

画审批时序 + MQ 流程：
```text
审批通过 → 复检/行锁 → 提交事务 → AFTER_COMMIT → RabbitMQ
→ notification-service 消费 → 幂等 SETNX → 落库 → WS 推送
```

- [ ] **Step 3: 后端代码走读**

至少覆盖：
- `server/meeting-service/src/main/java/com/smt/meeting/service/ApprovalService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/mq/MeetingEventPublisher.java`
- `server/meeting-common/src/main/java/com/smt/common/mq/MeetingMqTopology.java`
- `server/notification-service/src/main/java/com/smt/notification/mq/MeetingEventConsumer.java`
- `server/notification-service/src/main/java/com/smt/notification/mq/ConsumeIdempotency.java`
- `server/notification-service/src/main/java/com/smt/notification/service/NotificationService.java`
- `server/notification-service/src/main/java/com/smt/notification/ws/NotificationWebSocketHandler.java`
- `server/notification-service/src/main/java/com/smt/notification/ws/NotificationPushListener.java`
- 侧栏：`RoomService` / `CalendarCacheEvictor` / `CacheConfig` / `RateLimitAspect` / `RateLimitProperties`

- [ ] **Step 4: 前端代码走读**

至少覆盖：
- `web/src/pages/ApprovalCenter.tsx`
- `web/src/components/ApprovalDrawer.tsx`
- `web/src/pages/NotificationList.tsx`
- `web/src/hooks/useWebSocket.ts`
- `web/src/stores/notificationStore.ts`
- `web/src/components/notificationRouting.ts`

- [ ] **Step 5: 📘 笔记引用（至少 3 条）**

必须包含：
- 《13...》§三 MQ / §三.4 消息可靠性 → A
- 《11.Spring Boot》§七.1 Redis 缓存 / §七.3 WebSocket → A
- 《12...》§四.1 缓存 / §四.2 缓存三个问题 / §四.3 Spring Cache → A
- 《2.React知识点》§五.4 现代状态管理选型 → A/B
- 《1.前端基础》§四 HTTP、运行环境 → C

- [ ] **Step 6: 手敲练习**

任务：
1. 给 `MeetingEventPublisher` 加一个 AFTER_COMMIT 测试：
   - 事务提交后收到事件
   - 事务回滚不发事件
2. 写消费幂等测试：同一 msgId 第二次不落库
3. 启动两个浏览器 profile，观察 WS 通知
验收：测试通过；通知中心 1 秒内 +1。

- [ ] **Step 7: 五道自测 + 复盘卡**

题目：
1. 审批通过为什么要复检？
2. AFTER_COMMIT 解决了什么问题？
3. 消费幂等 key 怎么设计？
4. 手动 ACK 和自动 ACK 的区别？
5. WS 为什么只能把 token 放 query？

- [ ] **Step 8: 验证 + Commit**

```bash
test -f docs/learning/day4-审批MQ通知与前端联动.md
grep -c '^## ' docs/learning/day4-审批MQ通知与前端联动.md   # 7
grep -c '📘 \*\*笔记引用\*\*' docs/learning/day4-审批MQ通知与前端联动.md  # >= 3
grep -nE 'TBD|TODO|待定' docs/learning/day4-审批MQ通知与前端联动.md && exit 1 || true
git add docs/learning/day4-审批MQ通知与前端联动.md
git commit -m "docs: 新增 Day4 审批MQ通知与前端联动教程"
```

---

## Task 6: Day 5 教程

**Files:**
- Create: `docs/learning/day5-会议纪要与部署面试.md`

**7 个二级标题同 Task 2。**

- [ ] **Step 1: 今日目标 + 面试先问**

目标：
- 讲清 COS 分片、ASR 状态机、语音文本联动、DeepSeek 异步
- 讲清 compose/Nginx/CI 的请求路径和故障点
- 完成 15 分钟演示

面试先问：
- 大文件为什么直传 COS？分片和断点续传怎么实现？
- ASR 任务重复/乱序怎么防？
- 为什么用 `ResTextFormat=0`？
- 虚拟滚动怎么做的？
- Nginx 502 怎么排查？

- [ ] **Step 2: 10 分钟概念**

画全链路：
```text
浏览器 → initMultipart → COS 分片直传 → complete
→ ASR CreateRecTask → 轮询 → 解析 → 落库
→ 音频/转写联动 → DeepSeek → Word 导出
```
画部署拓扑（Nginx → gateway → services → infra）。

- [ ] **Step 3: 后端代码走读**

至少覆盖：
- `server/meeting-service/src/main/java/com/smt/meeting/service/MultipartUploadService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/CosObjectStorage.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/LocalObjectStorage.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesAsrService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/TencentAsrClient.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/AsrResultParser.java`
- `server/meeting-service/src/main/java/com/smt/meeting/task/MinutesAsrTask.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/DeepSeekLlmClient.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesAiService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/controller/MinutesController.java`

- [ ] **Step 4: 前端代码走读**

至少覆盖：
- `web/src/components/minutes/MinutesUploader.tsx`
- `web/src/components/minutes/AudioTranscriptWorkspace.tsx`
- `web/src/components/minutes/TranscriptList.tsx`
- `web/src/utils/transcriptSync.ts`
- `web/src/components/minutes/MinutesSummary.tsx`
- `web/src/components/minutes/MinutesInsights.tsx`
- `web/src/hooks/useMultipartUpload.ts`
- `web/src/components/minutes/minutesUi.ts`

- [ ] **Step 5: 部署/CI 代码走读**

至少覆盖：
- `server/gateway/Dockerfile`、`server/auth-service/Dockerfile`、`server/meeting-service/Dockerfile`、`server/notification-service/Dockerfile`
- `web/Dockerfile`
- `deploy/docker-compose.yml`
- `deploy/nginx.conf`
- `.github/workflows/ci.yml`
- `scripts/verify-deploy.sh`

- [ ] **Step 6: 📘 笔记引用（至少 3 条）**

必须包含：
- 《2.React知识点》§四.6 自定义 Hooks / §四.5 useEffect 异步坑 → A
- 《2.React知识点》§六 性能优化 / §七 原理深入 → A/B
- 《1.前端基础》§三 Web API → A/C
- 《4.前端工程化》§4 测试与 CI / §5 构建工具与性能 / §9 浏览器安全 → A/B/C
- 《13...》§四 Docker / §五 Nginx / §六 上线 → A
- 《10.Java基础》§十 IO 流 / §六 序列化 → A/C

- [ ] **Step 7: 手敲练习**

任务：
1. 写 ASR 结果解析单测（正常行、非法行、空行）
2. 写 `splitSentences` 边界测试（标点、空段、比例时间）
3. Run: `bash scripts/verify-deploy.sh`
4. 做一次 15 分钟演示（按 `docs/08`）
验收：测试通过；verify-deploy 15/15；演示不卡壳。

- [ ] **Step 8: 五道自测 + 复盘卡**

题目：
1. 预签名 URL 为什么不让文件经过后端？
2. ASR run token 解决什么问题？
3. `ResTextFormat=0` 和 `3` 的区别？
4. 虚拟滚动为什么能减少 DOM？
5. 容器重建后 Nginx 怎么避免 502？

- [ ] **Step 9: 验证 + Commit**

```bash
test -f docs/learning/day5-会议纪要与部署面试.md
grep -c '^## ' docs/learning/day5-会议纪要与部署面试.md   # 7
grep -c '📘 \*\*笔记引用\*\*' docs/learning/day5-会议纪要与部署面试.md  # >= 3
grep -nE 'TBD|TODO|待定' docs/learning/day5-会议纪要与部署面试.md && exit 1 || true
git add docs/learning/day5-会议纪要与部署面试.md
git commit -m "docs: 新增 Day5 会议纪要与部署面试教程"
```

---

## Task 7: 文件地图后端 Part 1（common + gateway + auth）

**Files:**
- Create: `docs/10-前后端文件地图.md`

- [ ] **Step 1: 建文件头和图例**

包含：
- 用途、使用方法（先看“按业务找文件”，再按目录查）
- 图例：⭐ 必须 / 🔍 理解 / ⚪ 了解
- 表格列：`文件 | 中文职责 | 关键类/方法 | 掌握级别`

- [ ] **Step 2: 列出文件**

Run:
```bash
find server/meeting-common/src/main/java -name '*.java' | sort   # 16
find server/gateway/src/main/java -name '*.java' | sort          # 4
find server/auth-service/src/main/java -name '*.java' | sort     # 31
```
共 51 行。

- [ ] **Step 3: 写中文职责**

规则：
- Controller：接口 + 权限 + 关键校验
- Service：业务规则/事务/锁
- Mapper：SQL 能力
- Config：配置项
- DTO/VO/Entity：一行带过
- Filter/Util：一句话职责

- [ ] **Step 4: 验证明细**

Run:
```bash
grep -c '^| `server/' docs/10-前后端文件地图.md   # >= 51
```
Expected: 至少 51 行，且 common/gateway/auth 文件全部出现。

- [ ] **Step 5: Commit**

```bash
git add docs/10-前后端文件地图.md
git commit -m "docs: 新增文件地图后端 common/gateway/auth"
```

---

## Task 8: 文件地图后端 Part 2（meeting-service controller/service/mapper）

**Files:**
- Modify: `docs/10-前后端文件地图.md`

- [ ] **Step 1: 列文件**

```bash
find server/meeting-service/src/main/java/com/smt/meeting/controller -name '*.java' | sort  # 9
find server/meeting-service/src/main/java/com/smt/meeting/service -name '*.java' | sort     # 28
find server/meeting-service/src/main/java/com/smt/meeting/mapper -name '*.java' | sort      # 14
```
共 51 行。

- [ ] **Step 2: 写职责 + 掌握级别**

重点文件（⭐）：
- `MeetingService`、`ApprovalService`、`ConflictChecker`、`MinutesAsrService`、`MultipartUploadService`、`MeetingQueryService`
- `MeetingMapper`、`MeetingMinutesMapper`、`TranscriptSegmentMapper`
- `MeetingController`、`ApprovalController`、`MinutesController`

- [ ] **Step 3: 验证 + Commit**

```bash
grep -c '^| `server/meeting-service/src/main/java/com/smt/meeting/controller' docs/10-前后端文件地图.md  # 9
grep -c '^| `server/meeting-service/src/main/java/com/smt/meeting/service' docs/10-前后端文件地图.md     # 28
grep -c '^| `server/meeting-service/src/main/java/com/smt/meeting/mapper' docs/10-前后端文件地图.md      # 14
git add docs/10-前后端文件地图.md
git commit -m "docs: 补充 meeting-service 核心文件地图"
```

---

## Task 9: 文件地图后端 Part 3（meeting-service config/entity/dto/vo/enums）

**Files:**
- Modify: `docs/10-前后端文件地图.md`

- [ ] **Step 1: 列文件**

```bash
find server/meeting-service/src/main/java/com/smt/meeting/config -name '*.java' | sort   # 14
find server/meeting-service/src/main/java/com/smt/meeting/entity -name '*.java' | sort   # 13
find server/meeting-service/src/main/java/com/smt/meeting/dto -name '*.java' | sort      # 14
find server/meeting-service/src/main/java/com/smt/meeting/vo -name '*.java' | sort       # 34
find server/meeting-service/src/main/java/com/smt/meeting/enums -name '*.java' | sort    # 7
```
共 82 行。

- [ ] **Step 2: 写职责**

- Config：一句话说明配置项
- Entity：对应表 + 关键字段
- DTO/VO：一行说明用途
- Enums：状态/错误码/类型

- [ ] **Step 3: 验证 + Commit**

```bash
python3 - <<'PY'
import re, pathlib
text = pathlib.Path('docs/10-前后端文件地图.md').read_text()
for pkg, n in [('config',14),('entity',13),('dto',14),('vo',34),('enums',7)]:
    c = len(re.findall(rf'^\| `server/meeting-service/src/main/java/com/smt/meeting/{pkg}/', text, re.M))
    assert c == n, (pkg, c, n)
print('OK')
PY
git add docs/10-前后端文件地图.md
git commit -m "docs: 补充 meeting-service 配置与模型文件地图"
```

---

## Task 10: 文件地图后端 Part 4（meeting-service 其余 + notification）

**Files:**
- Modify: `docs/10-前后端文件地图.md`

- [ ] **Step 1: 列文件**

```bash
find server/meeting-service/src/main/java/com/smt/meeting/aop -name '*.java' | sort         # 7
find server/meeting-service/src/main/java/com/smt/meeting/task -name '*.java' | sort        # 5
find server/meeting-service/src/main/java/com/smt/meeting/feign -name '*.java' | sort       # 4
find server/meeting-service/src/main/java/com/smt/meeting/statemachine -name '*.java' | sort # 2
find server/meeting-service/src/main/java/com/smt/meeting/mq -name '*.java' | sort          # 1
find server/meeting-service/src/main/java/com/smt/meeting/lock -name '*.java' | sort        # 1
find server/meeting-service/src/main/java/com/smt/meeting/handler -name '*.java' | sort     # 1
find server/meeting-service/src/main/java/com/smt/meeting -maxdepth 1 -name '*.java' | sort # 1
find server/notification-service/src/main/java -name '*.java' | sort                        # 16
```
共 38 行。

- [ ] **Step 2: 写职责 + 掌握级别**

重点：`IdempotentAspect`、`RateLimitAspect`、`LogAspect`、`MinutesAsrTask`、`MinutesAiTask`、`MeetingStateMachine`、`MeetingEventPublisher`、`MeetingEventConsumer`、`ConsumeIdempotency`、`NotificationWebSocketHandler`。

- [ ] **Step 3: 验证 + Commit**

```bash
python3 - <<'PY'
import re, pathlib
text = pathlib.Path('docs/10-前后端文件地图.md').read_text()
counts = {
 'aop':7,'task':5,'feign':4,'statemachine':2,'mq':1,'lock':1,'handler':1,
}
for pkg, n in counts.items():
    c = len(re.findall(rf'^\| `server/meeting-service/src/main/java/com/smt/meeting/{pkg}/', text, re.M))
    assert c == n, (pkg, c, n)
c = len(re.findall(r'^\| `server/notification-service/src/main/java/', text, re.M))
assert c == 16, c
c = len(re.findall(r'^\| `server/meeting-service/src/main/java/com/smt/meeting/[A-Z][^/]*\.java`', text, re.M))
assert c == 1, c
print('OK')
PY
git add docs/10-前后端文件地图.md
git commit -m "docs: 补充 meeting-service 横切与 notification 文件地图"
```

---

## Task 11: 文件地图前端

**Files:**
- Modify: `docs/10-前后端文件地图.md`

- [ ] **Step 1: 列文件**

```bash
find web/src -type f | sort   # 66
```

按目录分组：api、components、components/minutes、hooks、layouts、pages、pages/admin、stores、theme、utils、根文件。

- [ ] **Step 2: 写职责 + 掌握级别**

重点（⭐）：
- `api/request.ts`、`api/token.ts`
- `hooks/useAppRequest.ts`、`hooks/useMultipartUpload.ts`、`hooks/useWebSocket.ts`
- `stores/userStore.ts`、`stores/notificationStore.ts`
- `components/CalendarGrid.tsx`、`components/MeetingEditDrawer.tsx`
- `components/minutes/MinutesUploader.tsx`、`AudioTranscriptWorkspace.tsx`、`TranscriptList.tsx`
- `utils/transcriptSync.ts`、`pages/bookingSchema.ts`

- [ ] **Step 3: 验证 + Commit**

```bash
python3 - <<'PY'
import re, pathlib, subprocess
text = pathlib.Path('docs/10-前后端文件地图.md').read_text()
rows = len(re.findall(r'^\| `web/src/', text, re.M))
files = subprocess.check_output(['find','web/src','-type','f']).decode().split()
assert rows == len(files) == 66, (rows, len(files))
print('OK')
PY
git add docs/10-前后端文件地图.md
git commit -m "docs: 补充前端文件地图"
```

---

## Task 12: 按业务找文件索引 + 全量核对

**Files:**
- Modify: `docs/10-前后端文件地图.md`

- [ ] **Step 1: 加“按业务找文件”索引**

至少 7 组：
- 登录/权限
- 会议室/日历
- 预约/冲突/锁
- 审批/生命周期
- 通知/WS
- 会议纪要
- 部署/CI

每组 3-6 个文件路径 + 一句话。

- [ ] **Step 2: 全量核对**

```bash
python3 - <<'PY'
import re, pathlib, subprocess
text = pathlib.Path('docs/10-前后端文件地图.md').read_text()
backend = re.findall(r'^\| `(server/[^`]+\.java)`', text, re.M)
frontend = re.findall(r'^\| `(web/src/[^`]+)`', text, re.M)
assert len(backend) == 222, len(backend)
assert len(frontend) == 66, len(frontend)
assert len(set(backend)) == 222
assert len(set(frontend)) == 66
for p in backend + frontend:
    assert pathlib.Path(p).exists(), p
print('OK', len(backend), len(frontend))
PY
```

- [ ] **Step 3: 检查没有测试/构建产物**

```bash
! grep -nE 'src/test|web/tests|web/e2e|node_modules|/target/|/dist/' docs/10-前后端文件地图.md
```

- [ ] **Step 4: Commit**

```bash
git add docs/10-前后端文件地图.md
git commit -m "docs: 增加业务索引并核对文件地图完整性"
```

---

## Task 13: 总体验收与文档地图

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Verify: `docs/09-全栈学习计划.md`、`docs/learning/*.md`、`docs/10-前后端文件地图.md`

- [ ] **Step 1: 检查 5 篇日教程**

```bash
for f in docs/learning/day{1,2,3,4,5}-*.md; do
  echo "$f"
  test -f "$f"
  test "$(grep -c '^## ' "$f")" -eq 7
  test "$(grep -c '📘 \*\*笔记引用\*\*' "$f")" -ge 3
  test "$(grep -c '类型：A' "$f")" -ge 1
  ! grep -qE 'TBD|TODO|待定' "$f"
done
```

- [ ] **Step 2: 检查 docs/09 链接**

```bash
grep -o 'learning/day[1-5]-[^)]*\.md' docs/09-全栈学习计划.md | while read -r p; do
  test -f "docs/$p" || { echo "missing docs/$p"; exit 1; }
done
```

- [ ] **Step 3: 更新文档地图**

- `README.md` 文档索引加 `09`、`10`
- `CLAUDE.md` 文档地图加 `09`、`10`
- `docs/09` 链接到 `docs/08` 和 spec

- [ ] **Step 4: 最终提交**

```bash
git add docs/09-全栈学习计划.md docs/learning docs/10-前后端文件地图.md README.md CLAUDE.md
git commit -m "docs: 完成 5 天全栈学习教程与文件地图"
```

---

## 自检清单

- [ ] 5 篇日教程都包含 7 个二级标题
- [ ] 每篇至少 3 条 `📘 笔记引用`，且至少 1 条 A 类
- [ ] 文件地图后端 222 行、前端 66 行，路径全部存在
- [ ] 不含测试、`target`、`node_modules`、`dist`
- [ ] `docs/09` 的 5 个日教程链接全部可跳转
- [ ] README/CLAUDE 文档地图已加入 09/10
- [ ] 没有 `TBD` / `TODO` / 待定
