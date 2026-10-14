# 会议室预约系统（new-meeting-systems）

> **全栈实践项目**：Spring Cloud 微服务（Java 17）+ React 18，从真实企业会议室系统提炼核心业务链路重构。
> **核心解决两个工程问题**：① 并发预约下的时段冲突控制（分布式锁 + 区间判重）② 会议状态生命周期的一致性（状态机 + 定时补偿 + 事件驱动）。

---

## 📚 文档索引（docs/）

| 编号 | 文档 | 内容 |
|------|------|------|
| 01 | [需求文档](docs/01-需求文档.md) | 原系统梳理 + 筛选决策 + 需求定义 + 面试叙事 |
| 02 | [技术架构方案](docs/02-技术架构方案.md) | 架构对比 + 技术-业务映射 + 技术链路 + 决策记录 |
| 03 | [数据库设计](docs/03-数据库设计.md) | 10 张表 + 索引论证 + 面试深挖点 |
| 04 | [接口设计](docs/04-接口设计.md) | 40 接口 + 30 错误码 + WebSocket 协议 |
| 05 | [开发计划](docs/05-开发计划.md) | 66 个微任务（五要素）+ 演示脚本 + 风险预案 |
| 06 | [环境准备与配置清单](docs/06-环境准备与配置清单.md) | 环境检测 + 配置填写（真实密钥见 gitignore 的 .local 版） |

## 🚀 快速启动

```bash
# 1. 基础设施（MySQL 8 / Redis 7 / RabbitMQ 3.13 / Nacos 2.4）
cd deploy && docker compose -f docker-compose.infra.yml up -d
docker compose -f docker-compose.infra.yml ps        # 4 容器全 Up 即就绪

# 2. 数据库（首次启动 compose 时自动初始化；手动重建用：）
mysql -h127.0.0.1 -uroot -p < db/01-schema.sql
mysql -h127.0.0.1 -uroot -p < db/02-seed.sql

# 3. 后端（M1 起逐步补齐）
cd server && mvn spring-boot:run -pl gateway         # :8080

# 4. 前端（M1 起逐步补齐）
cd web && pnpm install && pnpm dev                   # :5173（代理 /api → :8080）
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

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 管理员（会议室管理/用户管理/看板） |
| approver1 / approver2 | user123 | 审批人（梧桐厅/红枫厅的预约需审批） |
| user1 / user2 / user3 | user123 | 普通用户 |

## ⚙️ 端口约定

| 类别 | 端口 |
|------|------|
| 服务 | gateway 8080 / auth 8081 / meeting 8082 / notification 8083 |
| 中间件 | MySQL 3306 / Redis 6379 / RabbitMQ 5672+15672 / Nacos 8848+9848 |
| 前端 | 开发 5173 / 部署（Nginx）8000 |

---

*开发按 `docs/05-开发计划.md` 的 66 个微任务顺序推进；当前进度：M0 完成 ✅（T0.1-T0.3）。*
