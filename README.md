# 会议室预约系统（new-meeting-systems）

> **全栈实践项目**：Spring Cloud 微服务（Java 17）+ React 18，从真实企业会议室系统提炼核心业务链路重构。
> **核心解决两个工程问题**：① 并发预约下的时段冲突控制（分布式锁 + 区间判重）② 会议状态生命周期的一致性（状态机 + 定时补偿 + 事件驱动）。

---

## ⚡ 一键启动（Docker Compose）

```bash
cd deploy
cp .env.example .env          # 可选：默认零依赖，不填也能跑
docker compose up -d --build
docker compose ps             # 期望 9 个常驻容器 Up + mysql-init 一次性任务退出 0
```

浏览器打开 **http://localhost:8000**，用 `admin / admin123` 登录。

> 若本机 IDE 已启动 8080~8083 的 Java 进程，先停掉再跑 compose；compose 只映射
> gateway 8080 与 web 8000。已有 infra 容器会被 compose 接管，命名卷与数据保留。
>
> 默认只绑 `127.0.0.1`，适合本机演示。要局域网访问：在 `deploy/.env` 设
> `BIND_ADDR=0.0.0.0`，**同时**换掉 `JWT_SECRET`（`openssl rand -base64 32`）
> 并修改种子 `admin` 密码；不要把默认口令暴露到公网。

- 默认 `STORAGE_PROVIDER=local` + `MINUTES_ASR_PROVIDER=mock` + `MINUTES_AI_PROVIDER=mock`：
  不配置任何云密钥即可完整体验预约、审批、通知、会议纪要上传与 AI 生成。
- 首次构建要下载 Maven / npm 依赖，通常 5~15 分钟；之后改代码重建会命中缓存。
- 真实 COS + 腾讯 ASR + DeepSeek：编辑 `deploy/.env`（参考 `.env.example` 注释）。
  **腾讯 ASR 需要公网可访问的音频 URL，因此真实 ASR 必须同时启用 COS。**
- 停止：`docker compose down`（保留数据）；彻底重置：`docker compose down -v`。

### 部署拓扑

```text
浏览器 :8000
   │
 Nginx
   ├── /api/**        ──> gateway:8080 ──> auth-service:8081
   │                                     ├─> meeting-service:8082
   │                                     └─> notification-service:8083
   ├── /ws/**         ──> gateway:8080（WebSocket Upgrade）
   └── /files/local/** ──> meeting-service:8082（本地存储预签名上传/下载）

4 个 Java 服务 + web 均在 smt-net 内；仅 gateway(8080) 与 web(8000) 映射宿主。
所有服务向 Nacos:8848 注册/发现；MySQL/Redis/RabbitMQ 只在 Docker 网络与回环端口可达。
```

---

## 🚀 本地开发启动

```bash
# 1. 基础设施（MySQL 8 / Redis 7 / RabbitMQ 3.13 / Nacos 2.4）
cd deploy && docker compose -f docker-compose.infra.yml up -d
docker compose -f docker-compose.infra.yml ps        # 4 容器全 Up 即就绪

# 2. 数据库（首次启动 compose 时自动初始化；手动重建用：）
mysql -h127.0.0.1 -uroot -p < db/01-schema.sql
mysql -h127.0.0.1 -uroot -p < db/02-seed.sql

# 3. 后端（4 个服务各开一个终端；meeting-service 的 COS/DeepSeek 环境变量见 CLAUDE.md §7）
cd server && mvn -q -DskipTests clean install
env -u SERVER__PORT java -jar gateway/target/gateway-1.0.0-SNAPSHOT.jar                    # :8080
env -u SERVER__PORT java -jar auth-service/target/auth-service-1.0.0-SNAPSHOT.jar          # :8081
env -u SERVER__PORT java -jar meeting-service/target/meeting-service-1.0.0-SNAPSHOT.jar    # :8082
env -u SERVER__PORT java -jar notification-service/target/notification-service-1.0.0-SNAPSHOT.jar  # :8083

# 4. 前端
cd web && npm ci && npm run dev                      # :5173（代理 /api、/ws → :8080）
```

> 已有库升级：`db/migrations/` 是手动迁移目录，**不会被 MySQL 容器自动执行**；
> 全新建库只跑 `01-schema.sql` + `02-seed.sql`。

