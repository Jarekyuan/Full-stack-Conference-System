#!/usr/bin/env bash
# T5.7 部署验收：只通过 Nginx 入口验证全栈，不直连内部服务。
# 覆盖：SPA 首页/深层路由、登录、会议室/会议 API、Nacos 四服务注册、WebSocket 握手。
#
# 用法：
#   bash scripts/verify-deploy.sh
#   BASE=http://localhost:18000 NACOS=http://localhost:18848 bash scripts/verify-deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${BASE:-http://localhost:8000}"
NACOS="${NACOS:-http://localhost:8848}"
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
INDEX_CODE=$(curl -s -o "$TMP_INDEX" -w '%{http_code}' "$BASE/")
check "首页 HTTP 200" "$INDEX_CODE" "200"
grep -q '<div id="root">' "$TMP_INDEX" && check "首页包含 SPA 挂载点" 1 1 \
  || check "首页包含 SPA 挂载点" 0 1

# 2) 深层路由刷新（history fallback）
DEEP_CODE=$(curl -s -o "$TMP_INDEX" -w '%{http_code}' "$BASE/meetings")
DEEP_TYPE=$(curl -s -o /dev/null -w '%{content_type}' "$BASE/meetings" | cut -d';' -f1)
check "深层路由 /meetings HTTP 200" "$DEEP_CODE" "200"
check "深层路由返回 HTML" "$DEEP_TYPE" "text/html"
grep -q '<div id="root">' "$TMP_INDEX" && check "深层路由回退到 index.html" 1 1 \
  || check "深层路由回退到 index.html" 0 1

# 3) 登录（经 Nginx -> gateway -> auth-service）
LOGIN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')
TOKEN=$(echo "$LOGIN" | jget data.accessToken)
check "admin 登录拿到 accessToken" "$([ -n "$TOKEN" ] && echo 1 || echo 0)" "1"

# 4) 会议室列表（meeting-service）
ROOMS=$(curl -fsS "$BASE/api/rooms" -H "Authorization: Bearer $TOKEN")
ROOM_COUNT=$(echo "$ROOMS" | python3 -c 'import sys,json; print(len(json.load(sys.stdin).get("data") or []))')
check "会议室列表非空" "$([ "$ROOM_COUNT" -gt 0 ] && echo 1 || echo 0)" "1"

# 5) 会议列表（分页接口）
MEETING_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  "$BASE/api/meetings?page=1&size=1" -H "Authorization: Bearer $TOKEN")
check "会议列表 HTTP 200" "$MEETING_CODE" "200"

# 6) Nacos 注册：4 个服务全部在线
SERVICES=$(curl -fsS "$NACOS/nacos/v1/ns/service/list?pageNo=1&pageSize=20")
for svc in gateway auth-service meeting-service notification-service; do
  echo "$SERVICES" | grep -q "\"$svc\"" \
    && check "Nacos 已注册 $svc" 1 1 \
    || check "Nacos 已注册 $svc" 0 1
done

# 7) WebSocket 握手（Nginx Upgrade -> gateway -> notification-service）
WS_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --http1.1 \
  -H 'Connection: Upgrade' \
  -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  "$BASE/ws/notifications?token=$TOKEN" || true)
check "WebSocket 握手 HTTP 101" "$WS_CODE" "101"

echo
echo "==> 通过 ${PASS}，失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
