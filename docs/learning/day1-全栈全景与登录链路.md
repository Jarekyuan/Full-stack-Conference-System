# Day 1：全栈全景 + 登录/权限链路

> 时间：6 小时
> 目标：先建立“项目怎么跑、请求怎么走”的全局图，再拆登录和权限。
> 这一天不要陷进 React 或 Spring 的语法细节，重点是**调用链**和**信任边界**。

---

## 1. 今日目标

- [ ] 能不看文档画出 4 个服务和登录请求链路
- [ ] 能启动 infra / 后端 / 前端，解释每个端口和代理关系
- [ ] 能用 `docs/10-前后端文件地图.md` 按业务定位登录相关文件

---

## 2. 面试先问

先带着这 5 个问题学，学完能直接回答：

1. 浏览器输入用户名密码，到拿到 token，中间发生了什么？
2. 为什么认证放在网关？服务内为什么还要 `@PreAuthorize`？
3. access token 过期后，前端怎么做到“用户无感”？
4. Filter 和 Interceptor 的区别？项目为什么用 Filter？
5. 同一浏览器多个 tab 登录不同用户，为什么会互相挤掉？怎么解决？

---

## 3. 10 分钟概念

### 3.1 四个服务

```text
浏览器 :8000 / :5173
   │
 Nginx / Vite（/api、/ws 代理）
   │
 gateway :8080
   ├── /api/auth/**        → auth-service :8081
   ├── /api/rooms/**       → meeting-service :8082
   ├── /api/meetings/**    → meeting-service :8082
   ├── /api/notifications  → notification-service :8083
   └── /ws/**              → notification-service :8083
```

- **gateway**：统一验 JWT、剥离伪造身份头、重新注入 `X-User-Id` / `X-User-Roles`
- **auth-service**：登录、刷新、登出、当前用户；RBAC 角色查询
- **meeting-service**：会议室、预约、审批、会议纪要（项目最重的服务）
- **notification-service**：消费 MQ、落站内信、WebSocket 推送

基础设施：Nacos（注册/发现 + 配置）、MySQL（业务数据）、Redis（token/锁/缓存/限流）、RabbitMQ（通知事件）。

### 3.2 登录请求链路

```text
Login.tsx
  → useAppRequest(login)
  → http.post('/auth/login')            // axios baseURL = /api
  → Vite/Nginx 代理到 gateway:8080
  → JwtAuthFilter：白名单 /api/auth/login 放行
  → auth-service /auth/login
  → AuthService.login：查用户 → BCrypt → 查角色 → 签 accessToken + 生成 refreshToken
  → RefreshTokenStore：refreshToken 摘要写 Redis（Lua 原子）
  → 返回 { accessToken, refreshToken, expiresIn, user }
  → userStore.login：token 存 sessionStorage，user 存 Zustand
```

后续请求：

```text
request.ts 请求拦截器 → Authorization: Bearer <accessToken>
  → gateway 验签 → 注入 X-User-Id / X-User-Roles
  → 下游 UserHeaderFilter → UserContext + SecurityContext
```

### 3.3 信任边界

- 外部请求**不能**自己带 `X-User-Id` 进来：网关第一件事就是删掉它。
- 服务只信任网关注入的头；所以 auth/meeting/notification 只绑回环/内网。
- 前端路由守卫只负责 UX；真正的权限裁决在服务端 `@PreAuthorize` + 归属校验。

---

## 4. 代码走读

### 4.1 后端：网关与认证

#### 4.1.1 网关 `JwtAuthFilter`

文件：`server/gateway/src/main/java/com/smt/gateway/filter/JwtAuthFilter.java`

它是 `GlobalFilter`，顺序 `-100`，执行顺序很关键：

1. **先剥离** `X-User-Id` / `X-User-Roles`，防止外部伪造。
2. `OPTIONS` 预检直接放行（预检不带 `Authorization`）。
3. 白名单放行：`/api/auth/login`、`/api/auth/refresh`、`/actuator/**`。
4. 取 token：优先 `Authorization: Bearer`；**只有 `/ws/` 接受 `?token=`**（浏览器 WebSocket 不能自定义头）。
5. 验签成功：注入 `X-User-Id`、`X-User-Roles`（逗号分隔多角色）。
6. 失败：401 + `{ code: 10002 }`，并带 traceId 日志。

面试一句话：

> 网关是身份的唯一入口：先删外部身份头，再验 JWT，最后重新注入可信身份头；服务端只信任网关。

#### 4.1.2 auth-service 登录/刷新

文件：
- `server/auth-service/src/main/java/com/smt/auth/controller/AuthController.java`
- `server/auth-service/src/main/java/com/smt/auth/service/AuthService.java`
- `server/auth-service/src/main/java/com/smt/auth/store/RefreshTokenStore.java`

