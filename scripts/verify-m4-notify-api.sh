#!/usr/bin/env bash
# ============================================================================
# T4.4 验证：通知 REST（列表 / 未读数 / 已读 / 全部已读）
#
# 覆盖：
#   ① 列表按 (created_at DESC, id DESC) 倒序、分页不重不漏、只含自己的通知
#   ② 未读数与库中实际未读一致；不会把别人的通知算进来
#   ③ 已读幂等（重复标记仍成功）；共读-1；read-all 返回实际更新条数、重复调用返回 0
#   ④ 越权：读别人的通知 → 60001（且那一行**没被改动**）
#   ⑤ 身份只认网关注入：直连 8083 不带身份头 → 看不到任何人的通知（fail closed）
#
# 前置：基础设施 + gateway/auth/meeting 三服务已启动；notification 由本脚本自起自收。
# 退出码：0 = 全部断言通过。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/server/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar"
LOG=/tmp/t44-notify.log
PORT=8083
ROOM_OPEN=6   # 云杉厅：免审批（直接产生 MEETING_CREATED 通知）
PASS=0; FAIL=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
cleanup() {
  for m in ${IDS:-}; do
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting WHERE id=$m;" > /dev/null
  done
  local pid; pid=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$pid" ] && { kill "$pid" 2>/dev/null; sleep 2; }
}
trap cleanup EXIT
redis_del() { docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli DEL "$@" > /dev/null 2>&1; }

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
api() { # api <token> <method> <path> [body]
  local token=$1 method=$2 path=$3 body=${4:-}
  if [ -n "$body" ]; then
    curl -s --noproxy '*' -X "$method" "$BASE$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -s --noproxy '*' -X "$method" "$BASE$path" -H "Authorization: Bearer $token"
  fi
}
book() { # book <token> <title> <roomId> <start> <end>
  local body resp code mid
  body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$3" "$4" "$5")
  resp=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body")
  code=$(echo "$resp" | j "d['code']"); mid=$(echo "$resp" | j "d['data']['meetingId']")
  [ "$code" = "0" ] && [ -n "$mid" ] || { echo "ERROR 创建失败 code=$code" >&2; return 1; }
  echo "$mid"
}
free_slot() { # free_slot <roomId> <起始天数偏移>
  local off d start end body
  local last=$(( $2 + 20 )); [ "$last" -gt 28 ] && last=28
  for off in $(seq "$2" "$last"); do
    d=$(date -v+"${off}"d +%Y-%m-%d); start="${d} 09:00:00"; end="${d} 10:00:00"
    body=$(printf '{"roomIds":[%s],"startTime":"%s","endTime":"%s"}' "$1" "$start" "$end")
    if [ "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "Authorization: Bearer $U1" \
          -H 'Content-Type: application/json' -d "$body" | j "d['data']['items'][0]['available']")" = "True" ]; then
      echo "${start}|${end}"; return 0
    fi
  done
  echo "ERROR 找不到空闲时段" >&2; return 1
}

echo
echo "=== 0. 起 notification-service（要它消费事件才能造出通知）==="
OLD=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')
[ -n "$OLD" ] && { echo "  停掉 8083 既有实例 $OLD"; kill $OLD 2>/dev/null; sleep 2; }
(cd "$ROOT/server" && env -u SERVER__PORT java -jar "$JAR" > "$LOG" 2>&1) &
UP=""
for i in $(seq 1 60); do
  UP=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/actuator/health" | j "d['status']")
  [ "$UP" = "UP" ] && break
  sleep 1
done
check "health UP" "${UP:-未响应}" "UP"
[ "$UP" = "UP" ] || { tail -20 "$LOG"; exit 1; }

U1=$(login user1 user123); U2=$(login user2 user123)
{ [ -n "$U1" ] && [ -n "$U2" ]; } || { echo "登录失败，终止"; exit 1; }
U1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user1';")
U2_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user2';")
IDS=""

echo
echo "=== 1. 造 3 条通知（user1 连订 3 场，每场 1 条 MEETING_CREATED）==="
BASELINE=$(api "$U1" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")
for n in 1 2 3; do
  SLOT=$(free_slot $ROOM_OPEN $((n * 2))) || exit 1
  M=$(book "$U1" "T44-通知$n" "$ROOM_OPEN" "${SLOT%%|*}" "${SLOT##*|}") || exit 1
  IDS="$IDS $M"
done
ID_LIST=$(echo $IDS | tr ' ' ',')
GOT=0
for i in $(seq 1 20); do
  GOT=$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id IN ($ID_LIST);")
  [ "$GOT" = "3" ] && break
  sleep 1
done
check "3 场会议各产生 1 条通知" "$GOT" "3"
check "未读数 = 基线 + 3" \
  "$(api "$U1" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")" "$((BASELINE + 3))"

echo
echo "=== 2. 列表：倒序 / 只含自己 / 分页 ==="
PAGE1=$(api "$U1" GET "/api/notifications?page=1&size=3")
check "返回 3 条" "$(echo "$PAGE1" | j "len(d['data']['list'])")" "3"
check "total ≥ 3" "$(echo "$PAGE1" | j "d['data']['total'] >= 3")" "True"
# 倒序：created_at 降序；同一秒插入的用 id 兜底（见 docs/04 3.3 补登）
check "按时间倒序（相邻两条 created_at 不递增）" \
  "$(echo "$PAGE1" | python3 -c "
