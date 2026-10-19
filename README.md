# 会议室预约系统（new-meeting-systems）

> **全栈实践项目**：Spring Cloud 微服务（Java 17）+ React 18，从真实企业会议室系统提炼核心业务链路重构。
> **核心解决两个工程问题**：① 并发预约下的时段冲突控制（分布式锁 + 区间判重）② 会议状态生命周期的一致性（状态机 + 定时补偿 + 事件驱动）。

---

## 📚 文档索引（docs/）

| 编号 | 文档 | 内容 |
|------|------|------|
| 01 | [需求文档](docs/01-需求文档.md) | 原系统梳理 + 筛选决策 + 需求定义 + 面试叙事 |
| 02 | [技术架构方案](docs/02-技术架构方案.md) | 架构对比 + 技术-业务映射 + 技术链路 + 决策记录 |
| 03 | [数据库设计](docs/03-数据库设计.md) | 20 张表 + 索引论证 + 面试深挖点 |
| 04 | [接口设计](docs/04-接口设计.md) | 62 个接口 + 45 个错误码 + WebSocket 协议 |
| 05 | [开发计划](docs/05-开发计划.md) | 66 个微任务 + M6 会议纪要 + 演示脚本 |
| 06 | [环境准备与配置清单](docs/06-环境准备与配置清单.md) | 环境检测 + 配置填写（真实密钥见 gitignore 的 .local 版） |

## 🚀 快速启动

```bash
# 1. 基础设施（MySQL 8 / Redis 7 / RabbitMQ 3.13 / Nacos 2.4）
cd deploy && docker compose -f docker-compose.infra.yml up -d
docker compose -f docker-compose.infra.yml ps        # 4 容器全 Up 即就绪

# 2. 数据库（首次启动 compose 时自动初始化；手动重建用：）
mysql -h127.0.0.1 -uroot -p < db/01-schema.sql
mysql -h127.0.0.1 -uroot -p < db/02-seed.sql

# 3. 后端（4 个服务各开一个终端；meeting-service 的 COS/DeepSeek 环境变量见 CLAUDE.md §7）
cd server && mvn -q -DskipTests clean install
java -jar gateway/target/gateway-1.0.0-SNAPSHOT.jar                    # :8080
java -jar auth-service/target/auth-service-1.0.0-SNAPSHOT.jar          # :8081
java -jar meeting-service/target/meeting-service-1.0.0-SNAPSHOT.jar    # :8082
java -jar notification-service/target/notification-service-1.0.0-SNAPSHOT.jar  # :8083

# 4. 前端
cd web && npm ci && npm run dev                      # :5173（代理 /api → :8080）
```

## 🏗️ 目录结构

```
new-meeting-systems/
├── docs/       # 01~06 设计文档
├── db/         # 数据库脚本（01-schema.sql / 02-seed.sql）
├── deploy/     # Docker 编排（infra 四件套 → M5 全量）
├── scripts/    # 演示与压测脚本（M2 起）
├── server/     # 后端：meeting-common / gateway / auth / meeting / notification
└── web/        # 前端：React 18 + TS + Vite + antd
```

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

| 类别 | 端口 |
|------|------|
| 服务 | gateway 8080 / auth 8081 / meeting 8082 / notification 8083 |
| 中间件 | MySQL 3306 / Redis 6379 / RabbitMQ 5672+15672 / Nacos 8848+9848 |
| 前端 | 开发 5173 / 部署（Nginx）8000 |

---

## 🧪 并发验证脚本（M2）

验证"多人同时抢同一会议室同一时段"的并发防护（Redisson 分布式锁 + 锁内冲突检测）：

```bash
# 5 并发抢同一时段（断言：恰好 1 个成功，其余 40001 冲突）
bash scripts/concurrent-reserve.sh

# 10 并发 × 10 轮回归（每轮自动挑选空闲时段）
bash scripts/concurrent-reserve.sh 10 --repeat 10
```

- 自动登录、**自动预检并挑选空闲时段**（可重复运行，不会撞上历史数据）
- 每轮断言"成功数 == 1"，输出成功/冲突/繁忙的分布统计
- 环境变量：`BASE_URL`（默认 :8080）/ `USERNAME` / `PASSWORD` / `ROOM_ID`（默认 1）/ `EXPECTED_SUCCESS`
- 退出码 0 = 全部轮次通过（可用于 CI）

验证"改期"与"新建"抢同一时段（跨写路径的竞态——乐观锁只保护同一行，管不住"改到哪"）：

```bash
# 1 个改期 + 3 个新建并发抢同一目标时段（断言：时段最终只落 1 场会议）
bash scripts/concurrent-modify.sh 10
```

- 每轮自动挑一天（基准时段 09-10 放 A、目标时段 15-16 做争夺），跑完自动取消本轮会议、释放槽位
- 断言：目标时段最终占用恰好 1 场 + 至少一方成功；**修复前实测 10 轮 7 轮双重预订**
- 环境变量：`BASE_URL` / `USERNAME` / `PASSWORD` / `ROOM_ID`（默认 1）/ `N_CREATE`（默认 3）
- 退出码 0 = 全部轮次通过（可用于 CI）

---

*开发按 `docs/05-开发计划.md` 的微任务顺序推进；当前进度：M0~M4 ✅，M5 **4/7**（周期/统计/看板完成，容器化与部署 T5.5~T5.7 暂缓），M6 会议纪要 ✅（COS 分片 + 腾讯 ASR 导入 + 逐句联动 + DeepSeek 纪要 + Word 导出）。*
