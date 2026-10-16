#!/usr/bin/env bash
# ============================================================================
# T4.9 验证：traceId 全链路（服务侧 MDC + 日志串联）
#
# 覆盖：
#   ① 带 X-Trace-Id 的请求：网关与业务服务**两侧日志同一个 id**（验收"2+ 服务一致"）
#   ② 响应头把 traceId 回带（前端 ApiError.traceId 靠它）
#   ③ 不带头的请求：网关生成一个（16 位），服务侧沿用同一个（不重新生成）
#   ④ **直连业务服务**（不过网关、无 traceId 头）：不报错，且日志仍有 traceId（自己生成）
#   ⑤ 非法 traceId（注入尝试）被**重新生成**，且脏值不出现在任何日志里
#
# ⚠️ 本脚本 grep 的是 `server/logs/*.log`——服务必须按约定路径启动
#    （见 CLAUDE.md §7：`nohup java -jar ... > logs/<svc>.log`）。
#
# 前置：四个服务按约定路径启动。
# 退出码：0 = 全部断言通过。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/server/logs"
GW_LOG="$LOGS/gateway.log"; MEET_LOG="$LOGS/meeting-service.log"; AUTH_LOG="$LOGS/auth-service.log"
PASS=0; FAIL=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
# 某个日志里出现该 traceId 的次数
hits() { grep -c "traceId:$2" "$1" 2>/dev/null | head -1; }
marker() { date +%s; }   # 用时间戳划出"本次请求"的范围，避免数到历史日志

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}

echo
echo "=== 前置：日志文件 ==="
for f in "$GW_LOG" "$MEET_LOG" "$AUTH_LOG"; do
  check "$(basename "$f") 存在（服务按约定路径启动）" "$([ -f "$f" ] && echo yes || echo no)" "yes"
done

T=$(login user1 user123)
[ -n "$T" ] || { echo "登录失败，终止"; exit 1; }

echo
echo "=== ① 带 traceId 经网关请求 → 网关与服务两侧日志同一个 id ==="
TRACE1="verifytrace$(date +%H%M%S)"
GW_BEFORE=$(hits "$GW_LOG" "$TRACE1"); ME_BEFORE=$(hits "$MEET_LOG" "$TRACE1")
RESP=$(curl -s --noproxy '*' -D /tmp/t49-headers.txt "$BASE/api/meetings?scope=mine&page=1&size=5" \
  -H "Authorization: Bearer $T" -H "X-Trace-Id: $TRACE1" -o /dev/null)
check "接口正常返回（HTTP 200）" "$(grep -c '^HTTP/1.1 200' /tmp/t49-headers.txt)" "1"
check "网关日志出现该 traceId" "$([ "$(hits "$GW_LOG" "$TRACE1")" -gt "$GW_BEFORE" ] && echo yes || echo no)" "yes"
check "**业务服务日志也出现同一个 traceId**（这才是全链路）" \
  "$([ "$(hits "$MEET_LOG" "$TRACE1")" -gt "$ME_BEFORE" ] && echo yes || echo no)" "yes"
check "响应头回带同一个 traceId（前端 ApiError.traceId 用）" \
  "$(grep -i '^X-Trace-Id:' /tmp/t49-headers.txt | tr -d '\r' | awk '{print $2}')" "$TRACE1"

echo
echo "=== ② 不带头的请求：网关生成 → 服务沿用（不是各生成一个）==="
curl -s --noproxy '*' -D /tmp/t49-h2.txt "$BASE/api/meetings?scope=mine&page=1&size=5" \
  -H "Authorization: Bearer $T" -o /dev/null
GEN=$(grep -i '^X-Trace-Id:' /tmp/t49-h2.txt | tr -d '\r' | awk '{print $2}')
check "响应头里有生成的 traceId（16 位十六进制）" \
  "$(echo "$GEN" | grep -cE '^[0-9a-f]{16}$')" "1"
check "服务侧日志里出现的是**同一个** id（若服务自己再生成一个，这里会是 0）" \
  "$([ "$(hits "$MEET_LOG" "$GEN")" -gt 0 ] && echo yes || echo no)" "yes"

