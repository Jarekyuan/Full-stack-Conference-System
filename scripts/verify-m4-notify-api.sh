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

# ---------------------------------------------------------------------------
# 限流开关（T4.8 起"创建预约"带用户级限流：默认 10 次/分钟）
# 本脚本为验证**别的**功能会连发多次预约，不开这一刀就会撞 10005——
# 报出来却是"创建失败 code=10005"，看着像功能坏了（整轮串跑时踩到过）。
# 限流本身由 `verify-m4-ratelimit.sh` 专门验证；这里只借用同一个开关（含读回确认与退出恢复）。
# 注：会与本脚本自己的 EXIT trap 链式共存（lib 里做了链式安装，不覆盖调用方的清理）。
# ---------------------------------------------------------------------------
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
  # R14.7：删除测试用的一次性用户（不再拿共享的 user1 执行 delete-all）
  if [ -n "${TMP_USER_ID:-}" ]; then
    sql "DELETE FROM smt_meeting.sys_user_role WHERE user_id=$TMP_USER_ID;
         DELETE FROM smt_meeting.sys_user WHERE id=$TMP_USER_ID;" > /dev/null
  fi
  local pid; pid=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$pid" ] && { kill "$pid" 2>/dev/null; sleep 2; }
}
trap cleanup EXIT

# 限流开关：必须在 cleanup trap 之后调用（lib 会链式安装；反过来会覆盖恢复钩子，见评审 C2）
source "$(dirname "$0")/lib/ratelimit.sh"
ratelimit_disable_for_script

