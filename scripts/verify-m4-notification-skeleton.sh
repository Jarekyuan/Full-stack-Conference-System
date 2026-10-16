#!/usr/bin/env bash
# ============================================================================
# T4.2 验证：notification-service 骨架
#   ① 能起来（:8083）+ health UP
#   ② 注册到 Nacos（网关才可能把它 lb:// 调起来）
#   ③ 网关 → notification-service 链路真通
#
# 关于"health UP 就等于 DB/Redis/RabbitMQ 都通"：
#   /actuator/health 的 status 是**所有健康指示器的聚合**。本服务依赖里同时有
#   DataSource / Redis / RabbitMQ，Boot 会自动注册 db / redis / rabbit 三个指示器，
#   任意一个不健康都会把整体拉成 DOWN。所以断言 `{"status":"UP"}` 已经覆盖了"三件套连得上"
#   ——这也是 T1.8 骨架当初的验收口径（"Hikari 建连（DB 通）"）。
#   （想看得更细要开 show-details，开发期没必要：只绑回环，聚合状态够用。）
#
# 脚本自己起、自己收（踩坑 #9）：跑完把 8083 关掉，不留常驻进程。
# 前置：基础设施 + gateway/auth/meeting 三服务已启动（gateway 要能转发才有链路断言）。
# 退出码：0 = 全部断言通过。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
NACOS_API="http://127.0.0.1:8848/nacos/v1/ns/instance/list"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/server/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar"
LOG=/tmp/t42-notification.log
PORT=8083
PASS=0; FAIL=0
STARTED=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
cleanup() {
  [ "$STARTED" = "1" ] || return
  local pid; pid=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$pid" ] && kill "$pid" 2>/dev/null
  for _ in $(seq 1 20); do
    [ -z "$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null)" ] && return
    sleep 0.5
  done
  [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
}
trap cleanup EXIT

echo
echo "=== 0. 准备：清掉 8083 上的残留进程（本脚本只验证、不留常驻）==="
OLD=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')
if [ -n "$OLD" ]; then echo "  发现旧进程 $OLD，先停掉"; kill $OLD 2>/dev/null; sleep 2; fi
[ -f "$JAR" ] || { echo "❌ 找不到 jar：$JAR（先 mvn -pl notification-service -am package）"; exit 1; }

echo
echo "=== 1. 启动 notification-service ==="
# env -u SERVER__PORT：否则 Spring 会读系统变量覆盖 yml 端口（踩坑 #5）
(cd "$ROOT/server" && env -u SERVER__PORT java -jar "$JAR" > "$LOG" 2>&1) &
STARTED=1
UP=""
for _ in $(seq 1 60); do
  UP=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/actuator/health" | j "d['status']")
  [ "$UP" = "UP" ] && break
  sleep 1
done
check "health 是 UP（聚合 db/redis/rabbit 三个指示器）" "${UP:-未响应}" "UP"
if [ "${UP:-}" != "UP" ]; then
  echo "  —— 启动日志尾部 ——"; tail -15 "$LOG"; exit 1
fi
check "端口 8083 由本服务的进程监听" "$([ -n "$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null)" ] && echo yes || echo no)" "yes"

echo
echo "=== 2. Nacos 注册（网关按服务名发现的前提）==="
REG=$(curl -s --noproxy '*' "$NACOS_API?serviceName=notification-service")
check "服务已在 Nacos 中" "$(echo "$REG" | j "'yes' if d['hosts'] else 'no'")" "yes"
check "注册 ip:port" "$(echo "$REG" | j "d['hosts'][0]['ip'] + ':' + str(d['hosts'][0]['port'])")" "127.0.0.1:8083"
check "实例健康" "$(echo "$REG" | j "str(d['hosts'][0]['healthy']).lower()")" "true"

echo
echo "=== 3. 网关 → notification-service（lb:// 转发链）==="
# 无 token：路由必须受保护（401 说明这条路径在网关的鉴权覆盖内，不是白名单）
check "无 token 访问 /api/notifications → 401" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/notifications")" "401"
# 有 token：T4.2 还没有 Controller（T4.4 才建），所以期望 **下游 404**。
# 关键区别：404 = 转发到了服务（只是没有这个端点）；503 = 网关找不到实例（没注册上/名字对不上）。
# 这正是"网关转发链全通、404 统一格式穿透"的验收口径（同 T1.8）。
TOKEN=$(curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"user1","password":"user123"}' | j "d['data']['accessToken']")
[ -n "$TOKEN" ] || { echo "❌ 登录失败（网关/认证服务没起来？）"; exit 1; }
GATEWAY_CODE=""
for _ in $(seq 1 30); do
  GATEWAY_CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/notifications" -H "Authorization: Bearer $TOKEN")
  [ "$GATEWAY_CODE" = "404" ] && break
  sleep 1
done
check "有 token 访问 /api/notifications → 404（转发到服务、端点待 T4.4）" "$GATEWAY_CODE" "404"
check "不是 503（503 = 网关没发现实例）" "$([ "$GATEWAY_CODE" = "503" ] && echo is503 || echo not503)" "not503"

# ⚠️ 上面那条 404 单独看是**假阳性风险**：网关自己"没有这条路由"时也回 404。
# 用响应体格式区分（实测对照）：
#   下游服务的 404 = 统一格式 {"code":10004,"msg":"资源不存在"}（common 的 NoResourceFoundException 处理）
#   网关自己的 404 = {"timestamp":...,"path":...,"status":404,"error":"Not Found","requestId":...}
# 所以"拿到 code=10004"就证明请求**确实到了服务**，不是网关兜底。
DOWNSTREAM_404=$(curl -s --noproxy '*' "$BASE/api/notifications" -H "Authorization: Bearer $TOKEN" | j "d['code']")
check "该 404 是下游统一格式（code=10004）" "$DOWNSTREAM_404" "10004"
GATEWAY_404_HAS_CODE=$(curl -s --noproxy '*' "$BASE/api/no-such-route-probe" -H "Authorization: Bearer $TOKEN" | j "'code' in d")
check "对照组：无路由路径的 404 里没有 code 字段（格式确实不同）" "$GATEWAY_404_HAS_CODE" "False"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
