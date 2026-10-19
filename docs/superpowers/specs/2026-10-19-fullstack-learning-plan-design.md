# 全栈项目学习计划与教程设计

> 日期：2026-10-19
> 项目：new-meeting-systems（会议室预约系统）
> 目标：5 天 × 6 小时 = 30 小时，面向全栈开发面试的系统学习
> 读者：5 年前端经验 + 2-3 个月后端学习经验；短板集中在 Redis/锁/缓存、MQ/微服务/部署

---

## 1. 背景与目标

项目已经完成 M0–M5、M6 会议纪要和部署/CI，但代码主要由 AI 按需求生成。当前问题不是“项目能不能跑”，而是：

- 对代码整体不熟，面试时讲不清“为什么这么写”
- 后端短板集中在 Redis/锁/缓存、MQ/微服务/部署
- 前端虽然经验长，但组件封装、复杂交互和工程化亮点需要重新组织成面试语言
- 需要一份能按天执行、可验收、能定位到文件的学习教程

学完后的目标：

1. 不看文档画出 4 服务架构和 6 条核心链路
2. 讲清 20+ 个高频追问，并能指到具体代码
3. 手写出 3 个核心片段：半开区间冲突检测、Redisson 锁 + 事务、幂等；AFTER_COMMIT 能读懂并讲清
4. 独立完成 15 分钟项目演示
5. 能按业务快速定位前后端文件

---

## 2. 约束与假设

- 总时长：5 天 × 6 小时 = 30 小时；允许每天 ±1 小时浮动
- 学习方式：混合式——概念讲解 + 代码走读 + 手敲练习 + 自测题
- 结构：业务纵向（认证 → 预约 → 并发 → 审批/通知 → 纪要/部署）+ 技术专题横向（Redis/锁、MQ、微服务、部署）
- 前端：不教 React/TS 基础，重点讲组件封装、状态管理、复杂交互和工程取舍
- 后端：不教 Java/Spring 入门，重点讲请求链路、并发、事务、消息和部署
- 运行环境：优先使用本地开发模式；Day 5 使用 compose 默认 `local + mock` 做冷启动验收
- 真实云服务（COS/腾讯 ASR/DeepSeek）作为“实际联调过”的证据，不在学习过程中依赖现场网络

---

## 3. 交付物

| 文件 | 内容 |
|------|------|
| `docs/09-全栈学习计划.md` | 总览、5 天日程、掌握矩阵、检查点、学习方法和笔记引用索引 |
| `docs/learning/day1-全栈全景与登录链路.md` | Day 1 详细教程 |
| `docs/learning/day2-预约核心与日历表单.md` | Day 2 详细教程 |
| `docs/learning/day3-并发锁幂等与详情状态.md` | Day 3 详细教程 |
| `docs/learning/day4-审批MQ通知与前端联动.md` | Day 4 详细教程 |
| `docs/learning/day5-会议纪要与部署面试.md` | Day 5 详细教程 |
| `docs/10-前后端文件地图.md` | 222 个后端生产 Java 文件 + 66 个前端源文件的中文职责地图 |

每篇日教程固定包含 7 个部分：

1. 今日目标（3 条，可验收）
2. 面试先问（3-5 个问题）
3. 10 分钟概念（图/类比，不堆术语）
4. 代码走读（精确文件 + 调用链 + 关键行；前后端各一段）
5. 手敲练习（任务、验收标准、提示、预期输出）
6. 5 道自测（答案附在文末）
7. 复盘卡（3 句话总结 + 1 个面试可讲点）

---

## 4. 掌握分级

| 级别 | 内容 | 要求 |
|------|------|------|
| ⭐ 必须掌握 | 半开区间冲突检测、Redisson 锁 + 事务顺序、幂等、状态机、MQ 消费幂等、登录/刷新链路、compose/Nginx 基本链路 | 不看代码能讲，能手写最小版本 |
| 🔍 理解即可 | 网关 JWT/身份头、AFTER_COMMIT/最终一致、ASR run token、缓存三大问题、COS 分片、DeepSeek 异步 worker、CI 四个 job、前端请求层/状态层 | 能讲清“为什么”和“在哪”，能定位代码 |
| ⚪ 了解/跳过 | 前端日历几何细节、ECharts 配置、Word 导出细节、后台 CRUD、限流 Lua 细节、traceId/审计细节、Redisson 看门狗、安全加固 | 知道存在、能查到即可 |

### 4.1 技术栈覆盖矩阵

> 掌握级别：⭐ 必须掌握 / 🔍 理解 / ⚪ 了解。
> “学习日”指五天计划中重点讲它的那一天；日常代码里遇到也会顺带讲。
>
> **岗位校准（1-3 年全栈）**：⭐ 只保留面试高频且项目核心的点；进阶内容降为 🔍/⚪，不要求手写源码。

#### 前端

