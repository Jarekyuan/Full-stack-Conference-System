# Day 1：全栈全景 + 登录/权限链路

> 时间：6 小时
> 目标：先建立“项目怎么跑、请求怎么走”的全局图，再拆登录和权限。
> 这一天不要陷进 React 或 Spring 的语法细节，重点是**调用链**、**信任边界**和**可迁移的设计模式**。

---

## 1. 今日目标

### 1.1 学完能做到

- [ ] 不看文档画出 4 个服务、基础设施和登录请求链路
- [ ] 能启动 infra / 后端 / 前端，解释每个端口、代理和注册关系
- [ ] 能讲清 JWT 双 token、网关鉴权、`@PreAuthorize`、前端 401 刷新队列
- [ ] 能用 `docs/10-前后端文件地图.md` 按业务定位登录相关文件
- [ ] 能把“网关信任边界”迁移到其他微服务/管理后台场景

### 1.2 时间分配（6h）

| 时间段 | 内容 | 产出 |
|--------|------|------|
| 0:00–1:00 | 概念：架构、请求链路、JWT vs Session | 一张手绘图 |
| 1:00–3:00 | 后端代码走读：gateway → auth → common | 能口述每个过滤器 |
| 3:00–4:00 | 前端代码走读：request/token/store/router | 能口述 401 刷新队列 |
| 4:00–5:00 | 手敲：登录、401、伪造头、加角色 | 4 个可复现实验 |
| 5:00–6:00 | 自测 + 复盘 + 举一反三 | 8 道题 + 复盘卡 |

### 1.3 前置检查

```bash
cd deploy && docker compose ps
# 期望：9 个常驻容器 Up + mysql-init Exited(0)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8000
# 期望：200
```

如果你还没跑起来，先回 `README.md` 的“一键启动”。

### 1.4 今天的“一张图”

```text
浏览器
  │  Authorization: Bearer <accessToken>
  ▼
Nginx / Vite（/api 代理）
  ▼
gateway :8080
  ├─ 剥离外部 X-User-*
  ├─ 白名单放行 /api/auth/login、/api/auth/refresh
  ├─ 验 JWT → 注入 X-User-Id / X-User-Roles
  ▼
auth-service / meeting-service / notification-service
  ├─ UserHeaderFilter → UserContext + SecurityContext
  ├─ @PreAuthorize 方法级鉴权
  └─ 业务 Service
```

记住四个东西：

- **一条链**：浏览器 → 代理 → 网关 → 服务 → 数据库
- **两个 token**：accessToken（短效）+ refreshToken（长效）
- **三道门**：网关过滤器、服务 Filter、方法级 `@PreAuthorize`
- **四个头**：`Authorization`、`X-User-Id`、`X-User-Roles`、`X-Trace-Id`

---

## 2. 面试先问

先不要往下翻答案。每题用 30 秒在心里回答，学完第 6 节再对照。

1. 浏览器输入用户名密码，到拿到 token，中间发生了什么？
2. 为什么认证放在网关？服务内为什么还要 `@PreAuthorize`？
3. JWT 里放了什么？为什么不能放密码、手机号等敏感信息？
4. access token 过期后，前端怎么做到“用户无感”？多个请求同时 401 会刷新几次？
5. 为什么 token 存 sessionStorage 而不是 localStorage？
6. Filter、Interceptor、AOP 三者有什么区别？项目为什么用 Filter？
7. 多 tab 登录不同用户为什么会互相挤掉？同一用户多 tab 有什么限制？
8. 如果网关被绕过，服务直接暴露，会发生什么？项目怎么防？

> 这 8 个问题覆盖了“认证 + 授权 + 前端会话 + 信任边界”，是 1-3 年全栈面试的高频组合。

---

## 3. 10 分钟概念

### 3.1 业务背景：为什么需要登录和权限

会议室系统有三类人：

- 普通用户：看日历、预约、取消自己的会议
- 审批人：审批别人提交的会议
- 管理员：管理会议室、用户、部门、看板

如果没有身份：

- 任何人都能取消别人的会议
- 普通用户能调管理接口
- 审计日志不知道“谁干的”

所以需要两件事：

- **认证（Authentication）**：你是谁？——登录、JWT
- **授权（Authorization）**：你能干什么？——角色、`@PreAuthorize`、归属校验

### 3.2 四个服务和基础设施

```text
浏览器 :8000 / :5173
   │
 Nginx / Vite（/api、/ws 代理）
   │
 gateway :8080
   ├── /api/auth/**        → auth-service :8081
   ├── /api/rooms/**       → meeting-service :8082
   ├── /api/meetings/**    → meeting-service :8082
   ├── /api/approvals/**   → meeting-service :8082
   ├── /api/notifications  → notification-service :8083
   └── /ws/**              → notification-service :8083
```

- **gateway**：统一入口、JWT 验签、身份头注入、CORS、WS 路由
- **auth-service**：登录、刷新、登出、当前用户、RBAC 角色
- **meeting-service**：会议室、预约、审批、纪要（最重）
- **notification-service**：消费 MQ、站内信、WebSocket

基础设施：

- **Nacos**：服务注册/发现 + 配置中心
- **MySQL**：业务数据
- **Redis**：refreshToken、分布式锁、缓存、限流
- **RabbitMQ**：通知事件

### 3.3 一次请求的完整生命周期

以 `GET /api/rooms` 为例：

