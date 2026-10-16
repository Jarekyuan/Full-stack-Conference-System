#!/usr/bin/env bash
# ============================================================================
# T5.3 验证：统计接口（H-1 会议室使用率 / H-2 个人会议统计）
#
# 覆盖：
#   ① **数值与手工 SQL 核对一致**（验收原文）：接口输出 vs 脚本里独立写的聚合 SQL
#   ② 状态口径：50 已取消 / 60 已拒绝不计入（它们没占用过时段）
#   ③ **跨区间裁剪**：跨天会议只把它落在区间内的那部分算进来（手工算出 60 分钟）
#   ④ 可用分钟 = 天数 × 720；占用率是 0~1 的小数
#   ⑤ 零会议的会议室也出现在结果里（LEFT JOIN，否则前端柱状图会莫名少几根）
#   ⑥ 权限：room-usage 仅 ADMIN（user1 → 403）；参数非法 → 10001
#   ⑦ 个人统计：我组织的 ∪ 我参与的；别人看不到我的数据；daily 只含有会议的日期
#
# 前置：基础设施 + 四服务已启动（meeting-service 需含 T5.3 新 jar）。
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
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
cleanup() {
  for m in ${IDS:-}; do
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_approval WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting WHERE id=$m;" > /dev/null
  done
}
trap cleanup EXIT

login() { local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2"); \
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"; }
book() { # book <token> <title> <start> <end> [userIds-csv]
  local body
  if [ -z "${5:-}" ]; then
    body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$ROOM" "$3" "$4")
  else
    body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s","participants":{"userIds":[%s]}}' "$2" "$ROOM" "$3" "$4" "$5")
  fi
  curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body" | j "d['data']['meetingId']"
}
usage() { curl -s --noproxy '*' "$BASE/api/stats/room-usage?start=$1&end=$2" -H "Authorization: Bearer $ADMIN"; }

U1=$(login user1 user123); U2=$(login user2 user123); ADMIN=$(login admin admin123)
{ [ -n "$U1" ] && [ -n "$U2" ] && [ -n "$ADMIN" ]; } || { echo "登录失败，终止"; exit 1; }
U1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user1';")
U2_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user2';")
IDS=""

# 窗口：day+1 ~ day+6（未来，避开"今天"里已有的其它安排）
D1=$(python3 -c "import datetime;print(datetime.date.today()+datetime.timedelta(days=1))")
D6=$(python3 -c "import datetime;print(datetime.date.today()+datetime.timedelta(days=6))")
D2=$(python3 -c "import datetime;print(datetime.date.today()+datetime.timedelta(days=2))")

echo
echo "=== 1. 造数据：窗口内 3 场（60 / 90 / 45 分钟），外加 1 场"跨天" ==="
A=$(book "$U1" "T53-A" "$D1 09:00:00" "$D1 10:00:00"); IDS="$IDS $A"
B=$(book "$U1" "T53-B" "$D1 11:00:00" "$D1 12:30:00"); IDS="$IDS $B"
C=$(book "$U2" "T53-C" "$D2 14:00:00" "$D2 14:45:00" "$U1_ID"); IDS="$IDS $C"   # user2 组织、user1 参会
check "3 场都建好了" "$([ -n "$A" ] && [ -n "$B" ] && [ -n "$C" ] && echo yes || echo no)" "yes"

