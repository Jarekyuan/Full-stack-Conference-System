#!/usr/bin/env bash
# ============================================================================
# M3 延长/提前结束验证（T3.4）
#
# 用**云杉厅（roomId=6，免审批、buffer=0）**——免审批才能造出 status=20 的"下一场"，
# 而"下一场"必须是占用态（20/30）才会被 R-15 的上限计算看见。
#
# ⚠️ 两处 SQL 造数，都有明确理由：
#   ① 把会议的 status 从 20 直接改成 30 —— 延长只对"进行中"开放，而**T3.5 的定时任务还没实现**，
#      没有 API 能让会议自然进入 30；这里模拟"开始时间到了"。T3.5 上线后这步可以改成等定时任务。
#   ② 插一条与自身重叠的占用会议 —— 用来打**冲突复检**这条兜底：它刻意绕开上限计算
#      （上限只看 `start_time >= 自身 end` 的会议），因此是唯一能验证"复检真的在守"的办法。
# 跑完会把造出来的数据从库里删掉（含 status_log / participant），不留残留。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
ROOM="${EXTEND_ROOM:-6}"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
PASS=0; FAIL=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
book() { # book <token> <title> <start> <end> → meetingId（失败即退出）
  local body resp code mid
  body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$ROOM" "$3" "$4")
  resp=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body")
  code=$(echo "$resp" | j "d['code']"); mid=$(echo "$resp" | j "d['data']['meetingId']")
  [ "$code" = "0" ] && [ -n "$mid" ] || { echo "ERROR 创建失败 code=$code $(echo "$resp" | head -c 150)" >&2; return 1; }
  echo "$mid"
}
U1=$(login user1 user123); U2=$(login user2 user123)
[ -n "$U1" ] || { echo "登录失败"; exit 1; }
IDS=""
cleanup() {
  for m in $IDS; do
    sql "DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m; DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=$m; DELETE FROM smt_meeting.meeting WHERE id=$m;" >/dev/null
  done
}
trap cleanup EXIT

D1=$(date -v+2d +%Y-%m-%d)   # 主场景日
D2=$(date -v+3d +%Y-%m-%d)   # 无下一场场景日
A_S="${D1} 10:00:00"; A_E="${D1} 11:00:00"
B_S="${D1} 12:00:00"; B_E="${D1} 13:00:00"
C_S="${D2} 18:00:00"; C_E="${D2} 19:00:00"

echo
echo "=== 准备：A(10-11) + B(12-13) + C(次日 18-19)，均免审批 ==="
A=$(book "$U1" "M3延长-A" "$A_S" "$A_E") || exit 1; IDS="$IDS $A"
B=$(book "$U1" "M3延长-B" "$B_S" "$B_E") || exit 1; IDS="$IDS $B"
C=$(book "$U1" "M3延长-C" "$C_S" "$C_E") || exit 1; IDS="$IDS $C"
check "三场都创建成功且为已预约(20)" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($A,$B,$C) AND status=20;")" "3"
check "非组织人无法延长（40004）" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$A/extend" -H "Authorization: Bearer $U2" -H 'Content-Type: application/json' -d '{"minutes":10}' | j "d['code']")" "40004"
check "未开始(20)不可延长（40002）" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$A/extend" -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' -d '{"minutes":10}' | j "d['code']")" "40002"
check "未开始(20)不可提前结束（40002）" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$A/finish" -H "Authorization: Bearer $U1" | j "d['code']")" "40002"

sql "UPDATE smt_meeting.meeting SET status=30 WHERE id IN ($A,$C);" >/dev/null
check "SQL 造数：A、C 进入进行中(30)" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($A,$C) AND status=30;")" "2"

echo
echo "=== 上限计算（R-15）==="
LIMIT=$(curl -s --noproxy '*' "$BASE/api/meetings/$A/extend-limit" -H "Authorization: Bearer $U1")
check "有下一场：上限 = 下一场(12:00) − 缓冲(0) − 当前 end(11:00) = 60" "$(echo "$LIMIT" | j "d['data']['maxDelayMinutes']")" "60"
check "reason 说清是谁卡住的" "$(echo "$LIMIT" | j "d['data']['reason']")" "下一场会议 12:00 开始"
LIMIT_C=$(curl -s --noproxy '*' "$BASE/api/meetings/$C/extend-limit" -H "Authorization: Bearer $U1")
check "无下一场：上限 = 当日 24:00 − 19:00 = 300" "$(echo "$LIMIT_C" | j "d['data']['maxDelayMinutes']")" "300"
check "reason 改为「当日无后续会议…」" "$(echo "$LIMIT_C" | j "d['data']['reason']")" "当日无后续会议，最多可延至 24:00"

