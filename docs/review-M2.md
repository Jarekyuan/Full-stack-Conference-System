# M2 全量评审报告（含 M1 存量项状态复核）

> 评审时间：2026-10-15　范围：M1 之后的全部代码（M2 后端 10 任务 + M2 前端 3 任务 + 当日并发修复）+ M1 未决项复核
> 方法：5 个只读评审分身分模块深挖（common+gateway / auth / meeting 写路径 / meeting 读路径+SQL / 前端），
> 各自对**运行中的本机实例**做只读实测；结论由主评审**逐条人工复核**后才收录。凡未运行时复现的，均标注「未实测」。
> 上游：`docs/review-M1.md`（M1 评审，P0×1 / P1×17 / P2×40+，仅第 1 批已修）

---

## 一、总览

**结论：REQUEST CHANGES。4 条 Critical、12 条 Required。**

这一轮的 Critical 有一个共同特征：**不是"代码写错了"，而是"信任模型没闭环"**——

| # | Critical | 实质 | 复现状态 |
|---|----------|------|---------|
| C1 | Redis 无认证 + 明文 refreshToken + 端口全网卡 → **账号接管链** | 基础设施默认配置 | ✅ 已复现（前四环实测，最后一环刻意未执行） |
| C2 | 业务端口绑 0.0.0.0 + 服务无条件信任身份头 → **伪造 ADMIN / 内部接口零凭据** | 部署前提无人执行 | ✅ 已复现（读操作实测；写操作代码级推断） |
| C3 | 分布式锁 acquire 异常收尾不全 → **某会议室某日永久订不上** | 异常路径漏释放 | ⚠️ 未实测（控制流逐行可查，确定性成立） |
| C4 | `updateWithVersion` 不校验 status → **已取消的会议还能被改期**（R-12 被绕过） | 层与层接缝靠人肉纪律 | ⚠️ 未实测（需人工交错时序） |

M1 的 17 条 P1 里，**11 条仍未修**（详见第五章）。

> **一句话给面试用**：这套代码的"经网关"链路是安全的（剥离-重注入闭环、路径穿越防护、@PreAuthorize 全覆盖都已实测有效）；
> 漏洞全部出在"绕开网关"的路径和"基础设施默认值"上——而这恰好是讲微服务安全边界最好的素材。

### 修复进度

| 批次 | 范围 | 状态 | 提交 |
|------|------|------|------|
| 第 1 批 | **安全止血**：C1（基础设施端口收环回 + refreshToken 只存 SHA-256 摘要）、C2（三服务 `server.address: 127.0.0.1`）、R1（分页 page 上限，两个服务）、R2（登录时序陪跑哈希）、R3（`/auth/me` 校验账号状态） | ✅ **已完成并验证** | 见 fix 提交 |
| 第 2 批 | 并发正确性：C3（锁 acquire 收尾）、C4（`updateWithVersion` 补 status 守卫）、R5（幂等语义）、`cancel` 进锁 | ✅ **已完成并验证** | `eafda49` |
| 第 3 批 | 前端可见缺陷：R9~R15 + R16（E2E 入库）+ 两个"下一个调用方会踩"的加固 | ✅ **已完成并验证** | `79a9609` |
| 第 4 批 | 契约与工程：R7（roomIds 入参上限）、R8（详情 email PII 收口）、R4（悬空 deptId/roleIds）、C1 剩余项（Redis `requirepass`）、P1-2/6/9/10/11/13/14/15/16/17 | ✅ **已完成并验证** | 见下 |

> 第 4 批验证方式（逐条实测，脚本入库为 `scripts/verify-review-batch4.sh`，**33/33 通过**）：
> `roomIds` 50 个放行 / 51 与 52 个回 `10001 一次最多查询 50 个会议室`（边界两侧都测）/ 重复 id 去重成 1 条 / 负数被拒；
> 会议详情 `participants` 已无 `email` 字段；`/users/search` 只回 `id/realName/deptName` 且 `deptName` 由 JOIN 直出（此前恒为 null）；
> `?device=投影仪` 筛出的房间都含该标签、不存在的标签回空列表；`groupId=99999` / `deptId=99999` / `roleIds=[99999]` 均 `10001`；
> 框架异常四类：参数类型错与缺参 → `10001`、错方法 → HTTP **405** + `10008`、错 Content-Type → HTTP **415** + `10009`（修复前四类均 500）；
> 匿名直连 8081 打 ADMIN 接口 → **401 + 10002**（修复前 403）、已登录 USER 打同一接口仍 403；
> 网关响应头带 `X-Trace-Id`（含 401 响应）、自带合法 traceId 被沿用、非法 traceId 被重新生成；
> OPTIONS 预检 → **200 + `Access-Control-Allow-Origin` + `Access-Control-Expose-Headers`**（修复前 401）。
> 回归：`concurrent-reserve`（会议室 4）**10/10**、`concurrent-modify` **10/10**、三个 E2E **11/11 + 19/19 + 19/19**、
> 后端单测 **45 例 0 失败**（含定向跑通的 Feign 降级：`WARN [Fallback] … cause=ServiceUnavailable`，返回 `10007` 且 data 为空）、
> 前端 `tsc` + `eslint` 零问题、**vitest 26 例**、`vite build` 分包后 app chunk 由 1.36MB 降至 55KB（vendor 三包独立缓存）。
> Redis：免密 `PING` → `NOAUTH Authentication required.`，三个服务在带口令的连接下全部功能正常。

