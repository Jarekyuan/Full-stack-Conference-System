# M1 代码评审报告

> 评审时间：2026-10-14 | 范围：M1 全部产出（后端 19 任务 + 前端 4 任务）
> 方法：4 个审查分身分模块深挖（common+gateway / auth / meeting / web），关键指控逐条**人工复核**后定级
> 定级：**P0** = 确证 bug/安全问题（必修）｜**P1** = 隐患或重要遗漏｜**P2** = 优化建议

---

## 一、总览

| 模块 | P0 | P1 | P2 |
|------|----|----|----|
| meeting-common + gateway | 0 | 5 | 10 |
| auth-service | **1** | 6 | 11 |
| meeting-service | 0 | 6 | 12 |
| web 前端 | 0 | 5 | 17 |

**结论先说**：没有发现会导致数据错乱的严重 bug，核心链路（登录/双 token/鉴权/会议室管理）是通的。
问题集中在三类：**① 失效彻底性（登出/停用/刷新）② 契约与文档不一致 ③ 工程化短板（无 lint/无测试/无分包）**。

---

## 二、P0（必修）

### P0-1 auth：refresh 滚动更新非原子，并发刷新竞态
- **位置**：`auth-service/.../store/RefreshTokenStore.java:31-38` + `AuthService.java:54-64`
- **问题**：`save()` 是"读旧值→删旧反向索引→写用户索引→写 token 索引"四步非原子。两个请求持同一 refreshToken 并发刷新时：都读到旧值且都判有效，后写者覆盖用户索引，且**不会删除先写者刚写入的反向索引** → 先写者拿到的 refreshToken 立即失效（被迫重登），并残留 7 天脏索引。
- **复核**：✅ 代码确认非原子读写。
- **建议**：滚动更新收敛为单条 **Redis Lua 脚本**（DEL 旧反向键 + SET 两键同 TTL），一次往返原子完成。前端 single-flight 只是"正常路径"的缓解，服务端必须自保。

---

## 三、P1（重要，建议修）

### 安全 / 越权

**P1-1 auth：`/users/batch` 无任何鉴权（越权信息泄露）** ✅复核
- `UserController.java:42-45`，`@PostMapping("/batch")` 无 `@PreAuthorize`，网关按 `/api/users/**` 放行 → **任意登录用户可批量拉取用户 realName+email（单次 100 条，可枚举）**。
- 该接口定位"服务间内部"却未登记进 04 文档 3.1（违反真相源铁律）。
- 建议：生产让内部接口不经网关暴露（独立前缀 + 网关白名单排除），或加服务间调用凭证；同步补登 04 文档。

**P1-2 配置：默认凭据入库（3 处）** ✅复核
- `auth/gateway/meeting` 的 `application.yml` 均含 `${DB_PASSWORD:root123}`、`${JWT_SECRET:smt-dev-secret-...}`，且文件已被 git 跟踪。
- 违反 `AI-RULES.md` 第四章"真实密钥只进 application-local.yml"。**生产漏配环境变量 = 用公开仓库里的已知密钥签发 token**。
- 建议：默认值下沉到被 gitignore 的 `application-local.yml`；生产配置去掉默认值（缺失即启动失败）；轮换当前密钥。

**P1-3 auth：登出 / 停用不彻底（accessToken 30 分钟真空期）**
- 登出只作废 refreshToken（`AuthService.java:66-72`）；`UserService.updateStatus` 停用用户既不删 refreshToken 也无黑名单 → **被停用/已登出者的 accessToken 在剩余有效期内仍可通行**（ADMIN 被停用仍能调管理接口）。
- 建议：登出/停用时把 accessToken 的 `jti` 写 Redis 黑名单（TTL=剩余有效期），网关 `JwtAuthFilter` 加黑名单校验；或缩短 accessToken TTL 兜底。

**P1-4 auth：登录无防爆破**
- `/auth/login` 无限流/无失败计数/无锁定，可高速撞库；02 文档只约定了预约接口限流，认证域遗漏。
- 建议：IP+用户名维度滑动窗口限流（Redis ZSet）。

### 契约 / 一致性

**P1-5 web：redirect 链路断裂（功能是死代码）** ✅复核
- `router.tsx:19` 守卫跳转只写 `<Navigate to="/login" replace />`，`request.ts:71-73` 刷新失败也是硬跳 `/login`（不带 query）；而 `Login.tsx:43` 读 `searchParams.get('redirect')` → **永远 null，"登录后回原页"实际不可用**。
- 建议：守卫处拼 `?redirect=`（含 `location.pathname + search`），request 层复用同一构造函数；Login 侧对 redirect 做 `/` 前缀白名单校验（防开放重定向），默认回落 `/calendar`。

**P1-6 meeting：会议室列表缺 device 筛选** ✅复核
- 04 文档 3.2 明确"筛选：groupId/capacity/status/**device**"，代码只有前三个。
- 建议：补 `JSON_CONTAINS(device_tags, JSON_QUOTE(#{device}))`（03 文档 104 行已给方案）。

