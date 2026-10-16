#!/usr/bin/env bash
# ============================================================================
# T4.11 验证：操作审计（@Log + 切面 + 独立 audit.log）
#
# 覆盖：
#   ① 成功写操作留痕：operator / action / **target = 被操作对象 id** / result=SUCCESS / cost=Nms
#   ② 失败的写操作也留痕（result=FAIL + error 摘要）——"谁的操作没成功"才是审计最该有的
#   ③ 五类动作都接上了：MEETING_CREATE / MEETING_CANCEL / APPROVAL_PASS / ROOM_UPDATE …
#   ④ **不记全文**：会议标题这类正文**不出现在**审计文件里（审计只记 id，不是数据备份）
#   ⑤ 审计行带 traceId，能与该请求的业务日志串起来
#
# ⚠️ "审计组件抛错不阻断业务"**不在本脚本验**：真实环境很难让"记一行日志"失败（磁盘满/权限错），
#    该实验做不了也不该做；它由单测 `LogAspectTest.auditFailureShouldNotBreakBusiness` 确定性覆盖
#    （把落盘抽成可注入的 AuditLogger 就是为了这个）。
#
# ⚠️ 本脚本**不删除审计文件里的行**——审计日志的意义就是只追加、不可删改（与业务日志的清理习惯相反）。
#
# 前置：基础设施 + 四个服务已启动（服务需含 T4.11 的新 jar，且按约定路径写日志）。
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
AUDIT="$ROOT/server/logs/audit.log"
ROOM=6
PASS=0; FAIL=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
# 审计行计数（用关键词过滤，只看本次动作）
audit_lines() { grep -c "AUDIT .*action=$1 .*target=$2 " "$AUDIT" 2>/dev/null | head -1; }
cleanup() {
  for m in ${IDS:-}; do
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_approval WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting WHERE id=$m;" > /dev/null
  done
  [ -n "${ROOM_OLD_NAME:-}" ] && sql "UPDATE smt_meeting.meeting_room SET name='$ROOM_OLD_NAME' WHERE id=$ROOM;" > /dev/null
}
trap cleanup EXIT

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
book() { # book <token> <title> <start> <end> [traceId]
  local trace=${5:-} body extra=""
  [ -n "$trace" ] && extra="X-Trace-Id: $trace"
  body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$ROOM" "$3" "$4")
  if [ -n "$trace" ]; then
    curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
      -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -H "X-Trace-Id: $trace" -d "$body"
  else
    curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
      -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body"
  fi
}

echo
echo "=== 前置：审计文件 ==="
check "logs/audit.log 存在" "$([ -f "$AUDIT" ] && echo yes || echo no)" "yes"

U1=$(login user1 user123); AP1=$(login approver1 user123); ADMIN=$(login admin admin123)
{ [ -n "$U1" ] && [ -n "$AP1" ] && [ -n "$ADMIN" ]; } || { echo "登录失败，终止"; exit 1; }
U1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user1';")
IDS=""