| 技术点 | 项目落点 | 掌握 | 学习日 | 备注 |
|--------|----------|------|--------|------|
| React 18 + TS strict | `web/src/**`、`tsconfig.json` | 🔍 | Day 1-5 | 不教基础；重点讲组件边界、类型边界、渲染性能 |
| ahooks `useRequest` 二次封装 | `web/src/hooks/useAppRequest.ts` | ⭐ | Day 1 | 统一 loading/错误 toast/手动触发/`runAsync` |
| antd 5 | 页面与组件 | 🔍 | Day 1-5 | Form/Table/Drawer/App 上下文；主题 token |
| Zustand + immer + persist | `web/src/stores/*.ts` | ⭐ | Day 1/4 | 客户端状态 vs 服务端状态；sessionStorage 多 tab |
| React Hook Form + zod | `BookingDrawer.tsx`、`bookingSchema.ts` | 🔍 | Day 2 | 表单校验、提交幂等、防抖冲突提示 |
| Tailwind CSS | `index.css`、`tailwind.config.js` | 🔍 | Day 1 | 工具类负责布局；antd 负责组件，不是二选一 |
| ECharts | `pages/admin/Dashboard.tsx` | ⚪ | Day 5 顺带 | 图表数据映射即可，不深挖配置 |
| 组件化/封装思想 | `components/**` | ⭐ | Day 1-5 | 容器/展示分离、纯函数下沉、自定义 hook |
| axios 拦截器 + 刷新队列 | `api/request.ts` | ⭐ | Day 1 | 401 单飞刷新、请求重放、错误归一化、traceId |
| token / sessionStorage 多 tab | `api/token.ts` | ⭐ | Day 1 | 每 tab 独立会话；旧 localStorage 迁移 |
| 路由/守卫/布局 | `router.tsx`、`RouteGuards.tsx`、`MainLayout.tsx` | 🔍 | Day 1 | 路由级权限、登录态保持 |
| 虚拟滚动 | `components/minutes/TranscriptList.tsx` | ⭐ | Day 5 | `@tanstack/react-virtual`、动态行高、自动滚动 |
| WebSocket hook/store | `hooks/useWebSocket.ts`、`stores/notificationStore.ts` | ⭐ | Day 4 | 同源地址、token query、断线重连、状态不持久化 |
| 分片上传 hook | `hooks/useMultipartUpload.ts` | ⭐ | Day 5 | 并发、暂停/续传、AbortController、localStorage |
| 纯函数拆分 | `calendarGeometry.ts`、`calendarLayout.ts`、`transcriptSync.ts`、`bookingSchema.ts` | ⭐ | Day 2/5 | 可单测的逻辑不要塞进组件 |
| Vitest + Playwright | `web/tests/**`、`web/e2e/**` | 🔍 | Day 2/5 | 纯逻辑单测 vs 真浏览器 E2E 的分工 |
| Vite 构建/分包 | `vite.config.ts` | ⚪ | Day 5 | `manualChunks`、构建告警 |

#### 后端