```text
1. 浏览器 axios：baseURL=/api，自动带 Authorization
2. Vite/Nginx：把 /api/** 转发到 gateway:8080
3. gateway JwtAuthFilter：
   - 剥离外部 X-User-Id / X-User-Roles
   - 不在白名单 → 解析 Bearer token
   - 验签成功 → 注入 X-User-Id、X-User-Roles
4. meeting-service：
   - TraceIdFilter：把 X-Trace-Id 放进 MDC
   - UserHeaderFilter：读身份头 → UserContext + SecurityContext
   - @PreAuthorize（如果有）检查角色
   - Controller → Service → Mapper → MySQL
5. 返回 Result<T>：HTTP 200 + { code, msg, data }
6. 前端 response 拦截器：code=0 解包 data；否则 reject ApiError
```

关键点：**HTTP 状态码和业务码是两套语义**。

- 401/403：传输/认证层失败（网关/安全链）
- HTTP 200 + `code != 0`：业务失败（参数、冲突、重复提交等）

面试时不要把它们混在一起说。

### 3.4 Session vs JWT vs OAuth2

| 方案 | 状态存哪 | 优点 | 缺点 | 本项目 |
|------|----------|------|------|--------|
| Session | 服务端内存/Redis | 可随时踢人、简单 | 多实例要共享、跨域麻烦 | 不用 |
| JWT | 客户端 | 无状态、易横向扩展 | 撤销难、不能放敏感信息 | 用 accessToken |
| OAuth2 | 授权服务器 | 标准、第三方登录 | 重、项目不需要 | 不用 |

本项目选择 **JWT + Redis refreshToken**：

- accessToken 无状态，网关本地验签，性能好
- refreshToken 存 Redis，可撤销、可轮换
- 不是完整 OAuth2，也不需要引入授权服务器

### 3.5 双 token 为什么需要两个

如果只有一个 accessToken：

- 有效期长 → 泄露后长期可用
- 有效期短 → 用户频繁重新登录

双 token 的分工：

- **accessToken**：30 分钟，放内存/sessionStorage，每次请求带
- **refreshToken**：7 天，只用来换新 accessToken，存 Redis（摘要）

刷新时**滚动轮换**：

```text
旧 refreshToken 有效
  → 生成新 refreshToken
  → 旧 refreshToken 立即失效
  → 返回新 accessToken + 新 refreshToken
```

为什么轮换：旧 token 泄露后，攻击者只能用一次；用户正常刷新后旧 token 失效。

### 3.6 信任边界：为什么网关是“唯一入口”

外部请求可以伪造任何 HTTP 头，包括 `X-User-Id: 1`。

所以：

- 网关**先删**外部传入的 `X-User-Id` / `X-User-Roles`
- 验 JWT 成功后**重新注入**
- 下游服务只信任网关注入的头
- 下游服务只绑回环/内网，外部不能直连

这就是“信任边界”：**边界外的输入一律不可信，边界内只信网关**。

### 3.7 类比：公司前台

- **网关** = 公司前台：查工牌（JWT），把访客信息写到来访单（身份头）
- **auth-service** = 人事：发工牌（token）、换工牌（refresh）
- **业务服务** = 各部门：只看来访单，不再查工牌
- **Redis** = 工牌登记簿：记录 refreshToken 摘要，可挂失

如果前台被绕过，访客直接进部门，来访单就可以随便写——所以部门门禁（服务绑回环）也必须存在。

### 3.8 记忆法：一条链、两个 token、三道门、四个头

- **一条链**：浏览器 → 代理 → 网关 → 服务 → DB
- **两个 token**：access（短效，请求带）+ refresh（长效，换 access）
- **三道门**：gateway filter → service filter → `@PreAuthorize`
- **四个头**：Authorization、X-User-Id、X-User-Roles、X-Trace-Id

---

## 4. 代码走读

### 4.1 后端：网关鉴权

#### 4.1.1 `JwtAuthFilter` 的 6 个动作

文件：`server/gateway/src/main/java/com/smt/gateway/filter/JwtAuthFilter.java`

它是 `GlobalFilter`，`getOrder() = -100`，在网关路由之前执行。

```java
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String path = request.getURI().getPath();

    // ① 剥离外部伪造的身份头
    ServerHttpRequest stripped = request.mutate()
            .headers(headers -> {
                headers.remove("X-User-Id");
                headers.remove("X-User-Roles");
            })
            .build();

    // ② CORS 预检放行
    if (HttpMethod.OPTIONS.equals(request.getMethod())) {
        return chain.filter(exchange.mutate().request(stripped).build());
    }

    // ③ 白名单放行
    if (isWhitelisted(path)) {
        return chain.filter(exchange.mutate().request(stripped).build());
    }

    // ④ 取 token + 验签
    String token = resolveToken(request);
    if (StrUtil.isBlank(token)) {
        return unauthorized(exchange, "缺少 token");
    }
    try {
        Claims claims = jwtUtil.parse(token);
        ServerHttpRequest.Builder builder = stripped.mutate()
                .header("X-User-Id", String.valueOf(jwtUtil.getUserId(claims)));
        List<String> roles = jwtUtil.getRoles(claims);
        if (!roles.isEmpty()) {
            builder.header("X-User-Roles", String.join(",", roles));
        }
        // ⑤ 注入可信身份头，继续路由
        return chain.filter(exchange.mutate().request(builder.build()).build());
    } catch (JwtException | IllegalArgumentException e) {
        // ⑥ 统一 401 + code=10002
        return unauthorized(exchange, e.getMessage());
    }
}
```