> ⚠️ **本批顺带发现并修掉的两个"非代码"问题**（都在验证过程中暴露）：
> ① `scripts/concurrent-reserve.sh` **从不清理**每轮胜出的会议——十轮回归就在该会议室留下 10 场 `status=20`，
> 跑几轮后候选时段被自己填满，脚本开始报"连续 10 天候选时段均被占用"（青竹厅 10-22~10-29 每天 10:00 都被历史残留占着）。
> 已补上与 `concurrent-modify.sh` 一致的 `cancel_slot` 自清理（实测 3 轮后该会议室活跃会议数归 0）。
> ② 上述残留数据会**让后端单测误红**：`ConflictCheckerTest` 用固定时段 2026-11-10 10:00（@Transactional 自带回滚），
> 一旦该时段有外部残留就断言失败。清理后 45 例全绿——**遇到测试红了先看数据库有没有脏数据**，别急着怀疑代码。

> 第 2、3 批验证方式：C4 实测"建会议 → 取消（50）→ 再改期 → code=40002 且时间未变"；
> R5 实测 Redis 中幂等 key 的值已为 `S`（处理中/已完成状态位）；定向测试 18/18；
> 前端 `tsc --noEmit` 零错误 + `vite build` 通过；三个 E2E 脚本 11/11 + 19/19 + 19/19。
>
> 第 1 批验证方式（逐条实测）：`lsof` 确认 8080/8081/8082 与 6379/3306 均为 `127.0.0.1`；
> 用局域网 IP 打 8081 的伪造身份头请求 → 连接被拒（修复前是 200 + 全量用户）；
> `auth:refresh:{id}` 的 `STRLEN` 由 36（明文 UUID）变为 **64**（SHA-256 十六进制）；
> 登录→刷新→旧 token 重放链路仍正常（20003）；`page=2147483647` 两个服务均返回 `code=0`（修复前 500）；
> 登录时序比值由约 15x 降到 **1.07x**；三个 E2E 脚本回归 11/11 + 19/19 + 19/19。

---

## 二、Critical

### C1. Redis 无认证 + refreshToken 明文存储 + 端口发布到所有网卡 → 任意同网段机器可接管账号（含 ADMIN）

**位置**：`deploy/docker-compose.infra.yml:38-44`（`ports: "6379:6379"`，`command: redis-server --appendonly yes` **无 `requirepass`**）
＋ `server/auth-service/src/main/resources/application.yml:13-16`（连 Redis 无密码）
＋ `server/auth-service/src/main/java/com/smt/auth/store/RefreshTokenStore.java:31-37`（把 UUID **明文**当值存）
＋ `server/gateway/src/main/resources/application.yml:53-54`（`/api/auth/refresh` **在白名单**，换 token 不需要任何凭证）

**攻击链（前四环已由主评审独立复现，均不依赖任何应用层漏洞）**：

```
① nc <host> 6379              → 免密可连（requirepass 为空）
② PING                        → +PONG
③ TYPE/STRLEN auth:refresh:1  → string / 长度 36  ← 恰为 UUID 文本长度，证明存的是明文而非摘要
④ GET auth:refresh:1          → 直接拿到 admin(id=1) 当前有效的 refreshToken
⑤ POST /api/auth/refresh {"refreshToken":"…"} → 换到 roles:["ADMIN"] 的 accessToken
```
第 ⑤ 环**刻意没有执行**——它会轮换掉 admin 的真实 token、把正在使用的人踢下线。

**为什么这是 Critical**：不需要登录、不需要应用漏洞、一条 `nc` 就能完成对 ADMIN 的接管；`0.0.0.0:6379` 与 `0.0.0.0:3306` 同时暴露（MySQL root 口令是 compose 里的 `root123`）。

**改法**：① compose 改 `127.0.0.1:6379:6379` + `--requirepass`，`application.yml` 加 `spring.data.redis.password`；
② **Redis 里只存 `SHA-256(refreshToken)` 摘要**，比对时对入参取摘要（改动收敛在 `RefreshTokenStore` 四处 key/值拼接）；
③ 生产环境基础设施端口一律不发布到宿主机。

### C2. 业务端口绑 `0.0.0.0` + 服务端无条件信任身份头 → 直连即可伪造 ADMIN；`/internal/**` 零凭据直连