| 技术点 | 项目落点 | 掌握 | 学习日 | 备注 |
|--------|----------|------|--------|------|
| Spring Boot 3 | 4 个服务 | ⭐ | Day 1 | 自动配置、starter、配置绑定 |
| Spring Cloud + Nacos | 服务注册/发现、配置中心 | 🔍 | Day 1 | 容器内 IP 探测；`@RefreshScope` |
| OpenFeign + 降级 | `feign/AuthFeignClient.java`、`AuthFeignFallbackFactory.java` | 🔍 | Day 1/4 | 超时、降级、circuit breaker |
| Spring Cloud Gateway | `gateway/**` | ⭐ | Day 1 | 路由、JWT 过滤器、CORS、WS 路由 |
| 负载均衡 | `lb://` + LoadBalancer | 🔍 | Day 1 | 与 Nacos 实例列表的关系 |
| JWT + 双 token | `JwtUtil`、`AuthService`、`RefreshTokenStore` | ⭐ | Day 1 | HS256、claims、access/refresh、轮换 |
| Spring Security | `CommonWebAutoConfiguration`、`@PreAuthorize`、`spring-security-crypto` | 🔍 | Day 1/4 | 只做方法级鉴权 + BCrypt，不是完整登录链 |
| RBAC 五表 | `sys_user/role/permission/...` | 🔍 | Day 1 | 用户-角色-权限；网关注入角色头 |
| MySQL 8 | 20 张表、Mapper XML | ⭐ | Day 1-3 | 索引、EXPLAIN、半开区间判重、无外键取舍 |
| MyBatis 手写 XML | `resources/mapper/*.xml` | ⭐ | Day 2/3 | resultMap、动态 SQL、`#{}` 参数化 |
| Redis 缓存 | `RoomService`、`CacheConfig`、`CalendarCacheEvictor` | ⭐ | Day 4 | 注解缓存 + 手写日历缓存；三大问题 |
| Redisson 分布式锁 | `service/MeetingService`、`lock/**` | ⭐ | Day 3 | 锁粒度、看门狗、锁包住事务 |
| 幂等 | `aop/IdempotentAspect` | ⭐ | Day 3 | SETNX、key 清理、失败重试 |
| 限流 | `aop/RateLimitAspect`、`RateLimitProperties` | ⚪/🔍 | Day 4 | ZSET + Lua 滑动窗口；Nacos 动态阈值 |
| RabbitMQ | `mq/MeetingEventPublisher`、`MeetingMqTopology`、`MeetingEventConsumer` | ⭐ | Day 4 | topic、手动 ACK、死信、消费幂等 |
| AFTER_COMMIT | `MeetingEventPublisher` | 🔍 | Day 4 | 事务提交后发消息；`fallbackExecution` |
| 状态机 | `statemachine/MeetingStateMachine` | ⭐ | Day 2/3 | 迁移表、CAS、状态日志 |
| DB 锁/乐观锁 | `MeetingMapper.xml` 行锁/version | ⭐ | Day 3 | 锁序、改期与审批的交叉竞态 |
| Spring Task | `task/**`、`ScheduleConfig` | 🔍 | Day 3/5 | 定时流转、补偿、ASR/AI worker |
| WebSocket | `notification-service/ws/**` | 🔍 | Day 4 | 握手、推送、网关转发 |
| 全局异常/Result | `GlobalExceptionHandler`、`Result`、`ErrorCode` | 🔍 | Day 1 | HTTP 200 + 业务码的约定 |
| ThreadLocal | `UserContext` | 🔍 | Day 1 | 上下文传递与清理 |
| AOP | `LogAspect`、`IdempotentAspect`、`RateLimitAspect` | ⭐/🔍 | Day 3/4 | 自定义切面；缓存/权限/异常不是同一种实现 |
| Lombok / Slf4j / Hutool | 全项目 | ⚪ | 日常 | 会用即可，不单独讲 |
| 对象存储 COS | `CosObjectStorage`、`MultipartUploadService` | 🔍 | Day 5 | 预签名、分片、ETag、断点续传；不是阿里 OSS |
| 腾讯 ASR | `TencentAsrClient`、`MinutesAsrService`、`AsrResultParser` | 🔍 | Day 5 | 提交/轮询/状态机/run token/解析 |
| DeepSeek | `DeepSeekLlmClient`、`MinutesAiService` | 🔍 | Day 5 | JSON mode、分块、重试、mock 切换 |
| TraceId / 审计 | `TraceIdFilter`、`LogAspect`、`AuditLogger` | ⚪/🔍 | Day 1/4 | MDC、日志脱敏、audit.log |
| 测试 | JUnit/Mockito、surefire | 🔍 | 每日 | 单测覆盖纯逻辑；E2E 覆盖真链路 |

#### 工程/部署

| 技术点 | 项目落点 | 掌握 | 学习日 | 备注 |
|--------|----------|------|--------|------|
| Dockerfile 多阶段 | `server/*/Dockerfile`、`web/Dockerfile` | 🔍 | Day 5 | Maven 缓存、非 root、jar 拷贝 |
| Docker Compose | `deploy/docker-compose.yml` | 🔍 | Day 5 | 9 常驻 + `mysql-init`、健康检查、容器 IP |
| Nginx | `deploy/nginx.conf` | 🔍 | Day 5 | SPA fallback、`/api`、`/ws`、`/files/local`、resolver |
| GitHub Actions | `.github/workflows/ci.yml` | 🔍 | Day 5 | service containers、docker-build、compose smoke |
| 部署验收脚本 | `scripts/verify-deploy.sh` | 🔍 | Day 5 | 15 项检查；本地/COS 模式切换 |
| 安全收口 | `.dockerignore`、`DB_USER=smt_app`、回环绑定 | ⚪/🔍 | Day 5 | 密钥不进镜像、最小权限、日志脱敏 |

### 4.2 需要纠偏的技术点

| 你的说法 | 项目实际 | 面试时怎么说 |
|----------|----------|--------------|
| 上传文件到 OSS | 使用腾讯云 COS，不是阿里 OSS | “对象存储用的是腾讯 COS，思路和 OSS 一样：预签名直传 + 分片 + 断点续传” |
| Spring Security | 没有完整登录过滤链；网关做 JWT 校验，服务内用 `@EnableMethodSecurity` + `@PreAuthorize`，密码用 BCrypt | “认证在网关，服务内是方法级鉴权；没有引入 Spring Security 的表单登录/OAuth2” |
| 拦截器 | 项目用的是 Servlet `Filter`（TraceId、UserHeader）和网关 `GlobalFilter`，没有 MVC `HandlerInterceptor` | “跨切面逻辑用 Filter 实现；能讲 Filter 与 Interceptor 的区别” |
| AOP 做日志/权限/缓存/异常 | 日志、幂等、限流是自定义 AOP；缓存是 Spring Cache 代理；权限是 Spring Security 方法级 AOP；异常是 `@RestControllerAdvice` | 分开讲，不要笼统说“都写在 AOP 里” |
| MyBatis / MyBatis-Plus | 以手写 XML 为主；MyBatis-Plus 只是依赖，未大量使用便利方法 | “SQL 可控、可审计，复杂区间查询用 XML 写” |
| Tailwind CSS | 与 antd 共存：Tailwind 负责布局工具类，antd 负责组件和主题 | 不要声称“纯 Tailwind”或“antd 被替代” |
| ahooks useRequest 二次封装 | `useAppRequest` 统一错误 toast、loading、manual/runAsync | 直接讲封装解决了什么问题 |
| Spring Task | 定时任务 + 调度线程池；项目没有 `@Async`/自定义线程池 | “异步主要靠定时任务和 MQ，不是 @Async” |