**P1-7 meeting：新建会议室的默认策略值与 schema/种子/文档不一致** ✅复核
- `RoomService.java:72` 写 `cancelDeadlineMinutes=30`，但 `db/01-schema.sql:80` 默认 **0**、种子青竹厅为 0、注释还自称"对齐种子标准厅"——**新建的会议室自带一个与种子不同的策略，埋不一致坑**。
- 建议：改为 0，或经业务确认后反向修正 schema/种子/03 文档。

**P1-8 meeting：`updateStrategy` 先更后插非原子** ✅复核
- `RoomService.java:118-120`：`UPDATE 影响 0 行则 INSERT`，并发下会撞 `uk_room` 唯一键抛 500。
- 建议：改 `INSERT ... ON DUPLICATE KEY UPDATE` 原子 upsert（或先 `selectByRoomId` 判存在）。

**P1-9 meeting：create/update 未校验 groupId 存在**
- 可写入悬空 `group_id`，列表 LEFT JOIN 后 `groupName` 恒 null（无外键约束）。
- 建议：新增 `RoomGroupMapper.selectById` 校验存在性。

**P1-10 common：未认证兜底返回 403/200+10002，与文档 401 约定不符**
- `GlobalExceptionHandler.java:49,56`：匿名访问受保护方法抛的是 `AuthorizationDeniedException`（AccessDenied 子类），走 403 分支，`handleAuthentication` 近乎死代码；`AuthService.logout/currentUser` 无身份时抛 UNAUTHORIZED 被包成 HTTP 200 + code 10002。
- **实际影响有限**（经网关的正常链路里，无 token 被网关 JwtAuthFilter 拦成 401，✅已实测），属直连服务/兜底路径的不一致，但会让前端"只认 401 刷新"的约定在兜底场景失效。
- 建议：`handleAccessDenied` 内判 `Authentication` 是否匿名 → 401（否则 403）。

**P1-11 common：框架异常被兜底吞成 500**
- 日志/请求体 JSON 格式错（`HttpMessageNotReadableException`）应 400、路径变量类型错应 400、方法不支持应 405，现全部 500 + 10000。
- 建议：`GlobalExceptionHandler` 补这些显式映射（或继承 `ResponseEntityExceptionHandler`）。

**P1-12 web：登录/表单失败产生 unhandled rejection**（源码级验证：RHF 重抛 + rc-field-form 仅同步 try/catch）
- `Login.tsx:40-45`、`RoomManage.tsx`（onOk）、`StrategyDrawer.tsx:67-73`：`handleSubmit(onSubmit)` 的 rejected promise 无人 catch，每次密码错/保存失败控制台都打 `Uncaught (in promise)`。
- 建议：`void handleSubmit(onSubmit)().catch(() => {})`（错误 toast 已由 useAppRequest 统一做）。

**P1-13 web：类型与后端契约不一致**
- `room.ts:23` 声明 `location: string` 必填，调用侧却写 `room.location ?? ''`（运行时可空）；`deviceTags`、`images` 同理；`Result.data: T|null` 的 null 被拦截器吞掉，void 接口实际返回 null 而类型承诺非空。
- 建议：对照后端 `RoomVO` 校正为 `string | null` / `string[] | null`；`http` 方法签名体现可空。

**P1-14 web：token 两处真相 + 登出语义不一致**
- `request.ts:68-74` 刷新失败只 `clearTokens()`，**不清 `meeting.user`** → localStorage 残留上一用户的 realName/role 等 PII；正常登出则走 `userStore.logout()` 全清。
- 建议：收敛为单一 `clearSession()`（clearTokens + userStore.getState().logout()），两条路径共用。

**P1-15 工程化：前端无 lint / 无测试 / 单包 1.35MB 无懒加载**
- 全库 0 处 ESLint/Prettier 配置（AGENT.md 的纪律全靠自觉）；无 vitest（request.ts 的高价值逻辑零保护）；router 静态 import 全部页面，产物单包 1.35MB（antd 全量 + echarts 预装未用）。
- 建议：① ESLint + `lint` 脚本前置到 build；② vitest+msw 补 request.ts 四个用例（并发 401 只刷一次/刷新失败清会话/重放不丢参/幂等静默）；③ `React.lazy` 路由级拆分 + `manualChunks`（vendor-react/vendor-antd）。

### 从容错与可观测

**P1-16 meeting：Feign 降级语义歧义 + 无超时配置**
- 降级返回 `Result.success(List.of())` 与"真查不到用户"无法区分，而 02 文档 3.1#4 要求**fail-open**（放行+告警），调用方极易误判为"用户都不存在"而 fail-close。
- 未配 Feign 超时（默认 read 60s）与 Resilience4j 实例参数，auth 卡顿时会拖慢调用线程而非快速熔断。
- 建议：降级返回带标记结构；显式配 connect/read timeout（1s/2s）+ 熔断阈值。

**P1-17 gateway：X-Trace-Id 与 CORS 未实现；/ws 未放行**
- 04 文档 1.5 要求网关生成 `X-Trace-Id` 全链路透传 —— 未实现；02 文档 3.1#6 要求网关承担 CORS —— 未配置；
- `JwtAuthFilter` 未放行 `/ws/**` 且只读 `Authorization` 头，而 04 文档 6.1 规定 WS 用 `?token=` 握手 → **M4 的 WebSocket 必 401**（现在不阻塞，M4 前必须修）。