**位置**：三个服务的 `application.yml` 只配了 `port`、**未配 `server.address`**（Spring Boot 默认绑全网卡）
＋ `server/meeting-common/src/main/java/com/smt/common/filter/UserHeaderFilter.java:42-54`（**有头即认**，不校验来源）
＋ `.../config/CommonWebAutoConfiguration.java:60`（`anyRequest().permitAll()`，没有 deny-by-default）
＋ `server/auth-service/src/main/java/com/smt/auth/controller/InternalUserController.java:25-37`（无任何凭证校验）
＋ 前提声明：`docs/04-接口设计.md:189`「业务服务端口仅限内网可达……依赖此部署前提」——**仓库里没有任何东西在执行它**。

**主评审独立复现（读操作）**：
```
curl -H "X-User-Id: 1" -H "X-User-Roles: ADMIN" http://127.0.0.1:8081/users?page=1&size=2
→ HTTP 200 + code 0 + 全量用户（username/realName/email/roles/deptName）
对照（去掉伪造头）→ 403 + 10003
curl -X POST http://127.0.0.1:8081/internal/users/batch -d '{"ids":[1,2]}'   # 不带任何 token
→ HTTP 200 + realName + email（PII）
```

**写路径影响（代码级推断）**：`MeetingController` 的取消/改期用 `UserContext.getUserId()` 做归属校验 → 伪造受害者的 `X-User-Id` 即可操作他人会议；`RoomController` 四个 ADMIN 写接口同理。主评审未执行写操作。

**好消息（已交叉验证）**：经网关的路径确实是安全的——`/api/users/../internal/...`、`%2e%2e`、`..;/`、`%2e%2e%2f` 四种穿越变体全部 400/404；网关先剥离同名头再按 JWT 重注入的闭环成立。**问题只在于"门被挪走了，墙没砌"。**

**改法**：① `server.address: 127.0.0.1`（开发态三处，与 Nacos 已固定的 `ip: 127.0.0.1` 一致）；
② 部署侧只对外放行 8080；③ 纵深防御（面试可讲）：网关注入内部共享密钥头，`UserHeaderFilter` 校验不过则忽略身份头；
④ 把 `docs/04:189` 从"前提"改写成"要求 + 落地方式"。

### C3. 分布式锁 acquire 异常收尾不全 → 一次瞬时 Redis 故障把"某会议室某日"永久锁死

**位置**：`server/meeting-service/src/main/java/com/smt/meeting/lock/MeetingLockHelper.java:64-83`（`acquire`）、`:48-55`（`executeWithLock`）、`:85-91`（`release`）

**失败场景**：跨天预约会逐日取多把锁。第 1 把 `tryLock` 成功后，第 2 把 `tryLock` 期间 Redis 抖动 →
Redisson 默认 `timeout=3000ms`（`config/RedissonConfig.java:19-29` 全用默认值）抛 `RedisTimeoutException`
（`tryLock` 只声明 `InterruptedException`，故它必然是 RuntimeException，`:75-82` 两个 catch 都接不住）→
异常穿透 `acquire`，**已获得第 1 把锁无人释放**；`leaseTime=-1` 走看门狗持续续期 →
该 `(roomId, 日期)` 此后所有预约都在 tryLock 3s 后 10007，**直到服务重启**。用户侧 = 这个会议室这一天再也订不了。

**改法**：把 `locks` 提升到 `executeWithLock`，用 try/finally 同时包住 acquire 与 action；
或最小改动：`catch (BusinessException e)` 扩为 `catch (Throwable t) { release(acquired); throw t; }`，
且 `release` 内每把锁的 `unlock` 各自 try/catch（现在一把 unlock 抛异常会漏放后面几把，还会吞掉原始业务异常，把 40001 变成 500）。

**未实测**（需人为制造 Redis 半途失败），但控制流逐行可查。

### C4. `updateWithVersion` 不校验 status → 已取消的会议仍可被改期（R-12 被绕过）

**位置**：`mapper/MeetingMapper.xml:220-226`（`WHERE id=? AND version=?`，**不含 status**）
＋ `:39-43`（`updateStatus` 只 SET status，不推进 version）
＋ `service/MeetingReserveService.java:134,156-159`

**失败场景（cancel ↔ 改期 交错）**：会议 A（status=20，version=3）。T1 改期：读完 A 后要过"抢锁 ≤3s + 会议室行锁等待"，
**窗口是秒级**；T2 同一用户的另一标签页提交"取消 A"（`cancel` 不取任何锁）→ CAS 20→50 提交，version 仍为 3。
T1 继续：锁内冲突复检通过 → `UPDATE ... WHERE id=? AND version=3` **命中** → 提交成功。
结果：一场 status=50 的会议被改了时间/会议室；`meeting_status_log` 记的是 CANCEL、行数据却是取消后的新时段，**审计不自洽**。