---

## 👤 演示账号（种子数据）

| 账号 | 密码 | 角色 | 部门 |
|------|------|------|------|
| admin | admin123 | **ADMIN + APPROVER**（多角色演示） | 行政部 |
| approver1 | user123 | APPROVER（审批人） | 产品部 |
| approver2 | user123 | APPROVER（审批人） | 研发部 |
| user1 / user2 | user123 | USER（普通用户） | 研发部 |
| user3 | user123 | USER（普通用户） | 产品部 |

> 角色为 RBAC 五表模型（一人可多角色）；梧桐厅/红枫厅的预约需审批。

## ⚙️ 端口约定

| 类别 | 端口 | 说明 |
|------|------|------|
| 前端 | 8000 | Nginx（compose）/ 5173（Vite 开发） |
| 网关 | 8080 | 唯一对外业务端口 |
| 内部服务 | 8081 / 8082 / 8083 | 仅容器网络可达（本地开发时绑 127.0.0.1） |
| 中间件 | 3306 / 6379 / 5672+15672 / 8848+9848 | 均只绑宿主回环 |

端口冲突时可在 `deploy/.env` 覆盖 `GATEWAY_PORT`、`WEB_PORT`；
隔离验证时还可覆盖 `MYSQL_PORT`、`REDIS_PORT`、`RABBITMQ_PORT`、`NACOS_PORT` 等。

## 🏗️ 目录结构

```text
new-meeting-systems/
├── docs/       # 01~07 设计文档 + 当前状态快照
├── db/         # 全新建库脚本 01/02；migrations/ 为已有库手动迁移
├── deploy/     # infra 编排 + 全量 compose + Nginx 配置 + .env.example
├── scripts/    # 演示、并发与验收脚本
├── server/     # 后端：meeting-common / gateway / auth / meeting / notification
│   └── */Dockerfile
└── web/        # 前端：React 18 + TS + Vite + antd；tests/ 单测，e2e/ 真浏览器脚本
```

---

## 📚 文档索引（docs/）

| 编号 | 文档 | 内容 |
|------|------|------|
| 状态 | [当前状态](docs/当前状态.md) | 运行态快照：服务/端口/真实测试数据/下一步 |
| 00 | [项目初心与目标](docs/00-项目初心与目标.md) | 为什么做 + 三重目标 + 验收标尺 |
| 01 | [需求文档](docs/01-需求文档.md) | 原系统梳理 + 筛选决策 + 需求定义 + 面试叙事 |
| 02 | [技术架构方案](docs/02-技术架构方案.md) | 架构对比 + 技术-业务映射 + 技术链路 + 决策记录 |
| 03 | [数据库设计](docs/03-数据库设计.md) | 20 张表 + 索引论证 + 面试深挖点 |
| 04 | [接口设计](docs/04-接口设计.md) | 61 个接口 + 46 个错误码 + WebSocket 协议 |
| 05 | [开发计划](docs/05-开发计划.md) | 66 个微任务 + M6 会议纪要 + 演示脚本 |
| 06 | [环境准备与配置清单](docs/06-环境准备与配置清单.md) | 环境检测 + 配置填写 |
| 07 | [项目理解指南](docs/07-项目理解指南.md) | 架构全景 + 链路走读 + 难点与踩坑 |

---

## 🧪 验证

```bash
# 后端单测
cd server && mvn -pl meeting-service test          # 262 例（1 skipped）
cd server && mvn test                             # 全量 318 例

# 前端单测 / 类型检查 / 构建
cd web && npm run check                           # 157 例
cd web && npm run build

# 部署验收（需要 Nginx 入口；纯本地 8080/5173 模式请把 BASE 指向实际入口）
BASE=http://localhost:8000 NACOS=http://localhost:8848 bash scripts/verify-deploy.sh   # 15/15
# COS 存储时跳过 /files/local 检查：CHECK_LOCAL_FILES=0 ...

# 纪要链路（需服务已启动）
bash scripts/verify-minutes.sh                    # 23/23
bash scripts/verify-minutes-ai.sh                 # 8/8
bash scripts/verify-cos-upload.sh                 # 1/1（需 COS 环境变量）

# 真浏览器 E2E
node web/e2e/e2e-meeting-minutes.cjs              # 11/11
node web/e2e/e2e-multi-tab-auth.cjs               # 3/3
```