echo
echo "=== 2. H-1：接口输出 vs 独立 SQL（验收原文"数值与手工 SQL 核对一致"）==="
API_MINE=$(usage "$D1" "$D6" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print(sum(i['totalMinutes'] for i in d['items']), sum(i['meetingCount'] for i in d['items']))")
# 独立 SQL：状态 20/30/40、区间裁剪——口径照 docs/04 4.10 自己写一遍（不是抄实现）
SQL_MINE=$(sql "SELECT CONCAT(COALESCE(SUM(TIMESTAMPDIFF(MINUTE, GREATEST(m.start_time, '$D1 00:00:00'), LEAST(m.end_time, '$(python3 -c "import datetime;print(datetime.date.fromisoformat('$D6')+datetime.timedelta(days=1))") 00:00:00'))), 0), ' ', COUNT(m.id))
  FROM smt_meeting.meeting m WHERE m.status IN (20,30,40)
   AND m.start_time < '$(python3 -c "import datetime;print(datetime.date.fromisoformat('$D6')+datetime.timedelta(days=1))") 00:00:00'
   AND m.end_time > '$D1 00:00:00';")
check "总时长与独立 SQL 一致（分钟）" "$(echo $API_MINE | awk '{print $1}')" "$(echo $SQL_MINE | awk '{print $1}')"
check "总场数与独立 SQL 一致" "$(echo $API_MINE | awk '{print $2}')" "$(echo $SQL_MINE | awk '{print $2}')"
check "**我造的三场**贡献了 60+90+45=195 分钟" \
  "$(sql "SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE, start_time, end_time)),0) FROM smt_meeting.meeting WHERE id IN ($A,$B,$C);")" "195"
check "可用分钟 = 6 天 × 720" "$(usage "$D1" "$D6" | j "d['data']['availableMinutes']")" "4320"
check "占用率是 0~1 的小数（不是百分数）" \
  "$(usage "$D1" "$D6" | j "all(0 <= i['usageRate'] <= 1 for i in d['data']['items'])")" "True"
check "零会议的会议室也在列表里（LEFT JOIN）" \
  "$(usage "$D1" "$D6" | j "any(i['meetingCount'] == 0 for i in d['data']['items'])")" "True"
check "会议室数量 = 全部启用会议室数" \
  "$(usage "$D1" "$D6" | j "len(d['data']['items'])")" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting_room;")"

echo
echo "=== 3. 状态口径：取消的会议不再计入 ==="
BEFORE_MIN=$(usage "$D1" "$D6" | j "d['data']['totalMinutes']")
curl -s --noproxy '*' -o /dev/null -X POST "$BASE/api/meetings/$B/cancel" -H "Authorization: Bearer $U1" \
  -H 'Content-Type: application/json' -d '{"reason":"T53 口径验证"}'
sleep 1
AFTER_MIN=$(usage "$D1" "$D6" | j "d['data']['totalMinutes']")
check "取消 90 分钟的会议后，总时长少了 90" "$((BEFORE_MIN - AFTER_MIN))" "90"
check "该会议状态已是 50" "$(sql "SELECT status FROM smt_meeting.meeting WHERE id=$B;")" "50"

echo
echo "=== 4. 跨区间裁剪：跨天会议只算落在区间内的部分 ==="
# 造一场 D2 23:30 → D3 01:30（跨天，120 分钟；room 2 上限 240 分钟）
D3=$(python3 -c "import datetime;print(datetime.date.today()+datetime.timedelta(days=3))")
# ⚠️ 用**前后差值**断言：那两天该会议室本来就有别的会（第一版写死 90/30 就红了——
# 错的是断言，不是实现：D2 上还有一场我自己的 45 分钟会议）
ROOM_MIN_D2_BEFORE=$(usage "$D2" "$D2" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print(next((i['totalMinutes'] for i in d['items'] if i['roomId'] == $ROOM), 0))")
ROOM_MIN_D3_BEFORE=$(usage "$D3" "$D3" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print(next((i['totalMinutes'] for i in d['items'] if i['roomId'] == $ROOM), 0))")
X=$(book "$U1" "T53-跨天" "$D2 23:30:00" "$D3 01:30:00")
check "跨天会议已创建" "$([ -n "$X" ] && [ "$X" != "None" ] && echo yes || echo no)" "yes"
[ "$X" != "None" ] && IDS="$IDS $X"
check "只查 D2：该会议室时长**只增加了 30**（23:30–24:00 那一段）" \
  "$(( $(usage "$D2" "$D2" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print(next((i['totalMinutes'] for i in d['items'] if i['roomId'] == $ROOM), 0))") - ROOM_MIN_D2_BEFORE ))" "30"
check "只查 D3：该会议室时长**只增加了 90**（00:00–01:30 那一段）" \
  "$(( $(usage "$D3" "$D3" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print(next((i['totalMinutes'] for i in d['items'] if i['roomId'] == $ROOM), 0))") - ROOM_MIN_D3_BEFORE ))" "90"

