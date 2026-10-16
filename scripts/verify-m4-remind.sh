#!/usr/bin/env bash
# ============================================================================
# T4.5 验证：会议提醒（定时扫描 → meeting.remind → 站内信）
#
# 覆盖：
#   ① 进入提醒窗口（开始前 ≤15 分钟）的会议 → 下一轮扫描发出 MEETING_REMIND
#   ② **收件人 = 组织人 + 参会人**（docs/04 5.3 的 2026-10-16 变更）+ 内容含标题与开始时间
#   ③ 重复扫描**不重复提醒**（remind:sent:{id}:{开始时刻} 幂等）
#   ④ 窗口外的会议（20 分钟后开始）**不提醒**
#   ⑤ 待审批（status=10）的会议不提醒——还没确认的会不该提醒
#   ⑥ 改期进入窗口 → 会提醒（幂等 key 里带开始时刻，改期自动重新武装；这就是原计划
#      "消费端比对 start_time 防旧消息"那条兜底被方案改判后消失的原因）
#
# ⚠️ 本脚本用的是**默认扫描间隔（60 秒）**，所以很慢（每个断言要等 1~2 轮）。
#    想快就临时把 meeting-service 的 `meeting.task.remind.delay-ms` 调小再跑。
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
source "$(dirname "$0")/lib/ratelimit.sh"
ratelimit_disable_for_script