## 🔁 CI（GitHub Actions）

- [.github/workflows/ci.yml](.github/workflows/ci.yml)
  - `backend`：MySQL/Redis/RabbitMQ service containers + `mvn test`（业务连接使用最小权限 `smt_app`；排除依赖真实 auth-service 的双态 Feign 测试）
  - `frontend`：Node 20 + `npm ci` + `npm run check` + `npm run build`
  - `compose-config`：`docker compose config --quiet`
  - `docker-build`：构建全部镜像 + `nginx -t` + 校验 jar 内无 `application-local.yml`
  - `compose-smoke`（手动触发）：起全栈 + `scripts/verify-deploy.sh`
- 本地等价门禁：`bash scripts/ci.sh`（需本地 infra 已启动；已跑通：后端 317、前端 157、build、compose config、Nginx `-t`）

> `AuthFeignClientTest` 的“auth 在线/离线”两个断言互斥，默认套件排除；
> 需要时按 `docs/05` 的命令单独执行。

并发验证脚本：

```bash
# 5 并发抢同一时段（断言：恰好 1 个成功，其余 40001 冲突）
bash scripts/concurrent-reserve.sh

# 1 个改期 + 3 个新建并发抢同一目标时段
bash scripts/concurrent-modify.sh 10
```

- 自动登录、自动挑选空闲时段，可重复运行
- 环境变量：`BASE_URL`（默认 :8080）/ `USERNAME` / `PASSWORD` / `ROOM_ID`
- 退出码 0 = 全部通过（可用于 CI）

---

## 🛠️ 常见问题

| 现象 | 处理 |
|------|------|
| 首次 `docker compose up --build` 很慢 | 首次要下 Maven/npm 依赖；Maven 已配阿里云镜像（`server/.maven/settings.xml`） |
| `docker compose ps` 有 infra 容器 unhealthy | 看 `docker compose logs <service>`；Nacos 启动最慢，等健康检查通过 |
| 8080 / 8000 被占用 | 在 `deploy/.env` 改 `GATEWAY_PORT` / `WEB_PORT` |
| 3306 / 6379 被本机服务占用 | 停掉本机服务，或覆盖 `MYSQL_PORT` / `REDIS_PORT` 等映射 |
| Nacos 反复重启 | Docker Desktop 内存调到 ≥ 6GB |
| 页面能开但接口 502 | 网关或后端还没注册到 Nacos；`docker compose logs -f gateway meeting-service` |
| 想清空演示数据 | `docker compose down -v` 后重新 `up`（会重跑 01+02） |
| 会议纪要上传/播放失败 | 默认 local 存储请从 :8000 访问；真实 ASR 必须配 COS，不能只用 local |
| 用 compose 打开已有真实会议，音频提示不存在 | 默认 local 与已有 COS 对象不匹配；在 `deploy/.env` 设 `STORAGE_PROVIDER=cos` 并填 `COS_*` |
| 改了 `MYSQL_ROOT_PASSWORD` 后 MySQL 一直 unhealthy | 该变量只在首次初始化数据卷时生效；已有卷需 `ALTER USER` 或 `down -v`（清数据） |
| 改了 `RABBITMQ_USER/PASSWORD` 后应用连不上 MQ | 同上，broker 用户只在首次初始化卷时创建；已有卷用 `rabbitmqctl` 改 |
| 本地开发时服务注册到错误 IP | 本地 profile 用 `127.0.0.1`；compose 内通过 `SPRING_CLOUD_NACOS_DISCOVERY_IP=""` 自动探测容器 IP |
| 用 `-p` 做隔离验证后 `down` 找错网络 | `down` 必须传与 `up` 相同的 `SMT_NETWORK` / `*_CONTAINER_NAME` 变量 |
| `verify-deploy.sh` 的 `/files/local` 检查失败 | 该检查只适用于 `STORAGE_PROVIDER=local`；COS 部署用 `CHECK_LOCAL_FILES=0` |

---

*开发按 `docs/05-开发计划.md` 的微任务顺序推进；当前进度：M0~M5 ✅（含容器化与部署），M6 会议纪要 ✅（COS 分片 + 腾讯 ASR 自动转写 + 逐句联动 + DeepSeek 纪要 + Word 导出）。*