### 4.3 建议补充（按 1-3 年岗位校准）

**必须会（能讲清 + 能改代码）**

1. **分布式锁 + 幂等**：Redisson 基本用法、锁粒度、锁包住事务；`@Idempotent` + Redis SETNX
2. **事务边界 + 状态机**：`@Transactional`/`TransactionTemplate`、迁移表 + CAS；能讲清空窗和状态日志
3. **MQ 基本可靠性**：发布/消费、手动 ACK、消费幂等、失败重试；死信能讲概念即可
4. **缓存基础**：cache-aside、TTL、失效；能讲穿透/击穿/雪崩的概念和项目做法
5. **Docker/Nginx 基本链路**：镜像构建、compose 启动、Nginx 反代 `/api`/`/ws`；能排 502/端口问题

**理解即可（能讲清概念和项目落点）**

6. **AFTER_COMMIT / 最终一致**：为什么事务内不能直接发 MQ，项目怎么在提交后发
7. **缓存三大问题**：知道概念、能指到 `RoomService` / `CalendarCacheEvictor`
8. **COS/ASR/AI 链路**：预签名、分片、轮询、run token、JSON mode；能画链路，不深挖 SDK
9. **CI**：backend/frontend/compose/docker-build 四个 job 分别做什么

**了解即可（知道名字、能讲概念，不深挖）**

10. **限流**：滑动窗口 + Lua + Nacos 动态阈值，能讲思路，不要求手写 Lua
11. **可观测性**：traceId/MDC、actuator、审计日志，知道怎么串一次请求
12. **进阶细节**：Redisson 看门狗、Lua 细节、审计脱敏、最小权限/安全加固、镜像 digest、`mem_limit`

> 这 12 类不是 12 个必须手写的任务；真正要手写的只有 3 个（冲突检测、锁、幂等）。
> 其余以“能讲清 + 能定位代码”为准，避免把 30 小时摊薄。

### 4.4 不建议为了面试硬加的内容

- 完整 Spring Security 登录链 / OAuth2：项目未使用，讲了容易被追问细节
- 阿里云 OSS：项目用 COS；说“对象存储”即可，不要编造 OSS SDK 细节
- `@Async` / 自定义线程池：项目没有；如果问异步，讲 MQ 和定时任务
- React Query / SWR：项目用 ahooks `useRequest` 封装；不要声称用过
- ErrorBoundary / Suspense / 懒加载：项目未使用；可以作为“如果继续优化”的候选，不要当已实现讲

### 4.5 笔记引用策略

你的笔记在项目外：

- `项目实践/前端笔记/1.前端基础知识点.md`
- `项目实践/前端笔记/2.React知识点.md`
- `项目实践/前端笔记/3.Ts基础.md`
- `项目实践/前端笔记/4.前端工程化.md`
- `项目实践/后端笔记/10.Java基础.md`
- `项目实践/后端笔记/11.Spring Boot.md`
- `项目实践/后端笔记/12.MySQL+Redis+并发锁.md`
- `项目实践/后端笔记/13.微服务+部署.md`

教程不会大段复制笔记，而是用「📘 笔记引用」块：书名 + 章节 + 一句结论 + 项目落点/差异。

#### 4.5.1 四类引用

| 标签 | 含义 | 教程处理 |
|------|------|----------|
| **A 项目直接对应** | 笔记概念在项目里有实现 | 指到文件/方法，讲项目怎么用 |
| **B 项目有差异** | 笔记用 Ribbon/Sentinel/Druid/PageHelper/pnpm/Next.js 等，项目用替代方案 | 先讲笔记概念，再讲项目差异，面试时能对比 |
| **C 纯基础补充** | Java 基础/JS/HTTP/浏览器等，不强行关联项目 | 用“补充”块，面试基础题按笔记回答 |
| **D 了解/对比** | SSR/RSC/微前端/Monorepo/Sentinel 等，项目未使用 | 只讲概念和适用场景，不冒充项目经验 |

#### 4.5.2 引用格式

