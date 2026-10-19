#!/usr/bin/env bash
# 真实 COS 冒烟：需要 COS_SECRET_ID / COS_SECRET_KEY / COS_REGION / COS_BUCKET
# 不打印任何密钥；未配置时跳过（退出 0），日常回归不依赖真实云资源。
set -euo pipefail
cd "$(dirname "$0")/../server"

if [ -z "${COS_BUCKET:-}" ] || [ -z "${COS_SECRET_ID:-}" ] || [ -z "${COS_SECRET_KEY:-}" ] || [ -z "${COS_REGION:-}" ]; then
  echo "跳过：未配置 COS_BUCKET / COS_SECRET_ID / COS_SECRET_KEY / COS_REGION"
  exit 0
fi

mvn -q -pl meeting-service -am test \
  -Dtest=CosObjectStorageSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
