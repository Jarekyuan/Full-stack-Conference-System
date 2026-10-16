#!/usr/bin/env bash
# ============================================================================
# T5.2 验证：周期会议系列取消（只取消未来场次 / 历史不受影响 / 进行中拦截）
#
# 覆盖：
#   ① 系列取消：所有"未开始"场次变 50 + 写入取消原因 + 每场一条 CANCEL 日志
#   ② **历史场次不受影响**（已结束 40 / 已取消 50 的场次原样保留，计入 skipped）
#   ③ **进行中（30）的场次 → 整个系列被拒**（40002 + 提示先单场处理），且**一场都不变**
#   ④ 非组织人 → 40004；系列不存在 → 40008
#   ⑤ 重复取消 → cancelled=0（全部已是历史，幂等）
#   ⑥ 日历缓存被清
#
# ⚠️ 第 ③ 条要**真的等会议开始**（造一场"几分钟后开始"的会议，等定时任务把它推成进行中）——
#    这是唯一能造出"系列里含进行中场次"的办法（校验链不允许创建过去的场次）。
#
# 前置：基础设施 + 四服务已启动（meeting-service 需含 T5.2 新 jar）。
# 退出码：0 = 全部断言通过。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
ROOM=2
PASS=0; FAIL=0

source "$(dirname "$0")/lib/ratelimit.sh"
ratelimit_disable_for_script

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
redis() { docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli "$@"; }
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
  sql "DELETE FROM smt_meeting.meeting_recurring WHERE title LIKE 'T52-%';" > /dev/null
  redis --scan --pattern "cache:calendar:${ROOM}:*" 2>/dev/null | tr -d '\r' | while read -r k; do
    [ -n "$k" ] && redis DEL "$k" > /dev/null 2>&1
  done
}
trap cleanup EXIT