> 📘 **笔记引用**：《12.MySQL+Redis+并发锁》§五.1 并发控制的三个层次
> 笔记结论：JVM 锁 → 数据库锁 → 分布式锁，能用低层不用高层。
> 项目落点：`MeetingService` 用 Redisson 处理跨实例；`MeetingMapper` 用行锁/version。
> 差异/提醒：项目没有用 `synchronized` 解决跨实例；面试先讲“并发发生在哪一层”。

#### 4.5.3 每日引用映射

| Day | 笔记章节 | 类型 | 项目落点 |
|-----|----------|------|----------|
| 1 | 《11.Spring Boot》§一.4 Spring MVC 执行流程 | A | gateway → auth → meeting 的请求链 |
| 1 | 《11.Spring Boot》§五.3 拦截器与过滤器 | B | 项目用 Filter + 网关，不是 HandlerInterceptor |
| 1 | 《11.Spring Boot》§五.4 ThreadLocal | A | `UserContext` 保存当前用户 |
| 1 | 《13.微服务+部署》§二.1 Nacos / §二.4 Gateway | A | 服务注册、网关路由 |
| 1 | 《2.React知识点》§四.1 Hooks 基础 / §四.6 自定义 Hooks | A | `useAppRequest`、`useWebSocket` |
| 2 | 《12.MySQL+Redis+并发锁》§二.2 索引基础 / §二.3 SQL 优化 | A | 冲突检测 SQL、半开区间查询 |
| 2 | 《12...》§五.2 事务基础 / §五.3 隔离级别 | A | 创建预约的事务边界 |
| 2 | 《11.Spring Boot》§六.1 MyBatis / §六.2 MyBatis 原理 | A/B | 手写 XML、`resultMap` |
| 2 | 《2.React知识点》§二.4 受控/非受控 + §四.1 Hooks | A | `BookingDrawer` 表单 |
| 2 | 《3.Ts基础》§7 类型收窄 / §10 React 实战速查 | A/C | 状态类型、表单 schema |
| 2 | 《4.前端工程化》§4.1 测试分层 / §4.2 组件测试原则 | B | 项目用纯逻辑单测 + E2E，不引 jsdom |
| 3 | 《12...》§五.1 并发控制的三个层次 | A | JVM/DB/分布式锁的选型 |
| 3 | 《12...》§五.5 锁 / §五.6 乐观锁 vs 悲观锁 | A | 行锁、version、锁序 |
| 3 | 《12...》§四.4 分布式锁 / §四.5 Redisson | A | Redisson 锁 + 看门狗 |
| 3 | 《11.Spring Boot》§六.6 事务 @Transactional | A | `TransactionTemplate`、代理失效坑 |
| 3 | 《11.Spring Boot》§四.4 AOP | A | `IdempotentAspect`、`RateLimitAspect` |
| 3 | 《2.React知识点》§四.4 闭包陷阱 / §四.5 useEffect 异步坑 | A | 详情页 effect、请求竞态 |
| 3 | 《10.Java基础》§十一 Java 并发+JVM | C | 线程安全/JMM 基础题 |
| 4 | 《13...》§三 MQ / §三.4 消息可靠性 | A | RabbitMQ 手动 ACK、消费幂等、死信 |
| 4 | 《11.Spring Boot》§七.1 Redis 缓存 / §七.3 WebSocket | A | 缓存、通知 WS |
| 4 | 《12...》§四.1 缓存 / §四.2 缓存三个问题 / §四.3 Spring Cache | A | `RoomService`、`CalendarCacheEvictor` |
| 4 | 《2.React知识点》§五.4 现代状态管理选型 | A/B | Zustand vs Context/Redux |
| 4 | 《1.前端基础》§四 HTTP、运行环境 | C | WS/HTTP 基础 |
| 5 | 《2.React知识点》§四.6 自定义 Hooks / §四.5 useEffect 异步坑 | A | `useMultipartUpload`、轮询 |
| 5 | 《2.React知识点》§六 性能优化 / §七 原理深入 | A/B | 虚拟滚动、React 渲染机制 |
| 5 | 《1.前端基础》§三 Web API | A/C | File/Blob/Audio |
| 5 | 《4.前端工程化》§4 测试与 CI / §5 构建工具与性能 / §9 浏览器安全 | A/B/C | Vite 分包、CI、CORS/XSS |
| 5 | 《13...》§四 Docker / §五 Nginx / §六 上线 | A | Dockerfile、compose、Nginx |
| 5 | 《10.Java基础》§十 IO 流 / §六 序列化 | A/C | 分片上传、本地存储、JSON |

#### 4.5.4 必须标注的差异