**不产生双重预订**（50 不占用时段），故非"数据错乱"级；但 M3 定时任务（20→30、20/30→40 也走 `updateStatus`）上线后，
同一缝隙会升级为"能改进进行中/已结束的会议"。

**改法**：`updateWithVersion` 的 WHERE 补 `AND status = #{expectedStatus}`（调用方传 20）；
或让 `updateStatus` 顺带 `version = version + 1` 当"行代"。二选一，推荐前者。

---

## 三、Required（12 条）

### 后端

| # | 位置 | 问题 | 失败场景 |
|---|------|------|---------|
| R1 | `meeting/service/MeetingQueryService.java:39-40,56`<br>`auth/service/UserService.java:56` | **分页 page 无上限 → int 溢出 → 500** | ✅ **主评审实测**：`/api/meetings?page=2147483647&size=100` → HTTP 500 `code:10000`（`(2147483646*100)` 溢出为 -200 → `LIMIT -200,100` → MySQL 1064）。`/users` 同型。改法：`long offset = ((long)page-1)*size` + page 上限 |
| R2 | `auth/service/AuthService.java:54` | **登录时序泄漏用户名**（防枚举只防了错误码，没防时间） | 实测：不存在的用户名 ~4ms，存在的 `user3` ~58ms（**约 15 倍差**）——脚本按响应时间排序即可批量拿有效用户名；再叠加认证域零限流 → 定向撞库。改法：`user==null` 时对预置 `DUMMY_HASH` 跑一次 `matches` + Redis 滑动窗口限流（`10005` 已登记） |
| R3 | `auth/service/UserService.java:81-88`、`AuthService.java:87-93`、**`:96-106`** | 停用/登出后的 **accessToken 真空期**；**新发现：`/auth/me` 也不看 status** | ⚠️ **部分修复**：`/auth/me` 已校验 status（批量 1）；**真空期仍在**——见下方遗留清单第 1 条 |
| R4 | `auth/service/UserService.java:73,77`、`mapper/SysRoleMapper.java:26-27` | 建用户**不校验 `deptId`/`roleIds`**（悬空引用；"校验用"的 Mapper 方法备而未用） | ✅ **已修**（第 4 批）：`ensureDeptExists` + `ensureRolesExist`（顺手用上了 `SysRoleMapper.selectByIds` 这个"备而未用"的方法、对 roleIds 去重），实测 `deptId=99999`/`roleIds=[99999]` 均 `10001`。与 M1 P1-9 同型，两边一起修 |
| R5 | `meeting/aop/IdempotentAspect.java:52-57,59-65` | **10006 的"已成功"语义与"失败删 key"互相打架** | R1 持 token K 在等锁/事务中，R2 同 token 到达 → 立刻 10006 → 前端弹"该预约已提交过"**并关闭抽屉**；若 R1 随后失败，用户看到"已提交过 + 抽屉关闭 + 日历里什么都没有"——**一次预约静默丢失**。根因：key 的 value 只有 `"1"`、没存结果，"重复即复用上次结果"物理上无法实现；且"抛异常 ⟺ 没提交"在 COMMIT 阶段断连时不成立 |
| R6 | `meeting/resources/mapper/` 各 XML 的索引注释（如 `MeetingMapper.xml:61-67`） | 注释断言"命中 idx_room_time"，**实测走的是 idx_status_start + filesort** | ✅ 主评审实测：`key: idx_status_start`、`Extra: Using index condition; Using where; **Using filesort**`——而 `docs/03 4.1` 自己写"若出现 filesort 说明写法有问题"。小数据量下是 cost 摆动（rows=2），但**注释与事实不符**（AI-RULES 要求注释准确）。改法：在真实数据量下复跑 EXPLAIN 后按实测改注释 |
| R7 | `meeting/service/AvailabilityService.java:35-41` + `dto/AvailabilityQueryDTO.java:16-17` | `roomIds` **无数量/去重/元素校验**，逐房间 2 次串行查询 | ✅ **已修**（第 4 批）：`@Size(max=50)` + 容器元素约束 `List<@NotNull @Positive Long>` + service 层 `distinct()`。实测 50 个放行、51/52 个回 `10001 一次最多查询 50 个会议室`、`[-1]` 被拒、`[3,3,3]` 去重成 1 条 |
| R8 | `meeting/vo/MeetingDetailVO.java:38-42` + `MeetingParticipantMapper.xml:37-43` | 详情"公开可查"把参会人 **email（PII）** 以自增 ID 可枚举的方式曝光 | ✅ **已修**（第 4 批，文档先行）：`docs/04 4.5` 样例先去掉 email 并写明理由 → 再删 `ParticipantVO.email`、SQL 的 `u.email`、前端 `MeetingParticipant.email`。实测详情响应 `participants[0]` 已无该字段 |

### 前端（多条为 2026-10-15 新写的代码，一并列出）