login() { local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2"); \
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"; }
recur() { local idem=${3:-$(uuidgen)}; \
  curl -s --noproxy '*' -X POST "$BASE/api/meetings/recurring" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $idem" -d "$2"; }
cancel_series() { curl -s --noproxy '*' -X POST "$BASE/api/meetings/recurring/$1/cancel" -H "Authorization: Bearer $2" \
  -H 'Content-Type: application/json' -d "{\"reason\":\"${3:-T52 验证}\"}"; }
free_window_start() { # free_window_start <dayOfWeek> <minDays> <minutes> <duration>
  local d start end body
  for off in $(seq "$2" $(( $2 + 14 ))); do
    d=$(python3 -c "
import datetime
day = datetime.date.today() + datetime.timedelta(days=$off)
while day.isoweekday() != $1:
    day += datetime.timedelta(days=1)
print(day)")
    start="${d} $(printf '%02d:%02d:00' $(( $3 / 60 )) $(( $3 % 60 )))"
    end=$(python3 -c "
import datetime
print((datetime.datetime.strptime('$start','%Y-%m-%d %H:%M:%S') + datetime.timedelta(minutes=$4)).strftime('%Y-%m-%d %H:%M:%S'))")
    body=$(printf '{"roomIds":[%s],"startTime":"%s","endTime":"%s"}' "$ROOM" "$start" "$end")
    if [ "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "Authorization: Bearer $U1" \
          -H 'Content-Type: application/json' -d "$body" | j "d['data']['items'][0]['available']")" = "True" ]; then
      echo "$d"; return 0
    fi
  done
  echo "ERROR 找不到空闲窗口" >&2; return 1
}

U1=$(login user1 user123); U2=$(login user2 user123)
[ -n "$U1" ] && [ -n "$U2" ] || { echo "登录失败，终止"; exit 1; }
IDS=""

echo
echo "=== 1. 系列取消：未开始场次全部变 50 + 原因 + 日志 ==="
START_DAY=$(free_window_start 2 9 900 60) || exit 1   # 周二 15:00
END_DAY=$(python3 -c "import datetime;print(datetime.date.fromisoformat('$START_DAY')+datetime.timedelta(days=14))")
RESP=$(recur "$U1" "$(printf '{"roomId":%s,"title":"T52-取消A","dayOfWeek":2,"startMinutes":900,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$START_DAY" "$END_DAY")")
RID=$(echo "$RESP" | j "d['data']['recurringId']")
MIDS=$(echo "$RESP" | j "' '.join(str(i) for i in d['data']['meetingIds'])")
[ -n "$RID" ] && [ "$RID" != "None" ] || { echo "❌ 建系列失败: $RESP"; exit 1; }
IDS="$MIDS"
MID_LIST=$(echo $MIDS | tr ' ' ',')
check "系列含 3 场" "$(echo $MIDS | wc -w | tr -d ' ')" "3"
# 先烘热日历缓存，验证取消会清它
WEEK=$(curl -s --noproxy '*' "$BASE/api/rooms/$ROOM/calendar?week=$START_DAY" -H "Authorization: Bearer $U1" | j "d['data']['weekStart']")
check "先烘热该周缓存" "$(redis EXISTS "cache:calendar:${ROOM}:${WEEK}" | tr -d '\r')" "1"

CANCEL=$(cancel_series "$RID" "$U1")
check "取消成功" "$(echo "$CANCEL" | j "d['code']")" "0"
check "cancelled = 3" "$(echo "$CANCEL" | j "d['data']['cancelled']")" "3"
check "skipped = 0（本系列没有历史场次）" "$(echo "$CANCEL" | j "d['data']['skipped']")" "0"
check "库里 3 场全是 50" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($MID_LIST) AND status=50;")" "3"
check "取消原因已写入" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($MID_LIST) AND cancel_reason='T52 验证';")" "3"
check "每场一条 CANCEL 日志（逐场走状态机）" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting_status_log WHERE meeting_id IN ($MID_LIST) AND event='CANCEL';")" "3"
check "日历缓存已被清" "$(redis EXISTS "cache:calendar:${ROOM}:${WEEK}" | tr -d '\r')" "0"

echo
echo "=== 2. 重复取消：全部已是历史 → cancelled=0（幂等，不报错）==="
AGAIN=$(cancel_series "$RID" "$U1")
check "再次取消返回 0（不是错误）" "$(echo "$AGAIN" | j "d['code']")" "0"
check "cancelled = 0 / skipped = 3" "$(echo "$AGAIN" | j "d['data']['cancelled']")|$(echo "$AGAIN" | j "d['data']['skipped']")" "0|3"

echo
echo "=== 3. 历史场次不受影响（已结束 40 的场次原样保留）==="
START_DAY2=$(free_window_start 4 10 1020 60) || exit 1  # 周四 17:00
END_DAY2=$(python3 -c "import datetime;print(datetime.date.fromisoformat('$START_DAY2')+datetime.timedelta(days=14))")
RESP2=$(recur "$U1" "$(printf '{"roomId":%s,"title":"T52-取消B","dayOfWeek":4,"startMinutes":1020,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$START_DAY2" "$END_DAY2")")
RID2=$(echo "$RESP2" | j "d['data']['recurringId']")
MIDS2=$(echo $RESP2 | j "' '.join(str(i) for i in d['data']['meetingIds'])")
[ -n "$RID2" ] && [ "$RID2" != "None" ] || { echo "❌ 建系列失败: $RESP2"; exit 1; }
IDS="$IDS $MIDS2"
F2=$(echo $MIDS2 | awk '{print $1}'); O2=$(echo $MIDS2 | awk '{print $2}'); T2=$(echo $MIDS2 | awk '{print $3}')
# 用 SQL 直接把两场造成"历史"（40 已结束 / 50 已取消）：这是**测试造场景**，
# 业务代码里 40/50 只能由状态机到达（这里绕过状态机是为了不真的等几周）
sql "UPDATE smt_meeting.meeting SET status=40 WHERE id=$F2;
     UPDATE smt_meeting.meeting SET status=50, cancel_reason='历史' WHERE id=$O2;" > /dev/null
CANCEL2=$(cancel_series "$RID2" "$U1" "T52 场景B")
check "cancelled = 1（只有那场未开始的被取消）" "$(echo "$CANCEL2" | j "d['data']['cancelled']")" "1"
check "skipped = 2（两场历史被跳过）" "$(echo "$CANCEL2" | j "d['data']['skipped']")" "2"
check "已结束那场**仍是 40**（历史不受影响）" "$(sql "SELECT status FROM smt_meeting.meeting WHERE id=$F2;")" "40"
check "已取消那场**仍是 50 且原因没被改**" "$(sql "SELECT CONCAT(status,'/',cancel_reason) FROM smt_meeting.meeting WHERE id=$O2;")" "50/历史"
check "未开始那场变成 50" "$(sql "SELECT status FROM smt_meeting.meeting WHERE id=$T2;")" "50"

echo
echo "=== 4. 进行中（30）拦截：整系列被拒，且一场都不变 ==="
# 造一场"2 分钟后开始"的场次（校验链不允许创建过去的场次，所以只能真的等它开始）
D3=$(python3 -c "import datetime;print(datetime.date.today())")
# ⚠️ 用"1 分钟后"而不是"3 分钟后"：等待循环只有 150 秒时永远等不到（第一版就是这么红的）
NOW_MIN=$(python3 -c "import datetime;n=datetime.datetime.now();print((n.hour*60+n.minute+1))")
PLUS2W=$(python3 -c "import datetime;print(datetime.date.today()+datetime.timedelta(days=14))")
START3=$(python3 -c "import datetime;print(datetime.date.today()+datetime.timedelta(days=1))")   # 让系列的第二场在明天
# 系列的第一场就在今天（几分钟后）
RESP3=$(recur "$U1" "$(printf '{"roomId":%s,"title":"T52-进行中","dayOfWeek":%s,"startMinutes":%s,"durationMinutes":30,"startDate":"%s","endDate":"%s"}' \
  "$ROOM" "$(python3 -c "import datetime;print(datetime.date.today().isoweekday())")" "$NOW_MIN" "$D3" "$PLUS2W")")
RID3=$(echo "$RESP3" | j "d['data']['recurringId']")
MIDS3=$(echo $RESP3 | j "' '.join(str(i) for i in d['data']['meetingIds'])" 2>/dev/null)
if [ -z "$RID3" ] || [ "$RID3" = "None" ]; then
  echo "  ⚠️ 造'今天几分钟后开始'的系列失败（$RESP3）——跳过本节（不影响其他断言）"
  FAIL=$((FAIL+1))
else
  IDS="$IDS $MIDS3"
  echo "  … 等定时任务把它推成进行中（每 60s 一轮，最多等 300s）"
  ONGOING=0
  for i in $(seq 1 30); do
    ONGOING=$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE recurring_id=$RID3 AND status=30;")
    [ "$ONGOING" -ge 1 ] && break
    sleep 10
  done
  check "第一场已进入进行中（30）" "$ONGOING" "1"
  BLOCK=$(cancel_series "$RID3" "$U1" "T52 场景C")
  check "整系列被拒 → 40002" "$(echo "$BLOCK" | j "d['code']")" "40002"
  check "提示里说明了原因（先单独处理）" "$(echo "$BLOCK" | j "'进行中' in d['msg']")" "True"
  check "**其余场次一场都没被取消**" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE recurring_id=$RID3 AND status=20;")" \
    "$(python3 -c "print(len('$MIDS3'.split()) - 1)")"
fi

echo
echo "=== 5. 权限与不存在 ==="
check "非组织人取消 → 40004" "$(cancel_series "$RID" "$U2" | j "d['code']")" "40004"
check "系列不存在 → 40008" "$(cancel_series 999999 "$U1" | j "d['code']")" "40008"

echo
echo "=== 6. 清理与残留 ==="
cleanup
trap - EXIT
ID_LIST=$(echo $IDS | tr ' ' ',')
check "库里已无本次数据" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($ID_LIST);")" "0"
check "规则表无残留" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting_recurring WHERE title LIKE 'T52-%';")" "0"
check "日历缓存已清" "$(redis --scan --pattern "cache:calendar:${ROOM}:*" 2>/dev/null | grep -c .)" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