逐条解释：

1. **为什么先剥离**：不管白名单与否，外部带进来的身份头一律不可信。先删，避免“白名单接口被伪造身份头”。
2. **OPTIONS 放行**：浏览器 CORS 预检不带 `Authorization`，如果卡在验签会 401，跨域前端直接失败。安全性：身份头已剥离，OPTIONS 只协商 CORS 元数据。
3. **白名单**：`/api/auth/login`、`/api/auth/refresh`、`/actuator/**`。登录/刷新不能要求 token。
4. **取 token**：
   - 优先 `Authorization: Bearer`
   - **只有 `/ws/` 接受 `?token=`**：浏览器 `new WebSocket(url)` 不能自定义请求头
   - query 凭证会进日志/历史/Referer，所以只给真正需要的 WS 开口
5. **注入**：`X-User-Id` + `X-User-Roles`（逗号分隔多角色）。下游不再验 JWT。
6. **失败**：HTTP 401 + `Result.error(UNAUTHORIZED)`，并带 traceId 日志。

#### 4.1.2 为什么网关不直接返回 403

网关只回答“你有没有有效身份”，不回答“你能不能做这件事”。

- 认证失败 → 401（你是谁？）
- 授权失败 → 403 / 业务码（你能干吗？）

授权需要业务上下文（会议归属、会议室策略），网关不知道，所以交给服务内 `@PreAuthorize` + Service 归属校验。

#### 4.1.3 `JwtUtil`：JWT 里到底放了什么

文件：`server/meeting-common/src/main/java/com/smt/common/util/JwtUtil.java`

```java
public String generate(Long userId, List<String> roles, Duration ttl) {
    Date now = new Date();
    return Jwts.builder()
            .subject(String.valueOf(userId))   // sub = userId
            .claim("roles", roles)             // roles = ["ADMIN","APPROVER"]
            .issuedAt(now)
            .expiration(new Date(now.getTime() + ttl.toMillis()))
            .signWith(key)                     // HS256
            .compact();
}
```

JWT 结构：

```text
header.payload.signature
  │        │         │
  │        │         └─ HMAC-SHA256(header.payload, secret)
  │        └─ { sub, roles, iat, exp }
  └─ { alg: HS256, typ: JWT }
```

注意：

- **不放密码、手机号、邮箱**：JWT 是 Base64URL 编码，不是加密，任何人都能解码 payload
- **roles 放 JWT**：网关验签后直接注入，服务不用每次查库
- **secret 必须一致**：auth-service 签发、gateway 验签，secret 不一致会全部 401
- **过期时间短**：accessToken 30 分钟，泄露窗口小

### 4.2 后端：登录与刷新

#### 4.2.1 `AuthController`：四个接口

文件：`server/auth-service/src/main/java/com/smt/auth/controller/AuthController.java`

```java
@PostMapping("/login")   public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req)
@PostMapping("/refresh") public Result<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest req)
@PostMapping("/logout")  public Result<Void> logout()
@GetMapping("/me")       public Result<LoginResponse.User> me()
```

对外路径是 `/api/auth/**`，网关 `StripPrefix=1` 后变成 `/auth/**`。

- login/refresh：网关白名单
- logout/me：需要 accessToken，网关注入身份头

#### 4.2.2 `AuthService.login`：三个安全细节

文件：`server/auth-service/src/main/java/com/smt/auth/service/AuthService.java`

```java
public LoginResponse login(LoginRequest request) {
    SysUser user = sysUserMapper.selectByUsername(request.getUsername());

    // ① 用户不存在也跑一次 BCrypt（时序对齐，防用户名枚举）
    boolean matched = passwordEncoder.matches(
            request.getPassword(),
            user == null ? DUMMY_HASH : user.getPassword());
    if (user == null || !matched) {
        throw new BusinessException(ErrorCode.LOGIN_FAILED);
    }

    // ② 禁用账号
    if (user.getStatus() == 0) {
        throw new BusinessException(ErrorCode.USER_DISABLED);
    }

    // ③ 签发双 token
    return issueTokens(user);
}
```

三个细节：

1. **时序攻击**：如果用户不存在就直接返回，响应时间约 4ms；用户存在要跑 BCrypt，约 58ms。攻击者可以按响应时间枚举用户名。用固定 `DUMMY_HASH` 陪跑，两条路径耗时接近。
2. **统一错误码**：不区分“用户不存在”和“密码错误”，都返回 `LOGIN_FAILED`。
3. **禁用账号**：即使密码正确，`status=0` 也不发 token。

`issueTokens`：

```java
private LoginResponse issueTokens(SysUser user) {
    List<String> roles = rolesOf(user.getId());                 // sys_user_role
    String accessToken = jwtUtil.generate(user.getId(), roles, accessTokenTtl);
    String refreshToken = UUID.randomUUID().toString();         // 随机，不是 JWT
    refreshTokenStore.save(user.getId(), refreshToken);          // 存摘要
    ...
}
```

为什么 refreshToken 不是 JWT：

- refreshToken 只需要“随机、不可猜、可撤销”，不需要携带 claims
- 存 Redis 可以做轮换、撤销、单用户单 token
- JWT 反而不好撤销