redis_del() { docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli DEL "$@" > /dev/null 2>&1; }

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
# 轮询等某个计数达到期望值（通知是异步消费的，不能睡固定时间就断言）
wait_count() { # wait_count <expected> <sql> [timeout_seconds]
  local want=$1 query=$2 timeout=${3:-20} i got
  for i in $(seq 1 "$timeout"); do
    got=$(sql "$query")
    [ "$got" = "$want" ] && { echo "$got"; return 0; }
    sleep 1
  done
  echo "$got"
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
book() { # book <token> <title> <roomId> <start> <end> [userIds-csv]
  local body resp code mid
  if [ -z "${6:-}" ]; then
    body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$3" "$4" "$5")
  else
    # ⚠️ 可选参会人：此前这个函数**没有**这个分支，传进来的第 6 个参数被静默忽略——
    # 于是"给 user2 造一条未读"那段其实造了一场没有参会人的会，取消通知自然发不出去
    # （排查时最费时间的一类：参数传了、不报错、也没生效）
    body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s","participants":{"userIds":[%s]}}' \
      "$2" "$3" "$4" "$5" "$6")
  fi
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

U1=$(login user1 user123); U2=$(login user2 user123); ADMIN=$(login admin admin123)
{ [ -n "$U1" ] && [ -n "$U2" ] && [ -n "$ADMIN" ]; } || { echo "登录失败，终止"; exit 1; }
U1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user1';")
U2_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user2';")
TMP_USER_ID=""
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
# ⚠️ 原来是 `>= 0`——**恒真**（任何非负整数都过），根本验不了"read-all 没波及别人"（评审 R5）。
# 改成前后取值比对；并且先给 user2 造一条未读，否则 0 == 0 同样说明不了问题。
U2_BEFORE=$(api "$U2" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")
check "user2 自己有一条未读（作为对照基线）" "$([ "$U2_BEFORE" -ge 1 ] && echo yes || echo no)" "yes"
check "user1 的 read-all 没有波及 user2（未读数前后一致）" \
  "$(api "$U2" GET "/api/notifications/unread-count" | j "d['data']['unreadCount']")" "$U2_BEFORE"

echo
echo "=== 4.5 给 user2 造一条未读（下面要验"别人的 read-all 不影响我"）==="
SLOT4=$(free_slot $ROOM_OPEN 6) || exit 1
M4=$(book "$U1" "T44-对照" "$ROOM_OPEN" "${SLOT4%%|*}" "${SLOT4##*|}" "$U2_ID") || exit 1; IDS="$IDS $M4"
# ⚠️ 别等 user2 的"创建通知"——MEETING_CREATED 只发给**组织人**（docs/04 5.3），
# 之前在这里白等了 20 秒（那 20 秒还会把后面断言的时间预算吃掉）。
# 要让 user2 有未读，走**取消**这条路：MEETING_CANCELLED 发给参会人 ✓
CANCEL_CODE=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$M4/cancel" -H "Authorization: Bearer $U1" \
  -H 'Content-Type: application/json' -d '{"reason":"对照用"}' | j "d['code']")
# 取消的返回值必须显式断言：丢了它的话，"没通知"到底是功能坏了还是这次取消失败了，无从判断
check "取消接口返回 0（不然下面的断言无从谈起）" "$CANCEL_CODE" "0"
check "user2 收到了取消通知（未读 +1）" \
  "$(wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M4 AND user_id=$U2_ID AND read_flag=0;" 30)" "1"

echo
echo "=== 4.6 删除通知（T6.2）==="
U1_IDS=$(sql "SELECT id FROM smt_meeting.notification WHERE user_id=$U1_ID ORDER BY id;")
U1_COUNT=$(echo "$U1_IDS" | grep -c . || true)
FIRST_ID=$(echo "$U1_IDS" | sed -n '1p')
SECOND_ID=$(echo "$U1_IDS" | sed -n '2p')
THIRD_ID=$(echo "$U1_IDS" | sed -n '3p')
check "U1 至少有 3 条通知可删（前置）" "$([ "$U1_COUNT" -ge 3 ] && echo yes || echo no)" "yes"
if [ "$U1_COUNT" -ge 3 ]; then
  check "删除单条返回 0" "$(api "$U1" DELETE "/api/notifications/$FIRST_ID" | j "d['code']")" "0"
  check "单条已从库中删除" \
    "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE id=$FIRST_ID;")" "0"

  U2_NOTIF=$(sql "SELECT id FROM smt_meeting.notification WHERE user_id=$U2_ID ORDER BY id LIMIT 1;")
  check "删除别人的通知 → 60001" \
    "$(api "$U1" DELETE "/api/notifications/$U2_NOTIF" | j "d['code']")" "60001"

  # R13：批量里混入别人的 id，只能删自己的，且别人的行必须还在
  BATCH_BODY="{\"ids\":[$SECOND_ID,$THIRD_ID,$U2_NOTIF]}"
  check "批量删除只算自己的（混入别人的 id）" \
    "$(api "$U1" POST "/api/notifications/delete" "$BATCH_BODY" | j "d['data']['deleted']")" "2"
  check "批量删除后自己的两条没了" \
    "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE id IN ($SECOND_ID,$THIRD_ID);")" "0"
  check "批量删除不会删别人的" \
    "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE id=$U2_NOTIF;")" "1"

  U1_AFTER_BATCH=$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE user_id=$U1_ID;")

  # R14.7：delete-all 用一次性用户，不再清空共享账号 user1 的收件箱
  TMP_USER="t44_del_$(date +%s)"
  TMP_RESP=$(api "$ADMIN" POST "/api/users" \
    "{\"username\":\"$TMP_USER\",\"password\":\"t44_pass123\",\"realName\":\"T44删除测试\",\"roleIds\":[1],\"deptId\":2}")
  TMP_USER_ID=$(echo "$TMP_RESP" | j "d['data']")
  check "删除测试用户创建成功" \
    "$([ -n "$TMP_USER_ID" ] && [ "$TMP_USER_ID" != "None" ] && echo yes || echo no)" "yes"
  if [ -n "$TMP_USER_ID" ] && [ "$TMP_USER_ID" != "None" ]; then
    TMP_TOKEN=$(login "$TMP_USER" t44_pass123)
    TMP_SLOT=$(free_slot "$ROOM_OPEN" 24) || exit 1
    TMP_MID=$(book "$TMP_TOKEN" "T44删除测试-$(date +%s)" "$ROOM_OPEN" \
      "${TMP_SLOT%%|*}" "${TMP_SLOT##*|}") || exit 1
    IDS="$IDS $TMP_MID"
    TMP_COUNT=$(wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE user_id=$TMP_USER_ID;" 30)
    check "一次性用户收到通知（前置）" "$TMP_COUNT" "1"
    check "删除全部返回实际条数（只影响该用户）" \
      "$(api "$TMP_TOKEN" DELETE "/api/notifications" | j "d['data']['deleted']")" "1"
    check "一次性用户通知已清空" \
      "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE user_id=$TMP_USER_ID;")" "0"
  fi
  check "共享账号 user1 的收件箱未被 delete-all 波及" \
    "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE user_id=$U1_ID;")" "$U1_AFTER_BATCH"
fi

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
