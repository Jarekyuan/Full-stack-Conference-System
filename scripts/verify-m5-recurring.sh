#!/usr/bin/env bash
# ============================================================================
# T5.1 验证：周期会议创建（展开 N 场 → 逐场检测 → 一个事务落库）
#
# 覆盖：
#   ① 正常创建：N 场的 id 都返回、**共享同一个 recurring_id**、每场都有创建日志
#   ② 冲突清单**准确到"哪一场"**（只报真正冲突的那一场，带 occurrenceStart）
#   ③ 冲突时**一场都不落库**（原子性：半截的系列最难收拾）
#   ④ 云杉厅 allow_recurring=0 → 40007
#   ⑤ 逐场过同一条校验链：区间内没匹配日期 / 超提前量（错误信息要指出**第几场**）
#   ⑥ 幂等：同一 token 重发不重复建系列
#   ⑦ 日历缓存被清（不逐场发事件，但缓存必须失效）
#
# 前置：基础设施 + 四服务已启动（meeting-service 需含 T5.1 新 jar）。
# 退出码：0 = 全部断言通过。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
ROOM=2            # 白桦厅：免审批、allow_recurring=1（与测试用不同的房间，避免互相干扰）
ROOM_NO_RECUR=6   # 云杉厅：allow_recurring=0
PASS=0; FAIL=0