`AuthService.login` 的关键点：

- 用户不存在时也跑一次 BCrypt（固定 `DUMMY_HASH`），避免通过响应时间枚举用户名。
- 统一错误码 `LOGIN_FAILED`，不区分“用户不存在/密码错误”。
- 禁用用户返回 `USER_DISABLED`。
- `issueTokens`：查角色 → `JwtUtil.generate(userId, roles, accessTtl)` → 生成 refreshToken UUID → `RefreshTokenStore.save`。

`RefreshTokenStore` 的关键点：

- Redis 里存的是 refreshToken 的 **SHA-256 摘要**，不是明文。
- 双索引：
  - `auth:refresh:{userId}` → 摘要
  - `auth:refresh:token:{摘要}` → userId
- `save` / `rotate` / `removeByUser` 都用 **Lua 原子执行**，避免并发刷新时“后写覆盖、脏索引”。
- 刷新是**滚动轮换**：旧 refreshToken 立即失效，新 token 写回双索引。

面试一句话：

> refreshToken 只存摘要，刷新时用 Lua 原子轮换，保证并发刷新只有一个成功，旧 token 不能重放。

#### 4.1.3 JWT 工具

文件：`server/meeting-common/src/main/java/com/smt/common/util/JwtUtil.java`

- `sub = userId`
- claim `roles = ["ADMIN","APPROVER"]`
- `issuedAt` / `expiration`
- `signWith(key)`：HS256
- `parse` 同时校验签名和过期时间

#### 4.1.4 服务侧 Filter

文件：
- `server/meeting-common/src/main/java/com/smt/common/filter/UserHeaderFilter.java`
- `server/meeting-common/src/main/java/com/smt/common/filter/TraceIdFilter.java`
- `server/meeting-common/src/main/java/com/smt/common/context/UserContext.java`

`UserHeaderFilter`：

- 读 `X-User-Id` / `X-User-Roles`
- 写 `UserContext`（ThreadLocal）
- 桥接 `SecurityContext`，把角色变成 `ROLE_ADMIN` 等 authority，供 `@PreAuthorize` 使用
- `finally` 里清 `UserContext` 和 `SecurityContext`，防止线程池复用串号

`TraceIdFilter`：

- 有合法 `X-Trace-Id` 就沿用，没有就生成
- 放进 MDC，让本服务每行日志都带 traceId
- `finally` 里 `MDC.remove`，同样是防线程复用

`UserContext`：

- `ThreadLocal<LoginUser>`
- `getUserId()` / `getRoles()` / `clear()`
- 只负责“当前请求”的上下文，不跨线程传递

### 4.2 前端：请求层与登录

#### 4.2.1 `request.ts`：传输/认证层

文件：`web/src/api/request.ts`

职责边界：

- 组件不允许直接 `axios/fetch`，统一走 `http`
- `baseURL = '/api'`，timeout 10s
- 请求拦截器注入 `Authorization: Bearer`
- 响应拦截器解包 `Result.data`；业务错误 `code != 0` reject `ApiError`
- 特殊码：
  - `10006` 幂等重复：静默返回 `undefined`
  - `20003` refresh 失效：清会话、跳登录
- 401 single-flight：
  - `refreshPromise` 共享同一个 Promise
  - `refreshClient` 是**不挂拦截器**的裸实例，避免刷新自身 401 死循环
  - 刷新成功后用新 token 重放原请求（`config._retry` 防重复）
- 下载使用独立 `downloadClient`：二进制不能被 `Result` 解包；要自己识别 401 和 JSON 错误。

面试一句话：

> 401 刷新做了 single-flight，多个并发请求共享一次刷新；刷新请求用裸 axios 实例，避免递归。

#### 4.2.2 `token.ts`：会话存储

文件：`web/src/api/token.ts`

- accessToken / refreshToken / user 快照都放 **sessionStorage**
- 原因：localStorage 同 origin 全 tab 共享，多 tab 登录不同用户会互相覆盖
- 兼容迁移：启动时把旧 localStorage 会话搬到当前 tab，然后删除共享副本
- `clearSession` 一次清 token + user 快照，避免上一用户资料残留
- 隐私模式/测试环境退回内存 `Map`

#### 4.2.3 `userStore.ts` + `RouteGuards.tsx`

文件：
- `web/src/stores/userStore.ts`
- `web/src/components/RouteGuards.tsx`
- `web/src/router.tsx`
- `web/src/layouts/MainLayout.tsx`

- `userStore` 只存**客户端状态**（当前用户），不存接口数据
- `persist` + `immer`，`partialize` 只持久化 `user`
- `storage` 用 `authStorage()`（sessionStorage），刷新当前 tab 仍保留
- `logout` 同时清通知角标，避免下一个用户看到上一个人的未读数
- `RequireAuth`：无 accessToken → `/login?redirect=...`
- `RequireRole`：任一角色命中即通过；否则回日历
- `MainLayout`：角色化菜单 + 通知长连接 + 退出