| 笔记里的写法 | 项目实际 | 面试怎么说 |
|--------------|----------|------------|
| Ribbon 负载均衡 | Spring Cloud LoadBalancer（`lb://`） | “Ribbon 是旧方案，项目用 LoadBalancer；原理都是按实例列表选一个” |
| Sentinel 流控/熔断 | Resilience4j + Feign fallback | “项目用 Resilience4j 做熔断降级，限流是自研 ZSET+Lua” |
| Druid 连接池 | HikariCP（Spring Boot 默认） | “连接池原理相通，项目没额外引 Druid” |
| PageHelper 分页 | 手写 `PageBounds` / `PageResult` + `LIMIT` | “分页 SQL 自己写，便于控制 count 和排序” |
| pnpm workspace | npm + package-lock | “项目用 npm；pnpm 的硬链接/严格依赖是加分项，不是项目经验” |
| Next.js / SSR / RSC | Vite + React 18 SPA | “项目是 SPA；SSR/RSC 我了解概念，但没有生产经验” |
| 完整 Spring Security 登录链 | 网关 JWT + `@PreAuthorize` 方法级鉴权 + BCrypt | “认证在网关，服务内做方法级鉴权” |
| `BaseContext` | `UserContext` | 同一个 ThreadLocal 思路，类名不同 |
| HandlerInterceptor 鉴权 | Filter + 网关 `GlobalFilter` | “项目把鉴权放在网关和 Filter；拦截器/过滤器区别我能讲” |
| `@Async` / 自定义线程池 | 定时任务 + MQ | “异步主要靠 MQ 和定时任务，没有用 @Async” |
| Vue vs React | React 18 | 只作为对比题，不说项目用 Vue |

#### 4.5.5 纯了解/不强行关联

- 《1.前端基础》HTML/CSS/浏览器安全/SQL 注入 → C 类，基础题按笔记回答
- 《2.React知识点》SSR/Hydration/RSC/React 19 → D 类，项目未使用
- 《3.Ts基础》条件类型/infer/工具类型 → C/D 类，能看懂即可
- 《4.前端工程化》Monorepo/微前端/Qiankun/Next.js → D 类
- 《10.Java基础》JVM/设计模式/JS 对比 Java → C 类
- 《11.Spring Boot》Bean 生命周期/循环依赖 → C/D 类
- 《12...》主从复制/分库分表/Redis 高级 → D 类
- 《13...》Sentinel/Ribbon/完整上线清单 → B/D 类，讲差异即可

#### 4.5.6 写作要求

- 每篇日教程至少 **3 个笔记引用**，至少 1 个 A 类、1 个 B/C/D 类
- 引用必须给章节名，不复制大段原文；必要时只引 1-2 行
- 笔记与项目不一致时，明确写“笔记是 X，项目是 Y，面试怎么说”
- 纯了解内容不放进“必须掌握”检查点，避免 30 小时被摊薄

---

## 5. 五天日程

### Day 1：全栈全景 + 登录/权限链路 + 0→1 跑通（6h）

**目标**

- 能画出 4 服务架构和登录请求链路
- 能启动 infra / 后端 / 前端，并解释每个端口
- 能用文件地图按业务定位后端和前端文件

**后端走读**

- `server/gateway/.../filter/JwtAuthFilter.java`：JWT 验签、白名单、身份头注入
- `server/auth-service/.../controller/AuthController.java`、`service/AuthService.java`、`store/RefreshTokenStore.java`
- `server/meeting-common/.../filter/UserHeaderFilter.java`、`TraceIdFilter.java`、`util/JwtUtil.java`
- `server/meeting-common/.../result/Result.java`、`ErrorCode.java`

**前端走读**

- `web/src/main.tsx`、`router.tsx`、`components/RouteGuards.tsx`
- `web/src/pages/Login.tsx`、`layouts/MainLayout.tsx`
- `web/src/api/request.ts`（拦截器、刷新队列、错误归一化）
- `web/src/api/token.ts`（sessionStorage 多 tab 隔离）
- `web/src/stores/userStore.ts`

**手敲**

- 用 curl + 浏览器 Network 跟踪一次登录：`POST /api/auth/login` → token → 带 token 请求
- 在 `request.ts` 或测试里加一条请求日志/断言（不改业务语义）

**检查点**

- 能口述登录链路：浏览器 → Nginx/Vite → gateway → auth → JWT → 身份头 → meeting
- 能解释 401 后刷新 token 的队列机制

---

### Day 2：预约核心 + 日历/预约表单（6h）

**目标**

- 讲清状态机 6 状态和迁移规则
- 讲清半开区间冲突检测和 buffer
- 能读懂日历布局和预约表单

**后端走读**

- `state machine/MeetingStateMachine.java` + 迁移表
- `service/ConflictChecker.java` + `MeetingMapper.xml` 冲突 SQL
- `service/MeetingService.java` 创建预约校验链
- `service/AvailabilityService.java`、`MeetingQueryService.java` 日历接口
- `entity/Meeting.java`、`RoomStrategy.java`、`enums/MeetingStatus.java`

**前端走读**

- `pages/Calendar.tsx`、`components/CalendarGrid.tsx`
- `components/calendarGeometry.ts`、`calendarLayout.ts`
- `pages/BookingDrawer.tsx`、`pages/bookingSchema.ts`
- `pages/MeetingList.tsx`

**手敲**