BASE="${BASE_URL:-http://localhost:8080}"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/server/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar"
LOG=/tmp/t45-notify.log
PORT=8083
ROOM_NEAR=6       # 云杉厅：免审批（窗口内那场）
ROOM_FAR=4        # 银杏厅：免审批（窗口外那场 + 改期验证）
ROOM_APPROVAL=5   # 红枫厅：需审批
SCAN_WAIT=100     # 等一轮扫描的上限（秒）：默认间隔 60s + 处理时间 + 余量
PASS=0; FAIL=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
redis() { docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli "$@"; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
# 等某个计数达到期望值（扫描是异步的，只能轮询；超时后返回最后一次的值，交给断言报错）
wait_count() { # wait_count <expected> <sql> [timeout_seconds]
  local want=$1 query=$2 timeout=${3:-$SCAN_WAIT} i got
  for i in $(seq 1 "$timeout"); do
    got=$(sql "$query")
    [ "$got" = "$want" ] && { echo "$got"; return 0; }
    sleep 1
  done
  echo "$got"
}
# 等"再扫描若干秒之后计数**仍然**不变"（负例断言：不提醒 / 不重复提醒）
wait_stable() { # wait_stable <expected> <sql> [seconds]
  local want=$1 query=$2 secs=${3:-70} i got
  for i in $(seq 1 "$secs"); do
    got=$(sql "$query")
    [ "$got" = "$want" ] || { echo "$got"; return 0; }   # 变了：立刻返回实际值，让断言失败
    sleep 1
  done
  echo "$got"
}
cleanup() {
  for m in ${IDS:-}; do
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting WHERE id=$m;" > /dev/null
    # 提醒标记也在 Redis 里（脚本写过的每个存储都要清），--scan 而不是 KEYS
    redis --scan --pattern "remind:sent:${m}:*" 2>/dev/null | tr -d '\r' | while read -r k; do
      [ -n "$k" ] && redis DEL "$k" > /dev/null 2>&1
    done
  done
  local pid; pid=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$pid" ] && { kill "$pid" 2>/dev/null; sleep 2; }
}
trap cleanup EXIT

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
book() { # book <token> <title> <roomId> <start> <end> [userIds-csv]
  local body resp code mid
  if [ -z "${6:-}" ]; then
    body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$3" "$4" "$5")
  else
    body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s","participants":{"userIds":[%s]}}' \
      "$2" "$3" "$4" "$5" "$6")
  fi
  resp=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body")
  code=$(echo "$resp" | j "d['code']"); mid=$(echo "$resp" | j "d['data']['meetingId']")
  [ "$code" = "0" ] && [ -n "$mid" ] || { echo "ERROR 创建失败 code=$code resp=$resp" >&2; return 1; }
  echo "$mid"
}
at() { date -v+"$1"M +"%Y-%m-%d %H:%M:00"; }        # 相对当前时间 +N 分钟的时刻
plus() { date -j -v+"$2"M -f "%Y-%m-%d %H:%M:%S" "$1" +"%Y-%m-%d %H:%M:%S"; }
# 在指定会议室、指定分钟区间里挑一个空闲时段，回显 "start|end"
# （写死偏移的写法踩过坑：两场会排在同一个会议室、时长各 60 分钟，后一场被前一场挡住 → 40001）
pick_slot() { # pick_slot <roomId> <min_offset> <max_offset> <duration_min> [excludeMeetingId]
  local off start end body
  for off in $(seq "$2" "$3"); do
    start=$(at "$off"); end=$(plus "$start" "$4")
    body=$(printf '{"roomIds":[%s],"startTime":"%s","endTime":"%s","excludeMeetingId":%s}' \
      "$1" "$start" "$end" "${5:-null}")
    if [ "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "Authorization: Bearer $U1" \
          -H 'Content-Type: application/json' -d "$body" | j "d['data']['items'][0]['available']")" = "True" ]; then
      echo "${start}|${end}"; return 0
    fi
  done
  echo "ERROR 会议室 $1 在 $2~$3 分钟内没有 $4 分钟的空闲时段" >&2; return 1
}

echo
echo "=== 0. 起 notification-service（消费端）==="
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
CONS=""
for i in $(seq 1 20); do
  CONS=$(curl -s --noproxy '*' -u guest:guest "http://127.0.0.1:15672/api/queues/%2F/meeting.remind" | j "d.get('consumers') or 0")
  [ "$CONS" = "1" ] && break
  sleep 1
done
check "meeting.remind 队列已有消费者（T4.5 把 remind 接上了）" "$CONS" "1"

U1=$(login user1 user123); U2=$(login user2 user123)
{ [ -n "$U1" ] && [ -n "$U2" ]; } || { echo "登录失败，终止"; exit 1; }
U1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user1';")
U2_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user2';")
IDS=""

echo
echo "=== 1. 造两场会：窗口外一场（$ROOM_FAR 室）、窗口内一场（$ROOM_NEAR 室，含参会人）==="
# 两个场景用**不同会议室**：同一间房里"3 分钟后开始"与"20 分钟后开始"的两场 60 分钟会议必然重叠
# （第一次写就是这么撞的：第二场创建直接被 40001 挡下）
FAR_SLOT=$(pick_slot "$ROOM_FAR" 35 55 60) || exit 1
MFAR=$(book "$U1" "T45-窗口外" "$ROOM_FAR" "${FAR_SLOT%%|*}" "${FAR_SLOT##*|}") || exit 1; IDS="$IDS $MFAR"
NEAR_SLOT=$(pick_slot "$ROOM_NEAR" 3 10 60) || exit 1
NEAR_START="${NEAR_SLOT%%|*}"
MNEAR=$(book "$U1" "T45-窗口内" "$ROOM_NEAR" "$NEAR_START" "${NEAR_SLOT##*|}" "$U2_ID") || exit 1; IDS="$IDS $MNEAR"
echo "  窗口外: #$MFAR 开始于 ${FAR_SLOT%%|*}（>15 分钟，应不提醒）"
echo "  窗口内: #$MNEAR 开始于 $NEAR_START（≤15 分钟，应提醒；参会人 user2）"

echo
echo "=== 2. 窗口内的会议收到提醒（收件人 = 组织人 + 参会人）==="
check "组织人收到 MEETING_REMIND" \
  "$(wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MNEAR AND user_id=$U1_ID AND type='MEETING_REMIND';")" "1"
check "参会人 user2 也收到" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MNEAR AND user_id=$U2_ID AND type='MEETING_REMIND';")" "1"
check "标题是「会议即将开始」" \
  "$(sql "SELECT title FROM smt_meeting.notification WHERE meeting_id=$MNEAR AND user_id=$U1_ID AND type='MEETING_REMIND';")" "会议即将开始"
check "内容含会议标题与开始时间（HH:mm）" \
  "$(sql "SELECT IF(content LIKE '%T45-窗口内%' AND content LIKE '%${NEAR_START:11:5}%', 'ok', content) FROM smt_meeting.notification WHERE meeting_id=$MNEAR AND user_id=$U1_ID AND type='MEETING_REMIND';")" "ok"