#### 4.2.3 `RefreshTokenStore`：为什么只存摘要

文件：`server/auth-service/src/main/java/com/smt/auth/store/RefreshTokenStore.java`

Redis 里两个 key：

```text
auth:refresh:{userId}        → refreshToken 的 SHA-256 摘要
auth:refresh:token:{摘要}     → userId
```

为什么存摘要：

- Redis 可能被误开放/被读走
- 存明文 refreshToken，攻击者可以直接换 accessToken
- 存 SHA-256 摘要，攻击者拿不到原像

为什么两个 key：

- 登录/刷新时按 userId 找当前 token（正向）
- 刷新时用 token 找 userId（反向）
- 同一用户只保留一个有效 refreshToken

轮换 Lua（简化）：

```lua
-- KEYS[1] = 旧 token 摘要 key
-- KEYS[2] = 新 token 摘要 key
-- ARGV[1] = 旧摘要, ARGV[2] = 新摘要, ARGV[3] = TTL, ARGV[4] = user key 前缀
local uid = redis.call('GET', KEYS[1])
if not uid then return nil end
local ukey = ARGV[4] .. uid
local current = redis.call('GET', ukey)
if current ~= ARGV[1] then return nil end
redis.call('DEL', KEYS[1])
redis.call('SET', ukey, ARGV[2], 'PX', ARGV[3])
redis.call('SET', KEYS[2], uid, 'PX', ARGV[3])
return uid
```

逐行：

- `GET 旧摘要 key`：旧 token 是否还有效
- `GET 用户 key`：当前用户绑定的摘要是否还是这个旧 token
- `DEL 旧反向 key`：旧 token 立即失效
- `SET 用户 key = 新摘要`：正向索引换成新 token
- `SET 新反向 key = userId`：反向索引
- 整个 Lua 原子执行：两个并发刷新只有一个成功，另一个返回 nil → 20003

为什么需要 `current ~= ARGV[1]`：

- 同一用户可能已经重新登录，用户 key 已换成另一个 token
- 旧 token 不能再刷新，否则会把新登录踢掉

#### 4.2.4 刷新流程

```text
POST /api/auth/refresh { refreshToken }
  → RefreshTokenStore.rotate(oldToken)
     ├─ 旧 token 无效 → 20003
     └─ 有效 → 新 refreshToken + userId
  → 查用户、检查 status
  → 重新查 roles
  → 签新 accessToken
  → 返回 { accessToken, refreshToken, expiresIn, user }
```

如果用户已被删除/禁用：

- `removeByUser(userId)` 作废刚轮换出的 token
- 返回 20003

### 4.3 后端：服务侧身份

#### 4.3.1 `UserHeaderFilter`

文件：`server/meeting-common/src/main/java/com/smt/common/filter/UserHeaderFilter.java`

```java
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
    try {
        resolveIdentity(request);
        chain.doFilter(request, response);
    } finally {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }
}
```

`resolveIdentity`：

- 读 `X-User-Id` / `X-User-Roles`
- `UserContext.set(new LoginUser(userId, roles))`
- 把 roles 转成 `ROLE_ADMIN` / `ROLE_APPROVER`，放进 `SecurityContext`
- 非法头值忽略，后续鉴权拦截

`finally` 清理：

- Tomcat 线程复用
- 不清理会把上一个请求的用户带到下一个请求（串号）

#### 4.3.2 `@PreAuthorize` 怎么生效

文件：`server/meeting-common/src/main/java/com/smt/common/config/CommonWebAutoConfiguration.java`

```java
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class CommonWebAutoConfiguration {
    @Bean
    public SecurityFilterChain permissiveSecurityFilterChain(HttpSecurity http,
                                                             UserHeaderFilter userHeaderFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(registry -> registry.anyRequest().permitAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterAfter(userHeaderFilter, SecurityContextHolderFilter.class);
        return http.build();
    }
}
```

关键点：

- `@EnableMethodSecurity`：开启 `@PreAuthorize`
- `anyRequest().permitAll()`：URL 级全部放行，因为认证在网关
- `UserHeaderFilter` 必须挂在 `SecurityContextHolderFilter` 之后：否则 SecurityContext 会被重置，身份丢失
- 无状态：不创建 session、不做表单登录
- `@PreAuthorize("hasRole('ADMIN')")` 会读取 `SecurityContext` 里的 `ROLE_ADMIN`

面试一句话：

> 认证在网关，授权在服务内；安全链是放行式的，URL 全部 permitAll，真正的权限靠 `@PreAuthorize` + 业务归属校验。

#### 4.3.3 `TraceIdFilter`

文件：`server/meeting-common/src/main/java/com/smt/common/filter/TraceIdFilter.java`

- 有合法 `X-Trace-Id` 就沿用，没有就生成
- 放进 MDC，让本服务每行日志都带 traceId
- `finally` 里 `MDC.remove`，防线程复用串号
- 不往响应头写 traceId：网关已经写了，服务再写会出现两个同名头

### 4.4 前端：请求层

#### 4.4.1 `request.ts` 的整体结构

文件：`web/src/api/request.ts`

```text
instance（主实例）
  ├─ 请求拦截：注入 Authorization
  ├─ 响应拦截（成功）：code=0 解包；10006 静默；20003 跳登录；其他 reject
  └─ 响应拦截（失败）：401 → single-flight 刷新 → 重放
refreshClient（裸实例）
  └─ 只用于 /auth/refresh，不挂拦截器，避免递归
downloadClient（下载实例）
  └─ responseType=blob，自己处理 401/JSON 错误
```