| # | 位置 | 问题 | 失败场景 |
|---|------|------|---------|
| R9 | `web/src/pages/MeetingList.tsx:58`（配 `:118,122-129`） | 我的"竞态守卫"把"防过期数据"做成了**列表与分页条瞬时空掉** | 任意一次翻页/改筛选 → `queryKey` 变 → `payload=undefined` → 表格"暂无数据"、分页条塌成"共 0 条"。**每次翻页 100% 复现**。且 ahooks 3.10 的 `Fetch.runAsync` 内部**已有 count 守卫**（过期响应本就不会覆盖新结果）→ 我的键守卫在这里是冗余且有害的。改法：保留上一条 payload 交给 Table 的 loading 遮罩（keepPreviousData 语义） |
| R10 | `web/src/pages/MeetingDetail.tsx:31-34,50,57` | **唯一没用键守卫的页面**：后退/前进会完整渲染"上一个会议" | `/meetings/2` 后退到 `/meetings/1`（同一路由元素复用实例）→ 在会议 1 的 URL 下完整展示会议 2 的标题/参会人/备注。比"空窗"更糟：**显示的是错误数据**。改法：`detail.id === meetingId` 才渲染 |
| R11 | `web/src/pages/Calendar.tsx:68-71` | 点会议块仍弹"**会议详情见 T2.F3**"——T2.F3 早已上线，且把内部任务号给用户看 | 任何人在日历上点已预约块 → 一句内部排期话术、没有详情入口；而 `docs/02 4.2` 原型图明写"点击看详情"。改法：`navigate('/meetings/' + meeting.meetingId)` |
| R12 | `web/src/pages/BookingDrawer.tsx:41-43` | **清空时间范围会显示 zod 默认英文报错** | rc-picker 4.11.3 的清除按钮传上来的是 `null`（不是 `[null,null]`）→ zod 的 `required_error` 只管 undefined → 实际显示 `Expected array, received null` 且无法提交。改法：补 `invalid_type_error`，并把 `z.custom<Dayjs>((v) => dayjs.isDayjs(v), …)` 补上真正的校验 |
| R13 | `web/src/pages/MeetingList.tsx:80-85,120` | "详情"按钮与行 onClick **双重 navigate** → 浏览器后退"失灵" | 按钮没有 `stopPropagation`，同一 tick 两次 push 同一 URL → 历史栈多一条重复记录 → 详情页按后退"第一次没反应"。改法：按钮加 `e.stopPropagation()`，或去掉行级 onClick |
| R14 | `web/src/pages/Calendar.tsx:43` | `Promise.all` 扇出：**任一会议室失败则整页空白** | 6 个会议室里有 1 个请求失败（含刚被删 → 30001）→ 整个 service reject → 新周网格完全不渲染，只剩转圈后的空白 + 一条 toast，用户以为"这周没有任何会议"。改法：`Promise.allSettled` + 逐房间容错占位 |
| R15 | `MeetingStatusTag.tsx:10-17` vs `CalendarGrid.tsx:29-34` vs `MeetingList.tsx:19-26` | 状态→文案/配色**存在三处副本**（我那个组件自称"唯一映射处"，与事实不符） | 改一处配色必然漏另外两处 → 出现"列表标签红、日历块灰"。改法：`MEETING_STATUS_META` 扩 `blockClass`，`STATUS_OPTIONS` 由它派生 |

### 工程 / 验收

| # | 位置 | 问题 |
|---|------|------|
| R16 | `docs/05-开发计划.md:430,438,447` | 三个 playwright 脚本（`e2e-calendar.cjs` / `e2e-booking.cjs` / `e2e-meeting-list.cjs`）**在仓库和 git 历史里都不存在**（我放在 `/tmp`），所以文档里"11/11 通过""19/19 通过"这些数字**不可复现、不可审**。改法：脚本移入 `web/e2e/` 提交，或把文档改成"一次性本地验证、未入库" |

---

## 四、Optional（择要，31 条中收录 14 条）

**并发/正确性**
- `MeetingReserveService.java:89-112` — `cancel` 不取任何锁（红线 #9 的唯一例外）：只产生**假冲突**，不产生双重预订；它同时是 C4 那条窗口的来源。
- `MeetingRoomMapper.xml:37-42` + `MeetingReserveService.java:69,157` — 行锁粒度 = **整间会议室**，把 Redis"按日锁"的并发设计在 DB 层抵消（不同日期的并发预约在 DB 层仍串行）。当前量级无害，但与文档表述不一致。
- `MeetingReserveService.java:59-61,69,150-152,157` — 会议室存在性/status/策略都在**锁外**读（TOCTOU）：管理员置维护与用户提交并发 → 维护中的会议室被订上；且若会议室行已被删，`FOR UPDATE` 返回 0 行 = **没锁住也不报错**（兜底静默失效）。
- `dto/MeetingCreateDTO.java:39-43` — `participants.userIds` 无上限，批量插入在锁内 → 单用户可拉长锁持有时间。
- `MeetingReserveService.java:197` vs `MeetingMapper.xml:212` — 双时钟（业务判断用 JVM 时钟、审计字段用 DB 时钟）；本机无偏差，**M5 容器化时若不设 TZ 会偏 8 小时**。
- `controller/MeetingController.java:80` — `@Idempotent` 只挂 Controller 层：未来非 HTTP 入口（M5 周期会议批量创建）会静默失去幂等。
- `lock/MeetingLockHelper.java:24` vs `:39/:67` vs `AI-RULES.md:65`/`docs/02:557` vs `docs/01:502` — 锁 key 契约**三处文档互相矛盾**（实现是 `lock:meeting:{roomId}:{yyyy-MM-dd}`）。无功能影响，但属 AI-RULES 铁律 #1 要求报告的文档冲突。

