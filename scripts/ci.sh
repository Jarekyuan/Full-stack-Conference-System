#!/usr/bin/env bash
# 本地预检门禁，等价于 .github/workflows/ci.yml 的 backend + frontend + compose-config。
# 前置：本地 infra（MySQL/Redis/RabbitMQ/Nacos）已启动（docs/05 / README 方式 B）。
#
# AuthFeignClientTest 的“auth 在线/离线”双态断言需要真实 auth-service，
# 默认排除；需要时按 docs/05 的命令单独跑。
set -euo pipefail
cd "$(dirname "$0")/.."
export TZ=Asia/Shanghai

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

echo "==> Nginx 配置校验"
docker run --rm \
  --add-host gateway:127.0.0.1 \
  --add-host meeting-service:127.0.0.1 \
  -v "$PWD/deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:1.27-alpine nginx -t

# 本地默认不重复构建镜像（慢）；需要时 CI_BUILD_IMAGES=1 bash scripts/ci.sh
if [ "${CI_BUILD_IMAGES:-0}" = "1" ]; then
  echo "==> Docker 镜像构建"
  docker compose -f deploy/docker-compose.yml build
fi

echo
echo "CI checks passed"