#### 4.4.2 为什么 401 刷新要 single-flight

```ts
let refreshPromise: Promise<string> | null = null

function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}
```

场景：页面加载时同时发 5 个请求，accessToken 过期，5 个都 401。

- 没有 single-flight：5 次 refresh，后 4 次可能因为轮换失败而报错
- 有 single-flight：共享同一个 Promise，只发 1 次 refresh，5 个请求拿到同一个新 token

`config._retry` 防止重放后再次 401 无限循环。

#### 4.4.3 特殊业务码

```ts
const CODE_IDEMPOTENT = 10006
const CODE_REFRESH_EXPIRED = 20003
```

- `10006`：重复提交，静默返回 `undefined`，前端复用上次结果
- `20003`：refreshToken 失效，清会话 + 跳登录

为什么要在这里处理，而不是每个组件处理：

- 传输/认证层语义统一
- 组件只关心业务错误
- 避免每个页面都写一遍 401/20003

#### 4.4.4 错误归一化

```ts
const err = new ApiError(body.code, body.msg, body.data, traceId)
```

- `ApiError` 带业务码、消息、结构化 data、traceId
- `useAppRequest` 统一 toast
- 特殊错误码（40001/40005/50001）可以注册监听器，弹冲突详情

#### 4.4.5 下载 client 为什么单独

导出 Word 返回二进制：

- 主实例会把 Blob 当 `Result` 解析，误报业务错误
- 下载实例 `responseType: 'blob'`，自己判断 401/JSON/文件名
- 401 时也复用主实例的 `refreshAccessToken`

### 4.5 前端：会话与状态

#### 4.5.1 `token.ts`：sessionStorage 多 tab

文件：`web/src/api/token.ts`

```ts
const ACCESS_TOKEN_KEY = 'meeting.accessToken'
const REFRESH_TOKEN_KEY = 'meeting.refreshToken'
export const USER_STORAGE_KEY = 'meeting.user'
```

为什么用 sessionStorage：

- localStorage 同 origin 全 tab 共享
- 后登录 tab 覆盖前一个 tab 的 token
- sessionStorage 按 tab 隔离，每个 tab 一个会话

兼容迁移：

```ts
for (const key of [ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, USER_STORAGE_KEY]) {
  const oldValue = window.localStorage.getItem(key)
  if (oldValue != null && tab.getItem(key) == null) tab.setItem(key, oldValue)
  window.localStorage.removeItem(key)
}
```

- 旧版本存在 localStorage 的会话搬到当前 tab
- 删除共享副本，新 tab 不再继承旧登录

`clearSession`：

- 清 accessToken、refreshToken、user 快照
- 三者是同一个会话的碎片，必须一起清

#### 4.5.2 `userStore`：客户端状态

文件：`web/src/stores/userStore.ts`

```ts
export const useUserStore = create<UserState>()(
  persist(
    immer((set) => ({
      user: null,
      login: (token) => {
        setTokens(token.accessToken, token.refreshToken)
        set((state) => { state.user = token.user })
      },
      logout: () => {
        clearSession()
        useNotificationStore.getState().reset()
        set((state) => { state.user = null })
      },
    })),
    {
      name: USER_STORAGE_KEY,
      storage: createJSONStorage(() => authStorage()),
      partialize: (state) => ({ user: state.user }),
    },
  ),
)
```

职责边界：

- 只存“客户端状态”（当前用户）
- 不存接口数据（归 useRequest 管）
- 不存 token（归 token.ts 管）
- `partialize` 只持久化 user，不序列化函数
- logout 同时清通知角标

#### 4.5.3 `RouteGuards`：前端守卫只是 UX

文件：`web/src/components/RouteGuards.tsx`

```tsx
export function RequireAuth() {
  const location = useLocation()
  if (!getAccessToken()) {
    const redirect = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?redirect=${redirect}`} replace />
  }
  return <Outlet />
}

export function RequireRole({ roles: allowed, children }) {
  const user = useUserStore((s) => s.user)
  const hasRole = !!user && (user.roles ?? []).some((role) => allowed.includes(role))
  if (!hasRole) return <Navigate to="/calendar" replace />
  return <>{children}</>
}
```

- `RequireAuth`：无 accessToken → 登录页，带 redirect
- `RequireRole`：任一角色命中即通过
- **服务端才是权限裁决**：绕过前端守卫直接发请求，会拿到 403/40004

#### 4.5.4 `MainLayout`：角色菜单 + 退出

文件：`web/src/layouts/MainLayout.tsx`

- 审批中心仅 ADMIN/APPROVER
- 管理端仅 ADMIN
- 通知长连接挂在 MainLayout：登录后的整个会话保持连接
- 退出：先尽力调 `/auth/logout` 作废 refreshToken，失败也不阻断本地退出
- 本地 `logoutStore()` 清 session + 通知角标 + 跳登录

### 4.6 端到端时序图

#### 4.6.1 登录

```text
Login.tsx
  → useAppRequest(login)
  → POST /api/auth/login
  → gateway 白名单放行
  → AuthController.login
  → AuthService.login：查用户 → BCrypt → 查角色
  → JwtUtil.generate
  → RefreshTokenStore.save（Lua）
  → 返回 LoginResponse
  → userStore.login：setTokens + user
  → navigate(redirect)