**读路径/SQL**
- `MeetingMapper.xml:117-137` — `scope=mine` 的 `(organiser_id=? OR EXISTS(...))` 实测走**全表扫描 + 逐行子查询**（`EXPLAIN ANALYZE`: `loops=63`），`idx_organiser` 因 OR 完全用不上；10 万行后每页扫 10 万行。改法：改写成可走索引的两段并集。
- `AvailabilityService.java:48-53` — calendar 不校验会议室存在（返回 200 + 空周），与同前缀的 `/rooms/{id}`（30001）语义不一致。
- `AvailabilityService.java:35-42` — availability 对「时间顺序 / 房间存在性」零校验 → **错报"可用"**（正是它要防的错误）。
- `MeetingQueryDTO.java:39-56` — 非法 status 静默降级成"不过滤"（`?status=pending` → 返回全部）。
- `MeetingMapper.xml:135` — keyword 的 `%`/`_` 未转义且无长度上限。
- `docs/04:470` vs `MeetingVO.java:26` — 列表 `needApproval` 文档写 boolean、代码回 0/1（同一字段两个接口类型不一致）。

**前端**
- `Calendar.tsx:40-47` — 切周 = 1+N 个请求且**无取消**（ahooks 的 `cancel()` 只丢响应、不发 AbortController）：连点 5 次周箭头 = 5×(1+N) 个请求全部真的打出去。
- `api/meeting.ts:106` — 10006 的 `undefined` 只活在注释与调用点注解里：`createMeeting` 类型声明是 `Promise<MeetingCreateResult>`，下一个调用点（M3 改期/周期）会直接踩。
- `BookingDrawer.tsx:147,158-178` — 双击提交会把一次正常提交误报成"预约失败"（CancelledError 被当失败）：结果正确（幂等救场），但两条互相矛盾的 toast 很出戏。
- `api/token.ts:9-27` — 双 token（含 7 天 refreshToken）都在 localStorage 且**无 CSP**：当前无 XSS sink，属风险面而非漏洞；面试必被追问"为何不用 HttpOnly cookie"。
- `router.tsx:31-38` 等 — 权限只在 UI 层（**服务端 `@PreAuthorize` 已核对齐全**，前端隐藏只是 UX）：建议加注释写明"裁决在服务端"，避免被误读。
- `request.ts:84-104` — `onErrorCode` 监听器是死代码（全库零调用），与 docs/04 §2.2 设计的"统一弹窗"分叉成两套机制。
- `BookingDrawer.tsx:115` — `crypto.randomUUID()` 依赖 secure context：若改成"局域网 IP + http"给面试官演示，会抛 TypeError、预约流程整条不可用。
- `BookingDrawer.tsx`（308 行）/ `RoomManage.tsx`（347 行）— 超 AGENT.md 的 300 行阈值；`docs/02 §3.4` 规划的 `<RoomPicker>`/`<TimeRangePicker>` 未落位（M3 改期还要复用同一套逻辑）。

---

## 五、M1 存量项状态（17 条 P1 逐条核实）