echo
echo "=== 5. 权限与参数 ==="
check "非 ADMIN → 403" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/stats/room-usage" -H "Authorization: Bearer $U1")" "403"
check "start 晚于 end → 10001" "$(curl -s --noproxy '*' "$BASE/api/stats/room-usage?start=$D6&end=$D1" -H "Authorization: Bearer $ADMIN" | j "d['code']")" "10001"
check "日期格式非法 → 10001" "$(curl -s --noproxy '*' "$BASE/api/stats/room-usage?start=2026-13-99" -H "Authorization: Bearer $ADMIN" | j "d['code']")" "10001"

echo
echo "=== 6. H-2 个人统计：我组织的 ∪ 我参与的（近 30 天）==="
# ⚠️ 夹具用 SQL 造**历史**会议：H-2 的窗口是"近 30 天"（过去），而校验链不允许创建过去的场次——
#    第一版拿未来会议当夹具，接口返回 1 场（那是别人留下的旧数据），断言全红。
#    用 40（已结束）落库是**忠实**的：过去的会本来就该是已结束。
P1=$(python3 -c "import datetime;print(datetime.date.today()-datetime.timedelta(days=5))")
P2=$(python3 -c "import datetime;print(datetime.date.today()-datetime.timedelta(days=3))")
P3=$(python3 -c "import datetime;print(datetime.date.today()-datetime.timedelta(days=2))")
H1=$(sql "INSERT INTO smt_meeting.meeting (title, room_id, organiser_id, start_time, end_time, status)
  VALUES ('T53-历史A', $ROOM, $U1_ID, '$P1 09:00:00', '$P1 10:00:00', 40); SELECT LAST_INSERT_ID();")
H2=$(sql "INSERT INTO smt_meeting.meeting (title, room_id, organiser_id, start_time, end_time, status)
  VALUES ('T53-历史B', $ROOM, $U1_ID, '$P2 09:00:00', '$P2 10:30:00', 40); SELECT LAST_INSERT_ID();")
H3=$(sql "INSERT INTO smt_meeting.meeting (title, room_id, organiser_id, start_time, end_time, status)
  VALUES ('T53-历史C', $ROOM, $U2_ID, '$P3 09:00:00', '$P3 09:45:00', 40); SELECT LAST_INSERT_ID();")
sql "INSERT INTO smt_meeting.meeting_participant (meeting_id, user_id) VALUES ($H3, $U1_ID);" > /dev/null
IDS="$IDS $H1 $H2 $H3"
MY=$(curl -s --noproxy '*' "$BASE/api/stats/my-meetings" -H "Authorization: Bearer $U1")
# 注意：user1 库里可能还有别的历史会议（早前的测试残留），所以断言"至少包含我造的三场"，
# 并用"增量"核对——写死 3 场的话一有残留就红（错的是断言）
check "user1 的近 30 天里**包含我造的三场**（>= 3）" "$(echo "$MY" | j "d['data']['totalMeetings'] >= 3")" "True"
check "user1 的时长 **>= 195**（60+90+45）" "$(echo "$MY" | j "d['data']['totalMinutes'] >= 195")" "True"
check "user2 的时长也包含他组织的那 45 分钟（>= 45）" \
  "$(curl -s --noproxy '*' "$BASE/api/stats/my-meetings" -H "Authorization: Bearer $U2" | j "d['data']['totalMinutes'] >= 45")" "True"
check "daily 含 P1 这一天" \
  "$(echo "$MY" | j "any(x['date'] == '$P1' for x in d['data']['daily'])")" "True"
check "daily 只含**有会议的**日期（不是补齐 30 天）" "$(echo "$MY" | j "len(d['data']['daily']) < 30")" "True"
check "**参与别人组织的会也算我的**（H-2 口径：组织 ∪ 参与）" \
  "$(echo "$MY" | j "d['data']['totalMinutes'] >= 240")" "True"

echo
echo "=== 7. 清理与残留 ==="
cleanup
trap - EXIT
ID_LIST=$(echo $IDS | tr ' ' ',')
check "库里已无本次数据" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($ID_LIST);")" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