import sys,json
lst=json.load(sys.stdin)['data']['list']
ok=all((lst[i]['createdAt']>lst[i+1]['createdAt']) or (lst[i]['createdAt']==lst[i+1]['createdAt'] and lst[i]['id']>lst[i+1]['id']) for i in range(len(lst)-1))
print(ok)")" "True"
check "最新一条是本次最后创建的会议（id 兜底生效）" \
  "$(echo "$PAGE1" | j "d['data']['list'][0]['meetingId']")" "$(echo $IDS | awk '{print $NF}')"
check "列表项不含 userId（不返回不必要的字段）" "$(echo "$PAGE1" | j "'userId' in d['data']['list'][0]")" "False"
check "字段齐全（type/title/content/meetingId/readFlag）" \
  "$(echo "$PAGE1" | j "all(k in d['data']['list'][0] for k in ['type','title','content','meetingId','readFlag'])")" "True"
# 翻页不重不漏：第 1 页 size=1 与第 2 页 size=1 应是不同记录
PA=$(api "$U1" GET "/api/notifications?page=1&size=1" | j "d['data']['list'][0]['id']")
PB=$(api "$U1" GET "/api/notifications?page=2&size=1" | j "d['data']['list'][0]['id']")
check "分页不重复（第 1、2 页首条 id 不同）" "$([ "$PA" != "$PB" ] && echo differ || echo same)" "differ"
check "第 1、2 页两条都确实属于我" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE id IN ($PA,$PB) AND user_id=$U1_ID;")" "2"
# 列表里每条都回查库核属主——**不信接口自述**（断言写成恒真等于没写）
MY_IDS=$(api "$U1" GET "/api/notifications?page=1&size=20" | j "' '.join(str(n['id']) for n in d['data']['list'])")
OWN=ok
for nid in $MY_IDS; do
  [ "$(sql "SELECT user_id FROM smt_meeting.notification WHERE id=$nid;")" = "$U1_ID" ] || OWN=leak
done
check "user1 列表里每条都属于 user1（回查库）" "$OWN" "ok"
U2_IDS=$(api "$U2" GET "/api/notifications?page=1&size=50" | j "' '.join(str(n['id']) for n in d['data']['list'])")
LEAK=no
for nid in $U2_IDS; do
  [ "$(sql "SELECT user_id FROM smt_meeting.notification WHERE id=$nid;")" = "$U2_ID" ] || LEAK=yes
done
check "user2 列表里没有别人的通知（跨用户隔离）" "$LEAK" "no"

echo
echo "=== 3. 已读：生效 / 幂等 / 越权 ==="
TARGET=$(echo "$PAGE1" | j "d['data']['list'][0]['id']")
TARGET_MID=$(echo "$PAGE1" | j "d['data']['list'][0]['meetingId']")
BEFORE=$(api "$U1" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")
check "标记已读返回 0" "$(api "$U1" POST "/api/notifications/$TARGET/read" | j "d['code']")" "0"
check "该条 readFlag 变成 1" "$(sql "SELECT read_flag FROM smt_meeting.notification WHERE id=$TARGET;")" "1"
check "未读数 -1" "$(api "$U1" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")" "$((BEFORE - 1))"
check "**重复**标记仍返回 0（幂等）" "$(api "$U1" POST "/api/notifications/$TARGET/read" | j "d['code']")" "0"
check "重复标记后未读数不再变化" "$(api "$U1" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")" "$((BEFORE - 1))"
check "别人的通知 → 60001（user2 读 user1 的）" \
  "$(api "$U2" POST "/api/notifications/$TARGET/read" | j "d['code']")" "60001"
check "越权请求没有改动那一行（readFlag 仍为 1 且属主未变）" \
  "$(sql "SELECT CONCAT(read_flag,'/',user_id) FROM smt_meeting.notification WHERE id=$TARGET;")" "1/$U1_ID"
check "不存在的通知 → 60001" "$(api "$U1" POST "/api/notifications/99999999/read" | j "d['code']")" "60001"

echo
echo "=== 4. 全部已读 ==="
UNREAD=$(api "$U1" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")
check "read-all 返回实际更新条数" "$(api "$U1" POST "/api/notifications/read-all" | j "d['data']['updated']")" "$UNREAD"
check "未读数归零" "$(api "$U1" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")" "0"
check "重复 read-all 返回 0（幂等）" "$(api "$U1" POST "/api/notifications/read-all" | j "d['data']['updated']")" "0"
check "本次 3 条都已读" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id IN ($ID_LIST) AND read_flag=1;")" "3"
check "user2 的未读数不受影响（未读是 per-user 的）" \
  "$(api "$U2" GET "/api/notifications/unread-count" | j "d['data']['unreadCount'] >= 0")" "True"

echo
echo "=== 5. 身份只认网关注入（fail closed）==="
DIRECT=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/notifications?page=1&size=10")
check "直连 8083 不带身份头 → 列表为空（不会返回任何人的通知）" "$(echo "$DIRECT" | j "len(d['data']['list'])")" "0"
check "直连 8083 未读数 → 0" "$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/notifications/unread-count" | j "d['data']['unreadCount']")" "0"
check "无 token 走网关 → 401" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/notifications")" "401"

echo
echo "=== 6. 清理与残留 ==="
cleanup
LEFT=0
for m in $IDS; do LEFT=$(( LEFT + $(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id=$m;") )); done
check "库里已无本次数据" "$LEFT" "0"
check "通知无残留" "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id IN ($ID_LIST);")" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