```

#### 4.6.2 已登录请求

```text
request 拦截器 → Authorization: Bearer
  → gateway JwtAuthFilter：剥离 → 验签 → 注入 X-User-*
  → service TraceIdFilter → UserHeaderFilter
  → @PreAuthorize → Controller → Service
```

#### 4.6.3 401 刷新

```text
请求 A、B 同时 401
  → 都调用 refreshAccessToken()
  → 共享 refreshPromise，只发一次 /auth/refresh
  → 新 token 写回
  → A、B 各自用新 token 重放
  → 若刷新失败 → clearSession + 跳登录
```

#### 4.6.4 WebSocket 握手

```text
new WebSocket('/ws/notifications?token=...')
  → Nginx Upgrade
  → gateway：/ws 接受 query token → 验签 → 注入 X-User-Id
  → notification-service 握手 → 按 userId 保存 session
```

### 4.7 边界与异常

| 场景 | 行为 | 为什么 |
|------|------|--------|
| accessToken 过期 | 前端 401 → refresh → 重放 | 用户无感 |
| refreshToken 过期/被撤销 | 20003 → 清会话 → 登录页 | 无法恢复 |
| 用户被禁用 | 登录/刷新/me 返回 USER_DISABLED | 状态检查 |
| 多 tab 不同用户 | sessionStorage 隔离 | 每个 tab 独立 |
| 多 tab 同一用户 | 后端只保留一个 refreshToken | 后登录会覆盖，先登录 tab 到期需重登 |
| 外部伪造 X-User-Id | 网关先剥离 | 信任边界 |
| CORS 预检 | OPTIONS 放行 | 预检不带 Authorization |
| 静态资源 | 网关白名单/路由不拦 | 前端资源不需要 JWT |
| WS 连接 | 只接受 query token | 浏览器 WS 不能自定义头 |
| 服务直连 | 只绑回环/内网 | 防止绕过网关 |

### 4.8 📘 笔记引用

> 📘 **笔记引用**：《11.Spring Boot》§一.4 Spring MVC 执行流程
> 类型：A（项目直接对应）
> 笔记结论：请求先经过 Filter/Interceptor，再进 DispatcherServlet → Controller → Service。
> 项目落点：网关 `JwtAuthFilter` → auth-service `AuthController` → `AuthService`。
> 差异/提醒：项目是微服务，不是单体 MVC；网关这一层在 DispatcherServlet 之前。

> 📘 **笔记引用**：《11.Spring Boot》§五.3 拦截器与过滤器
> 类型：B（项目有差异）
> 笔记结论：Filter 拦所有请求，Interceptor 只拦进 Controller 的请求；跨域/编码用 Filter，鉴权/方法信息用 Interceptor。
> 项目落点：`UserHeaderFilter`、`TraceIdFilter`、网关 `JwtAuthFilter`。
> 差异/提醒：项目没有用 MVC `HandlerInterceptor`；面试先讲 Filter/Interceptor/AOP 三者区别，再讲项目选型。

> 📘 **笔记引用**：《11.Spring Boot》§五.4 ThreadLocal线程（BaseContext）
> 类型：A（项目直接对应）
> 笔记结论：ThreadLocal 保存当前登录人，用完必须 `remove`，防线程池复用串号。
> 项目落点：`UserContext` + `UserHeaderFilter` 的 `finally` 清理。
> 差异/提醒：笔记叫 `BaseContext`，项目叫 `UserContext`，思路一样。

> 📘 **笔记引用**：《13.微服务+部署》§二.1 注册中心 Nacos / §二.4 网关 Gateway
> 类型：A（项目直接对应）
> 笔记结论：Nacos 负责注册/发现，Gateway 负责统一入口、路由、鉴权。
> 项目落点：4 个服务注册 Nacos；`gateway/application.yml` 的路由表。
> 差异/提醒：项目用 Spring Cloud LoadBalancer（`lb://`），不是笔记里的 Ribbon。

> 📘 **笔记引用**：《2.React知识点》§四.1 Hooks 基础 / §四.6 自定义 Hooks
> 类型：A（项目直接对应）
> 笔记结论：自定义 Hook 把可复用的状态逻辑抽出来，组件只关心 UI。
> 项目落点：`useAppRequest`（请求 + toast）、`useWebSocket`（通知长连接）。
> 差异/提醒：项目用 ahooks `useRequest` 二次封装，不是手写 `useState + useEffect`。

> 📘 **笔记引用**：《2.React知识点》§四.5 useEffect 异步坑点
> 类型：A（项目直接对应）
> 笔记结论：异步请求要处理竞态/卸载，晚返回的旧响应不能覆盖新状态。
> 项目落点：`AudioTranscriptWorkspace` 的请求 token、`useWebSocket` 的清理。
> 差异/提醒：普通请求由 ahooks 管生命周期，自定义 hook 要自己处理竞态。

> 📘 **笔记引用**：《3.Ts基础》§10 React 实战速查
> 类型：C（纯基础补充）
> 笔记结论：用泛型/联合类型约束 API 返回值，减少 `any`。
> 项目落点：`web/src/api/types.ts` 的 `Result<T>`、`LoginUser`、`TokenResult`。
> 差异/提醒：这部分是基础补强，不需要和项目每个类型一一对应。