echo
echo "=== ① 预约成功 → 审计留痕（含对象 id 与耗时）==="
SLOT=$(python3 -c "
import datetime
d = datetime.date.today() + datetime.timedelta(days=16)
print(f'{d} 09:00:00|{d} 10:00:00')")
START="${SLOT%%|*}"; END="${SLOT##*|}"
TRACE="verifyaudit$(date +%H%M%S)"
RESP=$(book "$U1" "T411-审计留痕" "$START" "$END" "$TRACE")
MID=$(echo "$RESP" | j "d['data']['meetingId']")
[ -n "$MID" ] && [ "$MID" != "None" ] || { echo "❌ 预约失败：$RESP"; exit 1; }
IDS="$IDS $MID"
sleep 1
check "有 MEETING_CREATE 的审计行，target = 新建的会议 id" "$(audit_lines MEETING_CREATE "$MID")" "1"
check "操作人是当前登录用户" \
  "$(grep -c "AUDIT operator=$U1_ID action=MEETING_CREATE target=$MID result=SUCCESS" "$AUDIT" | head -1)" "1"
check "带耗时（cost=…ms）" "$(grep "action=MEETING_CREATE target=$MID" "$AUDIT" | tail -1 | grep -cE 'cost=[0-9]+ms')" "1"
check "审计行带本次请求的 traceId（可与业务日志串起来）" \
  "$(grep -c "traceId:$TRACE.*action=MEETING_CREATE target=$MID" "$AUDIT" | head -1)" "1"

echo
echo "=== ② 失败的写操作也留痕（result=FAIL + 原因）==="
RESP2=$(book "$U1" "T411-必冲突" "$START" "$END")
check "同 slot 再订一次被拒（40001）" "$(echo "$RESP2" | j "d['code']")" "40001"
sleep 1
FAIL_LINE=$(grep "action=MEETING_CREATE .*result=FAIL" "$AUDIT" | tail -1)
check "有 MEETING_CREATE 的 FAIL 审计行" "$([ -n "$FAIL_LINE" ] && echo yes || echo no)" "yes"
check "失败行带异常摘要（BusinessException + 消息）" "$(echo "$FAIL_LINE" | grep -c 'error=BusinessException:')" "1"

echo
echo "=== ③ 取消 / 审批通过 / 会议室变更 也都接上了 ==="
curl -s --noproxy '*' -o /dev/null -X POST "$BASE/api/meetings/$MID/cancel" -H "Authorization: Bearer $U1" \
  -H 'Content-Type: application/json' -d '{"reason":"T411 验证"}'
sleep 1
check "MEETING_CANCEL 有审计行且 target 正确" "$(audit_lines MEETING_CANCEL "$MID")" "1"

# 待审批房 → 审批通过
AP_SLOT=$(python3 -c "
import datetime
d = datetime.date.today() + datetime.timedelta(days=17)
print(f'{d} 11:00:00|{d} 12:00:00')")
AP_BODY=$(printf '{"title":"T411-待审审计","roomId":5,"startTime":"%s","endTime":"%s"}' "${AP_SLOT%%|*}" "${AP_SLOT##*|}")
AP_MID=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $U1" \
  -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$AP_BODY" | j "d['data']['meetingId']")
[ -n "$AP_MID" ] && [ "$AP_MID" != "None" ] || { echo "❌ 待审会议创建失败"; exit 1; }
IDS="$IDS $AP_MID"
curl -s --noproxy '*' -o /dev/null -X POST "$BASE/api/approvals/$AP_MID/pass" -H "Authorization: Bearer $AP1" \
  -H 'Content-Type: application/json' -d '{"comment":"同意"}'
sleep 1
check "APPROVAL_PASS 有审计行且 target = 会议 id" "$(audit_lines APPROVAL_PASS "$AP_MID")" "1"

# ⚠️ 与 MEETING_CREATE 同理：**要先记基线再比对**（审计文件是跨次累积的，
# 直接用 `grep -c` 会把历史运行的行也数进来——它就红过一次）
ROOM_UPD_BEFORE=$(grep -c "AUDIT .*action=ROOM_UPDATE target=$ROOM " "$AUDIT" | head -1)
ROOM_OLD_NAME=$(sql "SELECT name FROM smt_meeting.meeting_room WHERE id=$ROOM;")
ROOM_JSON=$(curl -s --noproxy '*' "$BASE/api/rooms/$ROOM" -H "Authorization: Bearer $ADMIN")
UPD=$(python3 -c "
import json,sys
d=json.loads(sys.argv[1])['data']
print(json.dumps({'name':'审计验证厅','groupId':d['groupId'],'capacity':d['capacity'],
 'location':d.get('location'),'images':d.get('images'),'deviceTags':d.get('deviceTags'),'status':d['status']}))
" "$ROOM_JSON")
curl -s --noproxy '*' -o /dev/null -X PUT "$BASE/api/rooms/$ROOM" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "$UPD"
sleep 1
check "ROOM_UPDATE 新增了 1 条审计行（与基线比）" \
  "$(( $(grep -c "AUDIT .*action=ROOM_UPDATE target=$ROOM " "$AUDIT" | head -1) - ROOM_UPD_BEFORE ))" "1"

echo
echo "=== ④ 不记全文：正文（标题/备注内容）不出现在审计文件里 ==="
check "审计文件里**没有**会议标题字样" "$(grep -c 'T411-审计留痕' "$AUDIT" | head -1)" "0"
check "审计文件里**没有**审批意见字样" "$(grep -c '同意' "$AUDIT" | head -1)" "0"

echo
echo "=== ⑤ 清理 ==="
cleanup
trap - EXIT
ID_LIST=$(echo $IDS | tr ' ' ',')
check "库里已无本次数据" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($ID_LIST);")" "0"
check "会议室名字已复原" "$(sql "SELECT name FROM smt_meeting.meeting_room WHERE id=$ROOM;")" "$ROOM_OLD_NAME"
# 用本次的 target（会议 id 唯一）精确定位，别用"历史上任何一次"凑数（评审 O9）
check "审计文件仍保留着本次记录（审计不随测试清理）" \
  "$([ "$(grep -c "action=MEETING_CREATE target=$MID " "$AUDIT")" -ge 1 ] && echo kept || echo lost)" "kept"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