| 编号 | 问题 | 状态 |
|------|------|------|
| P1-1 | `/users/batch` 无鉴权 | ⚠️ **部分修复**：路径隔离有效（四种穿越变体实测 400/404），但直连 8081 仍零凭据 → 并入 C2 |
| P1-2 | 默认凭据入库 | ⚠️ **部分修复**：compose 的 MySQL/Redis 口令改为 `${MYSQL_ROOT_PASSWORD:-root123}` / `${REDIS_PASSWORD:-redis123}` 可环境变量覆盖 + 只绑回环；默认值仍在文件里（**本地开发型的取舍，已在注释里写明生产做法**） |
| P1-3 | 登出/停用不彻底 | ⚠️ **部分修复**：`/auth/me` 已校验 status、refreshToken 走 Redis 可即时吊销；**accessToken 的 ≤30 分钟真空期仍在**（需引入"失效时间戳"下发到网关，见遗留清单） |
| P1-4 | 登录无防爆破 | ⚠️ **部分修复**：时序泄漏已消除（陪跑哈希）；**限流未做**（`10005` 已登记，docs/05 T4.8 有 Redis 滑动窗口的实现计划） |
| P1-5 | redirect 闭环 | ✅ 已修 |
| P1-6 | 缺 device 筛选 | ✅ **已修**（`JSON_CONTAINS` + `?device=`，docs/04 3.2 早已约定该参数；前端管理页筛选 UI 暂未接入，已在文档注明） |
| P1-7 | 默认策略值不一致 | ✅ 已修 |
| P1-8 | upsert 非原子 | ✅ 已修 |
| P1-9 | 未校验 groupId | ✅ **已修**（`ensureGroupExists`，create/update 都过）；auth 侧同型 R4 一并修掉 |
| P1-10 | 匿名访问映射 403 | ✅ **已修**（`handleAccessDenied` 按 `AnonymousAuthenticationToken` 分叉：匿名 401 / 已登录 403），单测 3 例覆盖 |
| P1-11 | 框架异常吞成 500 | ✅ **已修**：四类残留（参数类型错、路径变量类型错、方法不支持、缺 Content-Type）分别映射 `10001`/`10001`/`405+10008`/`415+10009`；另补 `MissingServletRequestParameter`、`BindException` 两类同族异常 |
| P1-12 | unhandled rejection | ✅ 已修 |
| P1-13 | 前端类型与契约不一致 | ✅ **已修**：`/users/search` 换用场景专用 `UserSearchVO`（id/realName/deptName），前端类型同步为 `UserSearchItem`；顺带堵掉该接口的 email 外泄 |
| P1-14 | token 两处真相 | ✅ **已修**：新增 `clearSession()` 统一清 token + `meeting.user`，`redirectToLogin` 与 store.logout 都走它 |
| P1-15 | 无 lint / 无测试 / 无分包 | ✅ **已修**：ESLint（扁平配置，`no-explicit-any`/`no-console` 落成规则）+ vitest **26 例** + `manualChunks` 三分包；单包 1.36MB → app 55KB + 三个 vendor 包 |
| P1-16 | Feign 降级语义 + 无超时 | ✅ **已修**：降级改回 `10007`（不再用空列表冒充"查无此人"）+ connect/read 超时配死（默认 read 60s 会把请求线程挂在假死的 auth 上） |
| P1-17 | 网关 traceId/CORS | ✅ **已修**：`TraceIdFilter`（生成/透传/回带，含 401 响应；入参白名单校验）+ `globalcors` + `JwtAuthFilter` 放行 OPTIONS 预检 |

### 五·补、第 4 批之后**仍未修**的（明确遗留，别当成已修）

| # | 项 | 为什么留着 / 怎么修 |
|---|----|--------------------|
| 1 | **R3 的 accessToken 真空期**（≤30 分钟） | 停用/登出后 accessToken 仍能通行到自然过期。修法要动**网关**：登录时下发"用户级失效时间戳"（Redis `auth:invalid-before:{uid}`），网关校验 `iat` 是否早于它——相当于给无状态 JWT 加一层可撤销性。属于设计变更，值得单独一批 + 一份说明（面试问"JWT 怎么登出"时的标准答案） |
| 2 | **P1-4 登录限流** | `10005` 已登记、docs/05 T4.8 有完整方案（Redis ZSET 滑动窗口 + Nacos 阈值 + `@RateLimit`）。放在 M4 与限流一起做更省事，不建议现在零散加 |
| 3 | **P1-2 的"默认口令"本体** | 现已支持环境变量覆盖，但 `root123`/`redis123` 作为默认值仍在 compose 里——本地开发项目需要"clone 下来就能跑"，这是**有意保留**的取舍；生产做法（不发布端口 + 强制注入 + 密钥管理）已写进 compose 注释与 docs/04 |
| 4 | Optional 里影响面较大的几条 | ① `availability` 对"时间顺序 / 会议室存在性"零校验（会**错报可用**）；② `MeetingCreateDTO.participants.userIds` 无上限（批量插入在锁内，能拉长锁持有时间）；③ `scope=mine` 的 `OR EXISTS` 走全表扫描（`EXPLAIN ANALYZE` loops=63），10 万行后每页扫全表；④ keyword 的 `%`/`_` 未转义；⑤ 业务判断用 JVM 时钟、审计字段用 DB 时钟（容器化不设 TZ 会偏 8 小时）；⑥ 锁 key 契约在 docs/01、docs/02、AI-RULES 三处写法不一致（实现是 `lock:meeting:{roomId}:{yyyy-MM-dd}`） |
| 5 | 前端工程化的下一层 | 路由级 `React.lazy`（antd vendor 仍 1.07MB，首屏 ≈1.5MB）、CSP、HttpOnly Cookie 的取舍讨论、`request.ts` 里 `onErrorCode` 至今零调用（死代码，要么用要么删） |
| 6 | 其余 Optional（行锁粒度=整间会议室、`@Idempotent` 只在 Controller 层、`calendar` 不校验会议室存在、`needApproval` 文档 boolean vs 代码 0/1 等） | 见第四章，逐条都写了位置与影响，未动 |