### 4.9 举一反三

#### 4.9.1 这套认证模型能迁移到哪些场景

- 管理后台：JWT + 网关 + 方法级权限
- 移动端 API：access/refresh 双 token
- 多租户 SaaS：JWT 里放 tenantId，网关注入 `X-Tenant-Id`
- 开放平台：OAuth2 + scope（比本项目重）

#### 4.9.2 什么时候不该用 JWT

- 需要“立即踢人/强制下线”：JWT 无状态，撤销难；用 session 或短期 token + 黑名单
- 需要第三方授权：OAuth2
- 单体应用：session 更简单

#### 4.9.3 可迁移的模式

| 模式 | 本项目 | 其他场景 |
|------|--------|----------|
| 信任边界 | 网关剥离/注入身份头 | API Gateway、Service Mesh |
| 双 token | access + refresh | 移动端、开放平台 |
| single-flight | 401 刷新队列 | 任何 SDK 的 token 刷新 |
| per-tab session | sessionStorage | 多账号运营后台 |
| 方法级鉴权 | `@PreAuthorize` | 后台管理系统 |
| 上下文清理 | ThreadLocal finally | 线程池、异步任务 |

#### 4.9.4 反例：常见错误

- 把 JWT 当加密：payload 可解码，不能放敏感信息
- 把 refreshToken 存 localStorage：XSS 可读；sessionStorage 也不绝对安全，但窗口更小
- 只在前端做权限：绕过路由守卫直接调 API
- 网关不剥离身份头：外部伪造 `X-User-Id`
- 服务绑 0.0.0.0 且公网可达：绕过网关
- 401 刷新不做 single-flight：并发刷新互相踢掉

---

## 5. 手敲练习

### 5.1 基础：curl 走登录

```bash
LOGIN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')
echo "$LOGIN" | python3 -m json.tool | head -30
TOKEN=$(echo "$LOGIN" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
curl -s http://localhost:8080/api/rooms -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -20
```

验收：

- 看到 `accessToken` / `refreshToken` / `user.roles`
- `/api/rooms` 返回 6 个会议室
- 不带 token 请求 `/api/rooms` 返回 401

### 5.2 负例：伪造身份头

```bash
# 不带 token，只伪造 X-User-Id
curl -i http://localhost:8080/api/rooms -H 'X-User-Id: 1'
# 期望：401

# 带合法 token，但伪造 X-User-Id
curl -i http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN" -H 'X-User-Id: 999'
# 期望：网关剥离伪造头，按 token 里的 userId 注入
```

验收：能解释“为什么网关必须先剥离”。

### 5.3 观察 401 刷新

方式 A：把 accessToken 改成过期值（学习分支）

```yaml
# server/auth-service/src/main/resources/application.yml
security:
  jwt:
    access-token-ttl: 10s
```

重启 auth-service，登录后等 10 秒，再点页面：

- Network 里应看到一次 `/api/auth/refresh`
- 原请求被重放，页面不跳登录
- 多个并发请求同时 401 时，只发一次 refresh

方式 B：DevTools 里手动改 sessionStorage 的 accessToken 为乱码，再触发请求。

### 5.4 变体：加一个 AUDITOR 角色

目标：只读审计员，能看审批历史，不能审批。

步骤：

1. `sys_role` 插入 `AUDITOR`
2. `sys_user_role` 给某个用户绑定
3. 后端接口加 `@PreAuthorize("hasAnyRole('ADMIN','APPROVER','AUDITOR')")`
4. 前端菜单/路由加 `RequireRole`
5. 登录后检查 JWT 的 roles、`X-User-Roles`、页面入口

验收：AUDITOR 能看历史，不能点通过/拒绝；直接调审批接口返回 403。

### 5.5 破坏性实验（可选，学习分支）

- 注释掉网关的“剥离身份头”，用 curl 伪造 `X-User-Id`，观察是否能冒充
- 注释掉 `UserHeaderFilter` 的 `finally clear`，连续请求观察是否串号
- 把 `refreshClient` 换成主实例，观察刷新 401 时是否递归

> 这些实验只用于理解，不要提交到 main。

---

## 6. 八道自测

### 题目

1. 网关如何把用户身份传给下游？为什么下游不能直接信任外部 `X-User-Id`？
2. access token 过期后，前端如何做到用户无感？多个请求同时 401 会刷新几次？
3. 为什么 token 存 sessionStorage 而不是 localStorage？
4. Filter、Interceptor、AOP 的区别？项目为什么把鉴权放在网关？
5. `@PreAuthorize` 在哪个服务、依赖什么头？它和前端路由守卫是什么关系？
6. 为什么 refreshToken 存 Redis 摘要，而不是明文或 JWT？
7. 多 tab 登录不同用户为什么能隔离？同一用户多 tab 有什么限制？
8. 如果服务直接暴露在公网，会发生什么？项目怎么防？

### 答案