- 手写 `hasConflict(existing, candidate, buffer)` 半开区间函数 + 单测
- 覆盖：相邻、交叉、包含、相同、buffer 边界

**检查点**

- 单测全绿
- 能解释为什么用 `[start, end)` 而不是闭区间

---

### Day 3：并发/锁/幂等 + 详情/状态（6h）

**目标**

- 讲清 Redisson 锁粒度、锁与事务顺序、改期也要加锁
- 讲清幂等 key 和乐观锁
- 能读懂详情页的状态门槛和操作

**后端走读**

- `service/MeetingService.java` 预约锁 + `TransactionTemplate`
- `aop/Idempotent.java`、`IdempotentAspect.java`
- `MeetingMapper.xml` 的 `version` 乐观锁
- `service/MeetingService.java` 改期/取消
- `scripts/concurrent-reserve.sh`、`concurrent-modify.sh`

**前端走读**

- `pages/MeetingDetail.tsx`
- `components/MeetingEditDrawer.tsx`（打开时才加载会议室）
- `components/CancelMeetingModal.tsx`
- `components/MeetingStatusTag.tsx`
- 状态时间线/操作按钮的权限门槛

**手敲**

- 先在没有锁的测试/分支里复现双重预订（或用脚本观察）
- 加最小 Redisson 锁 + 锁内检测，跑 `concurrent-reserve.sh 10 --repeat 10`
- 手写幂等 key 的生成/清理逻辑 + 单测

**检查点**

- 并发脚本 10 轮“恰好 1 个成功”
- 能解释“锁释放 → 事务提交”的空窗问题

---

### Day 4：审批/MQ/通知/WS + 审批/通知前端（6h）

**目标**

- 讲清待审批不占时段 + 审批复检 + 锁序
- 讲清 AFTER_COMMIT、消费幂等、手动 ACK、死信
- 能读懂审批中心、通知中心和 WS 联动

**后端走读**

- `service/ApprovalService.java`（复检 + 会议室行锁 + 会议行锁）
- `mq/MeetingEventPublisher.java`、`MeetingMqTopology.java`
- `notification-service/.../mq/MeetingEventConsumer.java`、`ConsumeIdempotency.java`
- `notification-service/.../service/NotificationService.java`
- `notification-service/.../ws/NotificationWebSocketHandler.java`、`NotificationPushListener.java`
- 侧栏（🔍 理解）：缓存 `RoomService` + `CalendarCacheEvictor` + `CacheConfig`、
  限流 `RateLimitAspect`、traceId、审计 `LogAspect`

**前端走读**

- `pages/ApprovalCenter.tsx`、`components/ApprovalDrawer.tsx`
- `pages/NotificationList.tsx`
- `hooks/useWebSocket.ts`
- `stores/notificationStore.ts`
- `components/notificationRouting.ts`

**手敲**

- 给现有 `MeetingEventPublisher` 加一个 AFTER_COMMIT 测试（手写实现可选）
- 写消费幂等：同一 msgId 第二次不落库（测试或脚本）
- 观察 WS 通知从 MQ → notification-service → 浏览器

**检查点**

- MQ 幂等测试通过
- 能解释为什么不能在事务内直接发 MQ

---

### Day 5：会议纪要 + 部署/CI + 面试彩排（6h）

**目标**

- 讲清 COS 分片、ASR 状态机、语音文本联动、DeepSeek 异步
- 讲清 compose/Nginx/CI 的请求路径和故障点
- 完成 15 分钟演示

**后端走读**

- `service/MultipartUploadService.java`、`CosObjectStorage.java`、`LocalObjectStorage.java`
- `service/MinutesAsrService.java`、`TencentAsrClient.java`、`AsrResultParser.java`、`task/MinutesAsrTask.java`
- `service/DeepSeekLlmClient.java`、`service/MinutesAiService.java`、`task/MinutesAiTask.java`
- `service/MinutesService.java`、`controller/MinutesController.java`

**前端走读**

- `components/minutes/MinutesUploader.tsx`
- `components/minutes/AudioTranscriptWorkspace.tsx`
- `components/minutes/TranscriptList.tsx`
- `utils/transcriptSync.ts`
- `components/minutes/MinutesSummary.tsx`、`MinutesInsights.tsx`
- `hooks/useMultipartUpload.ts`、`components/minutes/minutesUi.ts`

**部署/CI 走读**

- `server/*/Dockerfile`、`web/Dockerfile`
- `deploy/docker-compose.yml`、`deploy/nginx.conf`
- `.github/workflows/ci.yml`、`scripts/verify-deploy.sh`

**手敲**

- 写 ASR 结果解析的单测；状态迁移能讲清即可
- 写 `splitSentences` 的边界测试（标点、空段、比例时间）
- 跑 `bash scripts/verify-deploy.sh`（15/15）
- 15 分钟模拟演示 + 10 题追问

**检查点**

- `verify-deploy` 15/15
- 能画“音频 → COS → ASR → 转写 → AI → 导出”全链路
- 15 分钟演示不卡壳

