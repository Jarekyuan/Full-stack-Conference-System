#!/usr/bin/env bash
# 本地预检门禁，等价于 .github/workflows/ci.yml 的 backend + frontend + compose-config。
# 前置：本地 infra（MySQL/Redis/RabbitMQ/Nacos）已启动（docs/05 / README 方式 B）。
#
# AuthFeignClientTest 的“auth 在线/离线”双态断言需要真实 auth-service，
# 默认排除；需要时按 docs/05 的命令单独跑。
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> 后端单测（排除 AuthFeignClientTest）"
(
  cd server
  mvn -B -ntp test \
    -Dtest='!AuthFeignClientTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
)

echo "==> 前端类型检查 / lint / 单测 / 构建"
(
  cd web
  npm ci
  npm run check
  npm run build
)

echo "==> compose 配置校验"
docker compose -f deploy/docker-compose.yml config --quiet

echo
echo "CI checks passed"