check "提醒是未读（read_flag=0）" \
  "$(sql "SELECT read_flag FROM smt_meeting.notification WHERE meeting_id=$MNEAR AND user_id=$U1_ID AND type='MEETING_REMIND';")" "0"

echo
echo "=== 3. 负例：窗口外的会议不提醒 / 重复扫描不重复提醒 ==="
# 这一段要**真的等**：等够一轮扫描，看"不该出现的没出现、已经出现的没重复"
check "窗口外的会议（${FAR_SLOT%%|*} 开始）始终没有提醒" \
  "$(wait_stable 0 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MFAR AND type='MEETING_REMIND';" 70)" "0"
check "窗口内的会议没有收到第二条（幂等；组织人+参会人各 1 条提醒 + 组织人还有 1 条创建通知 = 3）" \
  "$(wait_stable 3 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MNEAR;" 70)" "3"
check "Redis 里只有一个提醒标记（remind:sent:{id}:{开始时刻}）" \
  "$(redis --scan --pattern "remind:sent:${MNEAR}:*" 2>/dev/null | grep -c .)" "1"

echo
echo "=== 4. 待审批（10）的会议不提醒 ==="
AP_SLOT=$(pick_slot "$ROOM_APPROVAL" 3 12 60) || exit 1
MAP=$(book "$U1" "T45-待审批" "$ROOM_APPROVAL" "${AP_SLOT%%|*}" "${AP_SLOT##*|}") || exit 1; IDS="$IDS $MAP"
check "这场会确实是待审批态（10）" "$(sql "SELECT status FROM smt_meeting.meeting WHERE id=$MAP;")" "10"
check "待审批的会议不产生提醒" \
  "$(wait_stable 0 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MAP AND type='MEETING_REMIND';" 70)" "0"

echo
echo "=== 5. 改期进入窗口 → 会提醒（幂等 key 带开始时刻，自动重新武装）==="
# MFAR 原本在窗口外（从未提醒）；把它改到 5 分钟后 → 应当收到提醒
NEW_SLOT=$(pick_slot "$ROOM_FAR" 4 12 60 "$MFAR") || exit 1
NEW_START="${NEW_SLOT%%|*}"
UPD=$(printf '{"title":"T45-窗口外-改期","roomId":%s,"startTime":"%s","endTime":"%s","version":0}' "$ROOM_FAR" "$NEW_START" "${NEW_SLOT##*|}")
check "改期接口返回 0" \
  "$(curl -s --noproxy '*' -X PUT "$BASE/api/meetings/$MFAR" -H "Authorization: Bearer $U1" \
     -H 'Content-Type: application/json' -d "$UPD" | j "d['code']")" "0"
check "改期前它一条提醒都没有（全程在窗口外）" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MFAR AND type='MEETING_REMIND';")" "0"
check "改期后进入提醒窗口 → **恰好**收到 1 条（幂等 key 换了新时刻，自动重新武装）" \
  "$(wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MFAR AND type='MEETING_REMIND';")" "1"
check "提醒内容用的是**新**时间" \
  "$(sql "SELECT IF(content LIKE '%${NEW_START:11:5}%', 'ok', content) FROM smt_meeting.notification WHERE meeting_id=$MFAR AND type='MEETING_REMIND' ORDER BY id DESC LIMIT 1;")" "ok"

echo
echo "=== 6. 清理与残留 ==="
cleanup
LEFT=0
for m in $IDS; do LEFT=$(( LEFT + $(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id=$m;") )); done
check "库里已无本次数据" "$LEFT" "0"
ID_LIST=$(echo $IDS | tr ' ' ',')
check "通知无残留" "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id IN ($ID_LIST);")" "0"
REM_KEY_LEFT=0
for m in $IDS; do
  REM_KEY_LEFT=$(( REM_KEY_LEFT + $(redis --scan --pattern "remind:sent:${m}:*" 2>/dev/null | grep -c .) ))
done
check "Redis 提醒标记已清空（脚本写过的每个存储都要清）" "$REM_KEY_LEFT" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