1. 网关先删除外部传入的 `X-User-Id`/`X-User-Roles`，验 JWT 成功后重新注入；下游只信任网关注入的头，因为外部请求可以伪造同名头。
2. `request.ts` 捕获 401，走 `refreshPromise` single-flight；多个并发 401 共享同一个刷新 Promise，只发一次 `/auth/refresh`，成功后用新 token 重放原请求。
3. localStorage 同 origin 全 tab 共享，后登录 tab 会覆盖前一个 tab 的 token；sessionStorage 按 tab 隔离，支持同一浏览器多用户。
4. Filter 属于 Servlet 规范，能拦所有请求（含静态资源），能改 request/response；Interceptor 属于 Spring MVC，只能拦进 Controller 的请求，能拿方法信息；AOP 拦 Bean 方法调用。项目把统一鉴权放在网关，服务内用 Filter 注入身份；没有用 HandlerInterceptor 做鉴权。
5. `@PreAuthorize` 在 auth-service / meeting-service 等业务服务内，依赖 `UserHeaderFilter` 写入的 `SecurityContext`（来源是网关注入的 `X-User-Roles`）。前端路由守卫只负责 UX，服务端才是权限裁决。
6. refreshToken 只需要随机、可撤销、可轮换，不需要携带 claims；存 Redis 摘要可以防“Redis 被读走后直接换 token”，Lua 原子轮换保证并发刷新只有一个成功。JWT 反而不好撤销。
7. sessionStorage 按 tab 隔离；同一用户多 tab 后端只保留一个 refreshToken，后登录会覆盖，先登录 tab 的 accessToken 到期后需要重新登录。
8. 外部可以伪造 `X-User-Id` 冒充任何人、直连内部接口；项目让服务只绑回环/内网，网关是唯一入口，并在网关剥离/重写身份头。

### 追问

- 如果让你把 JWT 改成 session，哪些地方要改？
- 如果 refreshToken 被盗，怎么发现和止损？
- 如果网关是多实例，白名单/密钥怎么管理？
- 如果要做“强制下线”，JWT 方案怎么补？

---

## 7. 复盘卡

### 7.1 三句话

```text
1. 网关是身份的唯一入口：先删外部身份头，再验 JWT，再注入可信身份头。
2. 前端 401 刷新是 single-flight；refreshToken 只存摘要，服务端 Lua 原子轮换。
3. sessionStorage 让每个 tab 有独立会话，解决多 tab 互相挤掉。
```

### 7.2 归纳：认证链路一张表

| 层 | 组件 | 输入 | 输出 | 失败 |
|----|------|------|------|------|
| 浏览器 | request.ts | accessToken | Authorization 头 | 401 → refresh |
| 网关 | JwtAuthFilter | Authorization / WS token | X-User-Id / X-User-Roles | 401 + 10002 |
| 服务 | UserHeaderFilter | X-User-* | UserContext + SecurityContext | 无身份则后续 403 |
| 方法 | @PreAuthorize | SecurityContext | 放行/拒绝 | 403 |
| 业务 | Service | UserContext | 归属校验 | 40004 |

### 7.3 演绎：从原则推导新场景

原则：**边界外输入不可信，边界内只信网关**。

由此可以推导：

- 为什么服务只绑回环/内网？——防止绕过网关
- 为什么网关要剥离 `X-User-*`？——外部可伪造
- 为什么 `@PreAuthorize` 在服务内？——网关不知道业务归属
- 为什么 WS token 放 query？——浏览器 WS 不能自定义头
- 为什么多 tab 用 sessionStorage？——localStorage 跨 tab 共享

### 7.4 记忆法

```text
一条链：浏览器 → 代理 → 网关 → 服务 → DB
两个 token：access（短效）+ refresh（长效）
三道门：gateway filter → service filter → @PreAuthorize
四个头：Authorization、X-User-Id、X-User-Roles、X-Trace-Id
```

### 7.5 60 秒面试话术

> 登录时 auth-service 校验 BCrypt，签发 30 分钟的 accessToken 和 7 天的 refreshToken；
> refreshToken 只把 SHA-256 摘要存 Redis，刷新时用 Lua 原子轮换。
> 网关统一验 JWT，先剥离外部伪造的 X-User-*，再注入可信身份头；
> 服务内用 Filter 写入 UserContext 和 SecurityContext，方法级用 @PreAuthorize 做授权，
> 真正的数据权限还在 Service 里做归属校验。前端 401 用 single-flight 刷新，
> 多个并发请求只刷新一次；token 存 sessionStorage，保证多 tab 会话隔离。

### 7.6 明日预告

Day 2 进入预约核心：状态机、半开区间冲突检测、日历布局和预约表单。

---

### 附：今天要能打开的文件

- `server/gateway/src/main/java/com/smt/gateway/filter/JwtAuthFilter.java`
- `server/auth-service/src/main/java/com/smt/auth/controller/AuthController.java`
- `server/auth-service/src/main/java/com/smt/auth/service/AuthService.java`
- `server/auth-service/src/main/java/com/smt/auth/store/RefreshTokenStore.java`
- `server/meeting-common/src/main/java/com/smt/common/util/JwtUtil.java`
- `server/meeting-common/src/main/java/com/smt/common/filter/UserHeaderFilter.java`
- `server/meeting-common/src/main/java/com/smt/common/filter/TraceIdFilter.java`
- `server/meeting-common/src/main/java/com/smt/common/context/UserContext.java`
- `server/meeting-common/src/main/java/com/smt/common/config/CommonWebAutoConfiguration.java`
- `web/src/api/request.ts`
- `web/src/api/token.ts`
- `web/src/stores/userStore.ts`
- `web/src/components/RouteGuards.tsx`
- `web/src/router.tsx`
- `web/src/layouts/MainLayout.tsx`
- `web/src/hooks/useAppRequest.ts`