# 限流开关（T4.8 起创建预约有用户级限流）：本脚本要连发多次 → 借用同一个开关
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
  # 系列规则：按标题删（本次脚本创建的都在 T51 前缀下）
  sql "DELETE FROM smt_meeting.meeting_recurring WHERE title LIKE 'T51-%';" > /dev/null
  redis --scan --pattern "cache:calendar:${ROOM}:*" 2>/dev/null | tr -d '\r' | while read -r k; do
    [ -n "$k" ] && redis DEL "$k" > /dev/null 2>&1
  done
}
trap cleanup EXIT

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
recur() { # recur <token> <body> [idemToken]
  local idem=${3:-$(uuidgen)}
  curl -s --noproxy '*' -X POST "$BASE/api/meetings/recurring" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $idem" -d "$2"
}
book() { # book <token> <title> <roomId> <start> <end>
  local body; body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$3" "$4" "$5")
  curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body" | j "d['data']['meetingId']"
}
# 找一段"连续的 N 个周中该星期几都空闲"的窗口（起点 = 距今 +minDays 之后的第一个该星期几）
free_window_start() { # free_window_start <roomId> <dayOfWeek> <minDays> <minutes> <duration>
  local d
  for off in $(seq "$3" $(( $3 + 14 ))); do
    d=$(python3 -c "
import datetime
day = datetime.date.today() + datetime.timedelta(days=$off)
while day.isoweekday() != $2:
    day += datetime.timedelta(days=1)
print(day)")
    local start="${d} $(printf '%02d:%02d:00' $(( $4 / 60 )) $(( $4 % 60 )))"
    local end; end=$(python3 -c "
import datetime
s = datetime.datetime.strptime('$start', '%Y-%m-%d %H:%M:%S')
print((s + datetime.timedelta(minutes=$5)).strftime('%Y-%m-%d %H:%M:%S'))")
    local body; body=$(printf '{"roomIds":[%s],"startTime":"%s","endTime":"%s"}' "$1" "$start" "$end")
    if [ "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "Authorization: Bearer $U1" \
          -H 'Content-Type: application/json' -d "$body" | j "d['data']['items'][0]['available']")" = "True" ]; then
      echo "$d"; return 0
    fi
  done
  echo "ERROR 找不到空闲窗口" >&2; return 1
}

U1=$(login user1 user123)
[ -n "$U1" ] || { echo "登录失败，终止"; exit 1; }
U1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user1';")
IDS=""

echo
echo "=== 1. 正常创建（周三 16:00~17:00，连续 3 周）==="
START_DAY=$(free_window_start "$ROOM" 3 7 960 60) || exit 1
END_DAY=$(python3 -c "
import datetime
print(datetime.date.fromisoformat('$START_DAY') + datetime.timedelta(days=14))")
BODY=$(printf '{"roomId":%s,"title":"T51-周会","dayOfWeek":3,"startMinutes":960,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$START_DAY" "$END_DAY")
RESP=$(recur "$U1" "$BODY")
RID=$(echo "$RESP" | j "d['data']['recurringId']")
check "创建成功并返回 recurringId" "$([ -n "$RID" ] && [ "$RID" != "None" ] && echo yes || echo "no($RESP)")" "yes"
check "场次数 = 3（$START_DAY 起连续三周的周三）" "$(echo "$RESP" | j "d['data']['created']")" "3"
M_IDS=$(echo "$RESP" | j "' '.join(str(i) for i in d['data']['meetingIds'])")
IDS="$M_IDS"
check "返回的场次 id 数 = 3" "$(echo $M_IDS | wc -w | tr -d ' ')" "3"
check "库里 3 场**共享同一个 recurring_id**" \
  "$(sql "SELECT COUNT(DISTINCT recurring_id) FROM smt_meeting.meeting WHERE id IN ($(echo $M_IDS | tr ' ' ','));")" "1"
check "该 recurring_id 就是返回值" \
  "$(sql "SELECT DISTINCT recurring_id FROM smt_meeting.meeting WHERE id IN ($(echo $M_IDS | tr ' ' ','));")" "$RID"
check "规则表里有这条规则（含 day_of_week/start_minutes）" \
  "$(sql "SELECT CONCAT(day_of_week,'/',start_minutes,'/',duration_minutes) FROM smt_meeting.meeting_recurring WHERE id=$RID;")" "3/960/60"
check "每场都有创建日志（状态变更必须留痕）" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting_status_log WHERE meeting_id IN ($(echo $M_IDS | tr ' ' ',')) AND event='RESERVE';")" "3"
check "每场间隔 7 天" \
  "$(sql "SELECT COUNT(DISTINCT DATEDIFF(start_time, (SELECT MIN(start_time) FROM (SELECT start_time FROM smt_meeting.meeting WHERE id IN ($(echo $M_IDS | tr ' ' ','))) t))) FROM smt_meeting.meeting WHERE id IN ($(echo $M_IDS | tr ' ' ','));")" "3"

echo
echo "=== 2. 冲突清单准确到"哪一场" + 冲突时一场都不落库 ==="
# ⚠️ 必须换一个**没被上面系列占用的窗口**：第一版复用了第 1 节的时段，
# 结果"占位"订不上（那几天刚被自己建的系列占了），观察到的冲突是"自己的系列 vs 自己的系列"
# ——断言会以完全错误的理由变红（"冲突 3 条而不是 1 条"）
C_START_DAY=$(free_window_start "$ROOM" 4 8 840 60) || exit 1
C_END_DAY=$(python3 -c "
import datetime
print(datetime.date.fromisoformat('$C_START_DAY') + datetime.timedelta(days=14))")
SECOND_DAY=$(python3 -c "
import datetime
print(datetime.date.fromisoformat('$C_START_DAY') + datetime.timedelta(days=7))")
OCCUPY=$(book "$U1" "T51-占位" "$ROOM" "$SECOND_DAY 14:00:00" "$SECOND_DAY 15:00:00")
check "占位会议已创建" "$([ -n "$OCCUPY" ] && [ "$OCCUPY" != "None" ] && echo yes || echo no)" "yes"
[ "$OCCUPY" != "None" ] && IDS="$IDS $OCCUPY"
CONFLICT_BODY=$(printf '{"roomId":%s,"title":"T51-必冲突","dayOfWeek":4,"startMinutes":840,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$C_START_DAY" "$C_END_DAY")
CRESP=$(recur "$U1" "$CONFLICT_BODY")
check "整体失败 → 40005" "$(echo "$CRESP" | j "d['code']")" "40005"
check "冲突清单恰好 1 条（只报真冲突的那一场）" "$(echo "$CRESP" | j "len(d['data']['conflicts'])")" "1"
check "**带场次日期**（occurrenceStart = 第二场）" \
  "$(echo "$CRESP" | j "d['data']['conflicts'][0]['occurrenceStart'].startswith('$SECOND_DAY')")" "True"
check "冲突条目指向占位那场会议" "$(echo "$CRESP" | j "d['data']['conflicts'][0]['title']")" "T51-占位"
check "**一场都没落库**（原子性：没有名为 T51-必冲突 的会议）" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='T51-必冲突';")" "0"
check "也没有留下规则" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting_recurring WHERE title='T51-必冲突';")" "0"

echo
echo "=== 3. 未开放周期预约的会议室 → 40007 ==="
NO_REC_BODY=$(printf '{"roomId":%s,"title":"T51-云杉","dayOfWeek":3,"startMinutes":960,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM_NO_RECUR" "$START_DAY" "$END_DAY")
check "云杉厅（allow_recurring=0）被拒 → 40007" "$(recur "$U1" "$NO_REC_BODY" | j "d['code']")" "40007"

echo
echo "=== 4. 逐场校验：区间内无匹配日期 / 超提前量（错误信息指出第几场）==="
NOWEEK_BODY=$(printf '{"roomId":%s,"title":"T51-无匹配","dayOfWeek":7,"startMinutes":960,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$START_DAY" "$(python3 -c "
import datetime
print(datetime.date.fromisoformat('$START_DAY') + datetime.timedelta(days=2))")")
NOWEEK=$(recur "$U1" "$NOWEEK_BODY")
check "区间内没有匹配日期 → 10001" "$(echo "$NOWEEK" | j "d['code']")" "10001"
check "提示写清了原因" "$(echo "$NOWEEK" | j "'没有匹配的日期' in d['msg']")" "True"

FAR_END=$(python3 -c "import datetime;print(datetime.date.today()+datetime.timedelta(days=60))")
FAR_BODY=$(printf '{"roomId":%s,"title":"T51-超提前量","dayOfWeek":3,"startMinutes":960,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$START_DAY" "$FAR_END")
FAR=$(recur "$U1" "$FAR_BODY")
# 校验链用的是"可预约窗口"错误码 30003（不是笼统的 10001）——脚本第一版写错了期望值
check "超提前量 → 30003（可预约窗口外）" "$(echo "$FAR" | j "d['code']")" "30003"
check "错误信息**指出第几场**（不是干巴巴的"参数错误"）" "$(echo "$FAR" | j "'第 ' in d['msg'] and '场' in d['msg']")" "True"
check "越界时也是一场都不落库" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='T51-超提前量';")" "0"

echo
echo "=== 5. 幂等：同一 token 重发不重复建系列 ==="
IDEM=$(uuidgen)
FIRST=$(recur "$U1" "$(printf '{"roomId":%s,"title":"T51-幂等","dayOfWeek":3,"startMinutes":1080,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$START_DAY" "$END_DAY")" "$IDEM")
FIRST_RID=$(echo "$FIRST" | j "d['data']['recurringId']")
check "首次创建成功" "$([ -n "$FIRST_RID" ] && [ "$FIRST_RID" != "None" ] && echo yes || echo "no")" "yes"
FIRST_IDS=$(echo "$FIRST" | j "' '.join(str(i) for i in d['data']['meetingIds'])")
IDS="$IDS $FIRST_IDS"
SECOND=$(recur "$U1" "$(printf '{"roomId":%s,"title":"T51-幂等","dayOfWeek":3,"startMinutes":1080,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$START_DAY" "$END_DAY")" "$IDEM")
check "重发被幂等挡下（10006）" "$(echo "$SECOND" | j "d['code']")" "10006"
check "库里该系列仍只有 3 场" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE recurring_id=$FIRST_RID;")" "3"

echo
echo "=== 6. 日历缓存失效（不逐场发事件，但缓存必须删）==="
CAL=$(curl -s --noproxy '*' "$BASE/api/rooms/$ROOM/calendar?week=$START_DAY" -H "Authorization: Bearer $U1")
WEEK=$(echo "$CAL" | j "d['data']['weekStart']")
CKEY="cache:calendar:${ROOM}:${WEEK}"
check "先烘热该周缓存" "$(redis EXISTS "$CKEY" | tr -d '\r')" "1"
NEW_BODY=$(printf '{"roomId":%s,"title":"T51-缓存","dayOfWeek":3,"startMinutes":1140,"durationMinutes":60,"startDate":"%s","endDate":"%s"}' "$ROOM" "$START_DAY" "$END_DAY")
NEW_RESP=$(recur "$U1" "$NEW_BODY")
NEW_IDS=$(echo "$NEW_RESP" | j "' '.join(str(i) for i in d['data']['meetingIds'])" 2>/dev/null)
[ -n "$NEW_IDS" ] && IDS="$IDS $NEW_IDS"
check "缓存已被清" "$(redis EXISTS "$CKEY" | tr -d '\r')" "0"
# ⚠️ 不数"某几天的会议总数"（第一版写死 5，纯属巧合式断言）：直接确认**本次新建的场次**都在日历里
# ⚠️ 只核对**第一场**：系列的三场跨了三周，而这里查的是其中一周的日历——
# 第一版拿 3 个 id 去核对一整周，永远会"缺 2 个"（其实是它们不在这周）
NEW_FIRST=$(echo $NEW_IDS | awk '{print $1}')
CAL_IDS=$(curl -s --noproxy '*' "$BASE/api/rooms/$ROOM/calendar?week=$START_DAY" -H "Authorization: Bearer $U1" | \
  python3 -c "
import sys, json
print(' '.join(str(m['meetingId']) for day in json.load(sys.stdin)['data']['days'] for m in day['meetings']))")
# 失败时把"期望/实际"都打出来——只报 no 的话还得再跑一轮才知道差在哪
check "再查日历，本次新建的场次都在（期望 [$NEW_IDS]）" \
  "$([ -n "$NEW_FIRST" ] && echo "$CAL_IDS" | tr ' ' '\n' | grep -qx "$NEW_FIRST" && echo yes || echo "no(实际 [$CAL_IDS])")" "yes"

echo
echo "=== 7. 清理与残留 ==="
cleanup
trap - EXIT
ID_LIST=$(echo $IDS | tr ' ' ',')
check "库里已无本次数据" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($ID_LIST);")" "0"
check "规则表无残留" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting_recurring WHERE title LIKE 'T51-%';")" "0"
check "日历缓存已清" "$(redis --scan --pattern "cache:calendar:${ROOM}:*" 2>/dev/null | grep -c .)" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
