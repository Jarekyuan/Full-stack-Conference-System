#!/usr/bin/env bash
# T5.7 部署验收：只通过 Nginx 入口验证全栈，不直连内部服务。
# 覆盖：SPA 首页/深层路由、登录、会议室/会议 API、Nacos 四服务注册、
#       /files/local 反代、WebSocket 握手。
#
# 用法：
#   bash scripts/verify-deploy.sh
#   BASE=http://localhost:18000 NACOS=http://localhost:18848 bash scripts/verify-deploy.sh
#   CHECK_LOCAL_FILES=0 ...   # STORAGE_PROVIDER=cos 时跳过本地文件路由检查
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${BASE:-http://localhost:8000}"
NACOS="${NACOS:-http://localhost:8848}"
CHECK_LOCAL_FILES="${CHECK_LOCAL_FILES:-1}"
CURL=(curl -sS --connect-timeout 3 --max-time 10)
PASS=0
FAIL=0
TMP_INDEX="$(mktemp)"
trap 'rm -f "$TMP_INDEX"' EXIT

check() {
  local name="$1" actual="$2" expected="$3"
  if [ "$actual" = "$expected" ]; then
    PASS=$((PASS + 1)); echo "✓ ${name}"
  else
    FAIL=$((FAIL + 1)); echo "✗ ${name}  expected=${expected} actual=${actual}"
  fi
}

jget() {
  python3 -c 'import sys,json
d=json.load(sys.stdin)
for p in sys.argv[1].split("."):
    d = d[int(p)] if p.isdigit() else d[p]
print(d)' "$1"
}

echo "==> 入口：$BASE（Nacos：$NACOS）"

# 1) 首页
INDEX_CODE=$("${CURL[@]}" -o "$TMP_INDEX" -w '%{http_code}' "$BASE/" || true)
check "首页 HTTP 200" "$INDEX_CODE" "200"
grep -q '<div id="root">' "$TMP_INDEX" && check "首页包含 SPA 挂载点" 1 1 \
  || check "首页包含 SPA 挂载点" 0 1

# 2) 深层路由刷新（history fallback）
DEEP_CODE=$("${CURL[@]}" -o "$TMP_INDEX" -w '%{http_code}' "$BASE/meetings" || true)
DEEP_TYPE=$("${CURL[@]}" -o /dev/null -w '%{content_type}' "$BASE/meetings" || true)
DEEP_TYPE="${DEEP_TYPE%%;*}"
check "深层路由 /meetings HTTP 200" "$DEEP_CODE" "200"
check "深层路由返回 HTML" "$DEEP_TYPE" "text/html"
grep -q '<div id="root">' "$TMP_INDEX" && check "深层路由回退到 index.html" 1 1 \
  || check "深层路由回退到 index.html" 0 1

# 3) 登录（经 Nginx -> gateway -> auth-service）
LOGIN=$("${CURL[@]}" -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' || true)
TOKEN=$(printf '%s' "$LOGIN" | jget data.accessToken 2>/dev/null || true)
check "admin 登录拿到 accessToken" "$([ -n "$TOKEN" ] && echo 1 || echo 0)" "1"

# 4) 会议室列表（meeting-service）
ROOMS=$("${CURL[@]}" "$BASE/api/rooms" -H "Authorization: Bearer $TOKEN" || true)
ROOM_CODE=$(printf '%s' "$ROOMS" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("code"))' 2>/dev/null || true)
ROOM_COUNT=$(printf '%s' "$ROOMS" | python3 -c 'import sys,json; print(len(json.load(sys.stdin).get("data") or []))' 2>/dev/null || echo 0)
check "会议室列表 code=0" "$ROOM_CODE" "0"
check "会议室列表非空" "$([ "${ROOM_COUNT:-0}" -gt 0 ] && echo 1 || echo 0)" "1"

# 5) 会议列表（HTTP 200 + 业务 code=0）
MEETINGS=$("${CURL[@]}" "$BASE/api/meetings?page=1&size=1" -H "Authorization: Bearer $TOKEN" || true)
MEETING_CODE=$(printf '%s' "$MEETINGS" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("code"))' 2>/dev/null || true)
check "会议列表 code=0" "$MEETING_CODE" "0"

# 6) Nacos 注册：4 个服务全部在线
SERVICES=$("${CURL[@]}" "$NACOS/nacos/v1/ns/service/list?pageNo=1&pageSize=20" || true)
for svc in gateway auth-service meeting-service notification-service; do
  echo "$SERVICES" | grep -q "\"$svc\"" \
    && check "Nacos 已注册 $svc" 1 1 \
    || check "Nacos 已注册 $svc" 0 1
done

# 7) 本地文件反代：无效签名应返回 403；落到 SPA fallback 时会是 200
if [ "$CHECK_LOCAL_FILES" = "1" ]; then
  LOCAL_CODE=$("${CURL[@]}" -o /dev/null -w '%{http_code}' \
    "$BASE/files/local/objects/x?expires=0&sig=x" || true)
  check "/files/local 路由存在（无效签名 403）" "$LOCAL_CODE" "403"
else
  echo "… 跳过 /files/local 检查（CHECK_LOCAL_FILES=0）"
fi

# 8) WebSocket 握手（Nginx Upgrade -> gateway -> notification-service）
WS_CODE=$(curl -sS --connect-timeout 3 --max-time 5 --http1.1 \
  -o /dev/null -w '%{http_code}' \
  -H 'Connection: Upgrade' \
  -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  "$BASE/ws/notifications?token=$TOKEN" 2>/dev/null || true)
check "WebSocket 握手 HTTP 101" "$WS_CODE" "101"

echo
echo "==> 通过 ${PASS}，失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