### 4.3 📘 笔记引用

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

> 📘 **笔记引用**：《3.Ts基础》§10 React 实战速查
> 类型：C（纯基础补充）
> 笔记结论：用泛型/联合类型约束 API 返回值，减少 `any`。
> 项目落点：`web/src/api/types.ts` 的 `Result<T>`、`LoginUser`、`TokenResult`。
> 差异/提醒：这部分是基础补强，不需要和项目每个类型一一对应。

---

## 5. 手敲练习

### 5.1 启动

用 compose（推荐）：

```bash
cd deploy
cp .env.example .env
docker compose up -d --build
docker compose ps          # 9 个常驻 + mysql-init Exited(0)
```

或本地开发（需要 infra 已启动）：

```bash
cd server && mvn -q -DskipTests clean install
cd server && env -u SERVER__PORT java -jar gateway/target/gateway-1.0.0-SNAPSHOT.jar
cd server && env -u SERVER__PORT java -jar auth-service/target/auth-service-1.0.0-SNAPSHOT.jar
cd server && env -u SERVER__PORT java -jar meeting-service/target/meeting-service-1.0.0-SNAPSHOT.jar
cd server && env -u SERVER__PORT java -jar notification-service/target/notification-service-1.0.0-SNAPSHOT.jar
cd web && npm run dev
```

### 5.2 用 curl 走一遍登录

```bash
LOGIN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')
echo "$LOGIN" | python3 -m json.tool | head -30
TOKEN=$(echo "$LOGIN" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
curl -s http://localhost:8080/api/rooms -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -20
```

验收：

- 能看到 `accessToken` / `refreshToken` / `user.roles`
- `/api/rooms` 返回 6 个会议室
- 不带 token 请求 `/api/rooms` 返回 401

### 5.3 浏览器 Network

打开 `http://localhost:8000`（或 5173），登录后看：

- `POST /api/auth/login` 的响应
- 后续 `/api/rooms` 请求头里的 `Authorization`
- 401 时是否只发一次 `/api/auth/refresh`

### 5.4 加一条请求日志（可选）

在 `web/src/api/request.ts` 的请求拦截器里临时加：

```ts
if (import.meta.env.DEV) {
  console.debug('[api]', config.method?.toUpperCase(), config.url)
}
```

验收：控制台能看到每次请求；学习分支上保留/删除都可以，不要提交到 main。

---

## 6. 五道自测

### 题目

1. 网关如何把用户身份传给下游？为什么下游不能直接信任外部 `X-User-Id`？
2. access token 过期后，前端如何做到用户无感？多个请求同时 401 会刷新几次？
3. 为什么 token 存 sessionStorage 而不是 localStorage？
4. Filter 和 Interceptor 的区别？项目为什么把鉴权放在网关？
5. `@PreAuthorize` 在哪个服务、依赖什么头？它和前端路由守卫是什么关系？

### 答案

1. 网关先删除外部传入的 `X-User-Id`/`X-User-Roles`，验 JWT 成功后重新注入 `X-User-Id`/`X-User-Roles`；下游只信任网关注入的头，因为外部请求可以伪造同名头。
2. `request.ts` 捕获 401，走 `refreshPromise` single-flight；多个并发 401 共享同一个刷新 Promise，只发一次 `/auth/refresh`，成功后用新 token 重放原请求。
3. localStorage 同 origin 全 tab 共享，后登录 tab 会覆盖前一个 tab 的 token；sessionStorage 按 tab 隔离，支持同一浏览器多用户。
4. Filter 属于 Servlet 规范，能拦所有请求（含静态资源），能改 request/response；Interceptor 属于 Spring MVC，只能拦进 Controller 的请求，能拿方法信息。项目把统一鉴权放在网关，服务内用 Filter 注入身份；没有用 HandlerInterceptor 做鉴权。
5. `@PreAuthorize` 在 auth-service / meeting-service 等业务服务内，依赖 `UserHeaderFilter` 写入的 `SecurityContext`（来源是网关注入的 `X-User-Roles`）。前端路由守卫只负责 UX，服务端才是权限裁决。

---

## 7. 复盘卡

```text
今天最重要的 3 句话：
1. 网关是身份的唯一入口：先删外部身份头，再验 JWT，再注入可信身份头。
2. 前端 401 刷新是 single-flight；refreshToken 只存摘要，服务端 Lua 原子轮换。
3. sessionStorage 让每个 tab 有独立会话，解决多 tab 互相挤掉。

可以讲给面试官的 1 个点：
“为什么认证放网关、服务内还要 @PreAuthorize”——信任边界 + 纵深防御。
```