---

## 6. 文件地图规范

### 6.1 后端

- 范围：`server/**/src/main/java/**/*.java`，共 222 个生产文件（不含测试）
- 分组：`meeting-common`、`gateway`、`auth-service`、`meeting-service`、`notification-service`，再按 package
- 每行字段：`文件 | 中文职责 | 关键类/方法 | 掌握级别`
- Controller / Service / Mapper / 核心配置写得详细；DTO/VO/Entity 一行带过
- 额外“按业务找文件”索引：登录、预约、审批、通知、纪要、部署

### 6.2 前端

- 范围：`web/src/**`，共 66 个源文件（不含 `web/tests`、`web/e2e`、构建产物）
- 分组：`api`、`components`、`components/minutes`、`hooks`、`layouts`、`pages`、`pages/admin`、`stores`、`theme`、`utils`、根文件
- 每行字段：`文件 | 中文职责 | 关键点 | 掌握级别`
- 页面/复杂组件详细；类型定义、入口文件一行带过
- 额外“按业务找文件”索引：登录、日历、预约、审批、通知、纪要、后台管理

### 6.3 排除项

- 测试文件（`src/test`、`web/tests`、`web/e2e`）
- 构建产物（`target`、`dist`、`node_modules`）
- 生成文件/锁文件（`package-lock.json` 等）
- 图片/字体等静态资源

---

## 7. 每日教程模板

每篇日教程必须包含：

1. **今日目标**：3 条，能对应到检查点
2. **面试先问**：3-5 个问题，正文里逐一回答
3. **10 分钟概念**：一张 ASCII 图或一个类比
4. **代码走读**：文件路径 + 调用链 + 关键行；后端一段、前端一段
   - **📘 笔记引用**：至少 3 条，标注 A/B/C/D；A 类指到项目文件，B 类写差异，C/D 类明确“了解即可”
5. **手敲练习**：任务、验收标准、提示、预期输出
6. **5 道自测**：选择题/简答题，答案在文末
7. **复盘卡**：3 句话总结 + 1 个面试可讲点

写作要求：

- 所有文件路径必须真实存在
- 代码片段只引用关键行，不整文件粘贴
- 每个“必须掌握”点至少有一个追问示例
- 明确标注 ⭐/🔍/⚪，避免平均用力
- 每篇至少 3 个笔记引用，其中至少 1 个 A 类；引用格式见 4.5.2
- 笔记与项目不一致时，必须写清“笔记是 X，项目是 Y，面试怎么说”

---

## 8. 验收与检查点

| 时间 | 检查点 | 通过标准 |
|------|--------|----------|
| Day 1 结束 | 架构 + 登录链路 | 能不看文档画出 4 服务和登录时序 |
| Day 2 结束 | 冲突检测 | 手写函数 + 单测全绿 |
| Day 3 结束 | 并发/锁/幂等 | `concurrent-reserve.sh` 10 轮通过 |
| Day 4 结束 | MQ/通知 | 幂等测试通过 + WS 通知可见 |
| Day 5 结束 | 纪要 + 部署 + 面试 | `verify-deploy` 15/15 + 15 分钟演示 |

每天 5 道自测；Day 5 额外做 10 题模拟追问。

---

## 9. 风险与调整

| 风险 | 应对 |
|------|------|
| 30 小时不够 | 砍 `CalendarGrid` 几何细节、缓存/限流深挖、ECharts/Word 导出；保护 Day 3 锁和 Day 5 纪要 + 部署 |
| 某天卡住超过 1 小时 | 先保留“能讲清思路”，跳过实现，Day 5 复盘补 |
| 前端细节过多 | 只讲组件封装、状态管理、复杂交互；不教 React 基础 |
| 真实云服务不可用 | 用 `local + mock`；真实联调作为证据讲述，不现场依赖 |
| 文件地图工作量大 | 先按目录生成骨架，再补核心文件；DTO/VO 一行带过 |

---

## 10. 非目标

- 不从零重写整个项目
- 不逐行讲所有测试文件
- 不深挖 ECharts 配置、Word 导出、日历几何的所有细节
- 不教 Java/Spring/React 入门
- 不把项目扩成 K8s/多租户/计费系统

---

## 11. 成功标准

学完并完成检查点后，应能：

- 用 15 分钟讲完项目，且能回答 20+ 个追问
- 手写 3 个核心片段并解释取舍
- 按业务在 1 分钟内定位到前后端文件
- 说清“哪些是必须掌握、哪些是理解、哪些是了解”，不平均用力

---

## 12. 交付顺序

1. 先写 `docs/09-全栈学习计划.md`（总览 + 日程 + 掌握矩阵）
2. 再写 5 篇日教程
3. 同步生成 `docs/10-前后端文件地图.md`
4. 最后统一检查：文件路径存在、链接可跳转、检查点可执行

> 具体编写任务拆解和验收步骤见后续 implementation plan。