echo
echo "=== 延长动作 ==="
OVER=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$A/extend" -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' -d '{"minutes":90}')
check "超上限 → 40006" "$(echo "$OVER" | j "d['code']")" "40006"
check "40006 的 data 带 maxDelayMinutes（前端弹窗要用）" "$(echo "$OVER" | j "d['data']['maxDelayMinutes']")" "60"
check "被拒后 end_time 未变" "$(sql "SELECT DATE_FORMAT(end_time,'%H:%i') FROM smt_meeting.meeting WHERE id=$A;")" "11:00"
check "延长 30 分钟 → 成功" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$A/extend" -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' -d '{"minutes":30}' | j "d['code']")" "0"
check "A 的 end_time 变成 11:30" "$(sql "SELECT DATE_FORMAT(end_time,'%H:%i') FROM smt_meeting.meeting WHERE id=$A;")" "11:30"
check "B（下一场）不受影响" "$(sql "SELECT DATE_FORMAT(start_time,'%H:%i') FROM smt_meeting.meeting WHERE id=$B;")" "12:00"
check "version 自增（乐观锁痕迹）" "$(sql "SELECT version FROM smt_meeting.meeting WHERE id=$A;")" "1"
check "时间线里有 EXTEND 事件" \
  "$(curl -s --noproxy '*' "$BASE/api/meetings/$A" -H "Authorization: Bearer $U1" | j "[l['event'] for l in d['data']['logs']]")" "['RESERVE', 'EXTEND']"
check "延长后可预约区间随之缩短（新上限 30）" \
  "$(curl -s --noproxy '*' "$BASE/api/meetings/$A/extend-limit" -H "Authorization: Bearer $U1" | j "d['data']['maxDelayMinutes']")" "30"

echo
echo "=== 冲突复检兜底（人为破坏不变量：插一条与 A 重叠的占用会议）==="
sql "INSERT INTO smt_meeting.meeting(title,room_id,organiser_id,start_time,end_time,status) VALUES('M3延长-越界占用',$ROOM,4,'$D1 10:30:00','$D1 11:00:00',20);" >/dev/null
EVIL=$(sql "SELECT id FROM smt_meeting.meeting WHERE title='M3延长-越界占用' LIMIT 1;")
IDS="$IDS $EVIL"
check "上限计算看不到它（它 start < A.end，被 MIN(start_time >= A.end) 排除）" \
  "$(curl -s --noproxy '*' "$BASE/api/meetings/$A/extend-limit" -H "Authorization: Bearer $U1" | j "d['data']['maxDelayMinutes']")" "30"
CONFLICT=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$A/extend" -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' -d '{"minutes":15}')
check "但复检拦下了 → 40001" "$(echo "$CONFLICT" | j "d['code']")" "40001"
check "40001 的 conflicts 指向那条越界会议" "$(echo "$CONFLICT" | j "str(d['data']['conflicts'][0]['meetingId'])")" "$EVIL"
check "A 的 end_time 没被改" "$(sql "SELECT DATE_FORMAT(end_time,'%H:%i') FROM smt_meeting.meeting WHERE id=$A;")" "11:30"
sql "DELETE FROM smt_meeting.meeting WHERE id=$EVIL;" >/dev/null
IDS=$(echo "$IDS" | sed "s/ $EVIL//")

echo
echo "=== 提前结束 ==="
check "组织人提前结束 → 成功" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$A/finish" -H "Authorization: Bearer $U1" | j "d['code']")" "0"
check "A 状态变 40" "$(sql "SELECT status FROM smt_meeting.meeting WHERE id=$A;")" "40"
check "重复结束 → 40002" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$A/finish" -H "Authorization: Bearer $U1" | j "d['code']")" "40002"
check "非组织人结束他人会议 → 40004" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/meetings/$C/finish" -H "Authorization: Bearer $U2" | j "d['code']")" "40004"
check "结束后时段释放（B 前的空档仍可预约）" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' \
     -d "$(printf '{"roomIds":[%s],"startTime":"%s","endTime":"%s"}' "$ROOM" "$A_E" "$B_S")" | j "d['data']['items'][0]['available']")" "True"

echo
echo "=== 清理（删除本次 SQL/接口造出的数据）==="
cleanup
LEFTOVER=0
for m in $A $B $C $EVIL; do
  LEFTOVER=$(( LEFTOVER + $(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id=$m;") ))
done
check "库里已无本次数据" "$LEFTOVER" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