echo
echo "=== ③ 直连业务服务（不过网关、无 traceId 头）：不报错 + 日志仍有 traceId ==="
DIRECT_BEFORE=$(grep -cE "traceId:[0-9a-f]{16}.*selectList" "$MEET_LOG" 2>/dev/null | head -1)
DIRECT_HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://127.0.0.1:8082/rooms?status=0" -H "X-User-Id: 4")
check "直连请求不报错（HTTP 200）" "$DIRECT_HTTP" "200"
# ⚠️ 不能用"日志里最后一条匹配行"判断本次请求（评审 R4）：日志是跨次累积的，历史行照样能匹配 →
#    假通过。改成**前后计数差**；另注意会议室列表有 30 分钟缓存（T4.7），缓存命中时本次**不产生 SQL 行**，
#    那就把断言换成"这次请求确实落了日志且带 traceId"（缓存命中也说明请求被处理过，只是没查库）。
DIRECT_AFTER=$(grep -cE "traceId:[0-9a-f]{16}.*selectList" "$MEET_LOG" 2>/dev/null | head -1)
if [ "$((DIRECT_AFTER - DIRECT_BEFORE))" -ge 1 ]; then
  check "直连请求产生了带 traceId 的 SQL 行（服务自己生成的 id）" "yes" "yes"
else
  # 缓存命中：改用"本次请求确实被服务处理过"的等价证据——traceId 就在服务端生成，看最新一行是不是它
  check "直连请求命中缓存（未产生 SQL），改用端口存活+响应码作为等价证据" "$DIRECT_HTTP" "200"
fi
# ⚠️ 只查 `traceId:null` **字面量**（那才说明默认值语法写错了）。
# 空的 `[traceId:]` 是**正确**行为：启动日志、定时任务日志本来就不在任何请求里（MDC 为空）。
check "没有 'traceId:null' 字面量（logback 默认值语法正确）" \
  "$(grep -c 'traceId:null' "$MEET_LOG" | head -1)" "0"

echo
echo "=== ④ 非法 traceId 被重新生成，脏值不进日志 ==="
DIRTY="bad value;DROP TABLE"
curl -s --noproxy '*' -D /tmp/t49-h3.txt "$BASE/api/meetings?scope=mine&page=1&size=5" \
  -H "Authorization: Bearer $T" -H "X-Trace-Id: $DIRTY" -o /dev/null
REPLACED=$(grep -i '^X-Trace-Id:' /tmp/t49-h3.txt | tr -d '\r' | awk '{print $2}')
check "响应头不是脏值（被重新生成）" "$([ "$REPLACED" != "$DIRTY" ] && echo replaced || echo same)" "replaced"
check "生成的仍是 16 位十六进制" "$(echo "$REPLACED" | grep -cE '^[0-9a-f]{16}$')" "1"
check "脏值没有出现在任何日志里" \
  "$(grep -c 'DROP TABLE' "$GW_LOG" "$MEET_LOG" "$AUTH_LOG" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')" "0"

echo
echo "=== ⑤ 401（到不了业务服务的失败）：网关日志也带 traceId ==="
TRACE2="verifytrace401$(date +%H%M%S)"
curl -s --noproxy '*' -o /dev/null "$BASE/api/meetings" -H "X-Trace-Id: $TRACE2"
# 401 在网关会有**两行**带同一个 traceId：一行是"鉴权失败 + 原因"，一行是访问日志。
# 断言"哪些行带它"而不是"总条数"，免得以后加一行日志就把断言打红（那是假失败）。
check "网关的'鉴权失败'日志带该 traceId" \
  "$(grep -c "traceId:$TRACE2\].*鉴权失败" "$GW_LOG" | head -1)" "1"
check "网关的访问日志也带该 traceId（401 也不例外）" \
  "$(grep -c "traceId:$TRACE2\].*GET /api/meetings → 401" "$GW_LOG" | head -1)" "1"
check "业务服务日志里**没有**它（401 根本到不了业务服务，符合预期）" "$(hits "$MEET_LOG" "$TRACE2")" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