---

## 四、P2 优化清单（择机批量清理）

**common/gateway**：`@RestControllerAdvice` 未加 `@Order`；`UserHeaderFilter` 因是 Filter Bean 被容器与 Security 链双重注册（当前无害但生效位置隐式）；`JwtUtil` 注释写 HS256、实际 48 字节密钥由 jjwt 自动选 **HS384**（建议显式 `signWith(key, Jwts.SIG.HS256)` 或修注释）；白名单 `/actuator/**` 应收敛为 `/actuator/health,info`；`AuthProperties` 前缀过泛 + secret 无 `@NotBlank`；`Bearer ` 前缀应大小写不敏感。

**auth**：`selectByIds` 空列表生成 `IN ()` 语法错（当前被 `@NotEmpty` 挡住）；`searchByKeyword` 未过滤 status、LIKE 通配符未转义（输入 `%` 返回任意 20 条）；`offset` int 溢出风险；refreshToken 明文存 Redis（建议存摘要）；同用户单 refreshToken 导致多端互踢（需确认是否符合预期）；生产恒开 SQL debug 日志；`LoginResponse` 有过期注释（T1.11 已填充）。

**meeting**：动态 `<set>` 无兜底（全空时 SQL 语法错）；`JsonTypeHandler` 遇字符串 `"null"` 返回 null 破坏"空→空列表"约定；复杂 SQL 未按 AI-RULES 标注预期索引；删除会议室"先 count 再 delete"存在窗口（建议 `FOR UPDATE`）；`countActiveMeetingsByRoom` 口径 `(10,20,30)` 与冲突检测 `(20,30)` 不一致（需确认是否把"待审批"算占用）；`AuthFeignClientTest` 两个测试互斥（本地 `mvn test` 必挂一个）；列表参数无 `@Min/@Max`（status=99 静默空列表）；会议室/分组列表无缓存（01 文档 B-4 提过 Redis 缓存）。

**web**：无 `path:'*'` 404 兜底 + 无 ErrorBoundary；`selectedKeys=[pathname]` 导致 `/meetings/123` 菜单高亮丢失；`Select mode="tags" open={false}` 是反 antd hack；设备标签 `key` 用文本（重复标签 key 冲突）；`columns` 未 memo；RoomManage 手写 useEffect 绕过 refreshDeps 且 StrictMode 下双发请求；`getCurrentUser()` 定义未用（且暴露"刷新后不校准 role"的真实问题）；zustand persist 无 `version/migrate`；`echarts` 预装未用；通知铃铛无 onClick 却带 pointer 光标；Dropdown 触发元素无键盘/ARIA 支持；`roomSchema` 文案重复 3 遍；错误码散落魔法数（建议 `const ERROR_CODE`）。

---

## 五、修复优先级建议

| 批次 | 内容 | 理由 |
|------|------|------|
| **第 1 批（低风险高收益）** | P0-1 refresh 原子化、P1-1 `/users/batch` 鉴权、P1-5 redirect 闭环、P1-12 reject 收敛、P1-7 默认策略值、P1-8 upsert 原子 | 都是独立小改动，直接消除确证问题 |
| **第 2 批（安全加固）** | P1-2 凭据外移、P1-3 登出彻底性、P1-4 防爆破、P1-16 超时配置 | 涉及机制设计，建议成组做 |
| **第 3 批（工程化）** | P1-15 ESLint + vitest + 路由懒加载分包 | 为 M2 及后续任务兜底 |
| **第 4 批（契约对齐）** | P1-6 device 筛选、P1-9 groupId 校验、P1-10/11 异常映射、P1-13 类型校正、P1-17 traceId/WS | 可与 M2 相关任务合并做 |
| 择机 | P2 全量 | 功能收尾阶段批量清理 |

---

## 六、已核实"没毛病"的项（加分项）

- **错误码全表 29 条与 04 文档 2.2 逐条零偏差**（码值+文案），响应结构 `Result/PageResult` 一致；
- **网关防伪造头彻底**：大小写变体、多值头均被 `remove` 覆盖，先剥离后注入，白名单路径也剥离，未发现绕过；
- **白名单不可绕过**：精确匹配 + 路径解码，构造不出"被放行且转发到非白名单服务"的组合；
- **全库 SQL 零注入**：无 `${}` 滥用，手写 XML 全 `#{}`；resultMap 字段零漏映射；分页 LIMIT+COUNT 正确；
- **VO 零密码泄露**（LoginResponse.User/UserVO/UserBriefVO 均不含 password）；
- **前端零 `any`、零 `@ts-ignore`、零 `dangerouslySetInnerHTML`、零 console**；tsc strict 通过；
- **401 single-flight 经代码级实证正确**（无竞态、`_retry` 阻断死循环、重放不丢参数），且 E2E 实测"3 并发 401 只发 1 次刷新"。