---

## 六、建议修复批次

| 批次 | 内容 | 理由 |
|------|------|------|
| **第 1 批（安全止血，约 1 小时）** | C1（Redis 密码 + 端口收窄 + token 存摘要）、C2（`server.address: 127.0.0.1`）、R1/R2/R3 的入参上限与登录时序 | 都是**配置/几行代码**级改动，但堵住的是"账号接管""伪造 ADMIN"这类真实攻击链 |
| **第 2 批（并发正确性）** | C3（锁 acquire 收尾）、C4（`updateWithVersion` 补 status 守卫）、R5（幂等语义）、`cancel` 进锁 | 都在"层与层接缝"上，M3 定时任务上线前必须修完（否则缝隙会被放大） |
| **第 3 批（前端可见缺陷）** | R9/R10（竞态守卫的正确形态）、R11（会议块跳详情）、R12（清空时间的英文报错）、R13（双跳）、R14（日历容错）、R15（状态映射收敛） | 全是**用户每次操作都能撞到**的问题，且多数是当日新代码 |
| **第 4 批（契约与工程）** | R7/R8（PII 与入参）、R16（E2E 入库）、M1 残留 P1-6/9/10/11/13/14/15/16/17 | 影响面广但不紧急 |

---

## 七、做得好（均有验证动作支撑）

- **"经网关"链路是真的安全**：剥离-重注入闭环成立（伪造头经网关被拒 10003）、四种路径穿越变体全被拦、`@PreAuthorize` 与 docs/04 权限列**逐条对应无漏加**（实测非 ADMIN → 403）。
- **上游全库零 `${}` 拼接**（mapper XML 全 `#{}`）→ 无 SQL 注入面。
- **今日两处并发修复经独立复核成立**：① "锁包住事务"无空窗（全项目无一处反例）；② 数据库兜底**不是摆设**——评审用两个真实 mysql 会话复现了"B 在 `FOR UPDATE` 等待期间 A 插入并提交，B 随后读到 A"的时序，证明 InnoDB 的 read view 在锁等待之后才建立，兜底查询不会读到过期快照。
- **状态机与冲突检测的细节扎实**：迁移表与矩阵逐条一致、CAS + 日志同事务、外部读不到中间态；半开区间与 buffer 的"放宽扫描区间"数学等价成立；`FOR UPDATE` 走主键 → 单行记录锁（实测只挡并发事务，非表锁/间隙锁）。
- **Redis Lua 的三段写路径原子化**（refresh 轮换并发只有一个成功）、`UserContext` ThreadLocal 在 finally 双清理（线程池串号这条路堵死）。
- **前端**：全库零 `any`/零 `@ts-ignore`/零 `console`/零 XSS sink；memo 真的生效（模块级空常量 + `useCallback` + `useMemo` 到位）；`useAppRequest` 与 `useDebounceFn` 的卸载安全无洞（ahooks 源码级确认）。
- **幂等链路的整体设计是对的**：token 在"抽屉打开时生成一次"而非每次提交生成，与后端"成功保留 key 10 分钟 / 业务失败即删 key"配合，使"被 40001 打回 → 就地改时间 → 同一 token 重提"不会被误判重复（缺陷只在并发同 token 的场景，见 R5）。

---

## 八、验证方式（区分实测与推断）

- **实测复现（主评审亲自执行）**：直连伪造身份头读 ADMIN 数据 + 对照组、`/internal/users/batch` 零凭据、Redis 免密 PING + `TYPE/STRLEN`（**不读取令牌值**）、登录/会议列表 page 溢出 500、availability 重复 roomIds、详情 email PII、EXPLAIN 执行计划。
- **上游分身实测（主评审复核采纳）**：登录时序 15 倍差、路径穿越四变体、四种框架异常仍 500、Redis 经局域网 IP 的 PING/GET、EXPLAIN ANALYZE 的子查询 loops、`nc` 抓取 refreshToken（**最后一环刻意未执行**）。
- **两会话 DB 实验**：`FOR UPDATE` 锁等待后的 RR 快照可见性。
- **反编译确认**：redisson 3.27.2 `tryAcquireAsync` 在 leaseTime=-1 时走看门狗默认超时。
- **未实测（明确标注）**：C3、C4（需人工构造故障/交错时序）、跨设备可达性（同网段第二台机器是否被宿主防火墙拦截）、万级数据下的执行计划与列表性能。

> ⚠️ 评审期间三个后端服务一直以 `0.0.0.0` 暴露在本机网络上。**若你当时连着公共 WiFi，C1/C2 的实际暴露窗口是存在的**——建议先做第 1 批，再继续开发。
