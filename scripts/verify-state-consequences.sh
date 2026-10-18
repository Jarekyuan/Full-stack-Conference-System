#!/usr/bin/env bash
# ============================================================================
# T6.2 状态后果验收（自清理）
#
# 会议室维护：
#   ① 维护中的房间不出现在 /rooms?status=0
#   ② 维护中创建预约 → 30002
#   ③ 改期到维护房 → 30002
#   ④ 待审批期间房间转维护 → 审批通过 30002，会议仍是 10
# 用户停用：
#   ⑤ 停用后登录 → 20004；/auth/me → 20004；refresh → 20003
#   ⑥ 停用用户不出现在选人搜索，但管理端列表仍可见
#   ⑦ 历史会议的参会人记录保留（停用不删历史）
#
# 前置：四服务已启动。脚本自己造临时会议室/用户/会议，失败也会清理。
# 用法：bash scripts/verify-state-consequences.sh
# ============================================================================
set -u

BASE="${BASE_URL:-${BASE:-http://127.0.0.1:8080}}"
STAMP=$(date +%s)
ROOM_NAME="T62临时厅-$STAMP"
USERNAME="t62_user_$STAMP"
ROOM_ID=""
USER_ID=""
ADMIN2_ID=""
MEETING_A=""
MEETING_B=""
MEETING_C=""
WEEK_A=""
WEEK_B=""
PASS=0
FAIL=0
MYSQL=(docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e)

check() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then
    PASS=$((PASS + 1)); printf '  ✅ %s%s\n' "$name" "${detail:+ → $detail}"
  else
    FAIL=$((FAIL + 1)); printf '  ❌ %s%s\n' "$name" "${detail:+ → $detail}"
  fi
}
sql() {
  "${MYSQL[@]}" "$1" 2>/dev/null | grep -v -e 'PAGER set to stdout' -e 'Using a password' | tr -d '\r'
}
redis_cli() { docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli "$@" 2>/dev/null; }

login() {
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}
token_of() { echo "$1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print((d.get("data") or {}).get("accessToken") or "")'; }
refresh_of() { echo "$1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print((d.get("data") or {}).get("refreshToken") or "")'; }
code_of() { echo "$1" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("code",-1))'; }
data_of() { echo "$1" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("data"))'; }

get_api() { curl -s "$BASE$1" -H "Authorization: Bearer $2"; }
post_api() { curl -s -X POST "$BASE$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -d "$3"; }
put_api() { curl -s -X PUT "$BASE$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -d "$3"; }
delete_api() { curl -s -X DELETE "$BASE$1" -H "Authorization: Bearer $2"; }

cleanup() {
  local ids=""
  [ -n "$MEETING_A" ] && ids="$MEETING_A"
  [ -n "$MEETING_B" ] && ids="${ids:+$ids,}$MEETING_B"
  [ -n "$MEETING_C" ] && ids="${ids:+$ids,}$MEETING_C"
  if [ -n "$ids" ]; then
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id IN ($ids);
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id IN ($ids);
         DELETE FROM smt_meeting.meeting_approval WHERE meeting_id IN ($ids);
         DELETE FROM smt_meeting.meeting_participant WHERE meeting_id IN ($ids);
         DELETE FROM smt_meeting.meeting WHERE id IN ($ids);" > /dev/null
  fi
  if [ -n "$ROOM_ID" ]; then
    # 先走 API 删除（会清会议室列表/详情缓存）；失败再 SQL 兜底
    if [ -n "${ADMIN_TOKEN:-}" ]; then
      delete_api "/api/rooms/$ROOM_ID" "$ADMIN_TOKEN" > /dev/null || true
    fi
    sql "DELETE FROM smt_meeting.room_strategy WHERE room_id=$ROOM_ID;
         DELETE FROM smt_meeting.meeting_room WHERE id=$ROOM_ID;" > /dev/null
  fi
  if [ -n "$USER_ID" ]; then
    sql "DELETE FROM smt_meeting.sys_user_role WHERE user_id=$USER_ID;
         DELETE FROM smt_meeting.sys_user WHERE id=$USER_ID;" > /dev/null
  fi
  if [ -n "$ADMIN2_ID" ]; then
    sql "DELETE FROM smt_meeting.sys_user_role WHERE user_id=$ADMIN2_ID;
         DELETE FROM smt_meeting.sys_user WHERE id=$ADMIN2_ID;" > /dev/null
  fi
  [ -n "$WEEK_A" ] && redis_cli DEL "cache:calendar:1:$WEEK_A" > /dev/null
  [ -n "$WEEK_B" ] && [ -n "$ROOM_ID" ] && redis_cli DEL "cache:calendar:$ROOM_ID:$WEEK_B" > /dev/null
  if [ -n "$ROOM_ID" ] || [ -n "$USER_ID" ]; then
    local room_left user_left
    room_left=$([ -n "$ROOM_ID" ] && sql "SELECT COUNT(*) FROM smt_meeting.meeting_room WHERE id=$ROOM_ID;" || echo 0)
    user_left=$([ -n "$USER_ID" ] && sql "SELECT COUNT(*) FROM smt_meeting.sys_user WHERE id=$USER_ID;" || echo 0)
    if [ "$room_left" = "0" ] && [ "$user_left" = "0" ]; then
      echo "  🧹 已清理临时会议室/用户/会议"
    else
      echo "  ⚠️ 清理后仍有残留：room=$room_left user=$user_left"
    fi
  fi
}
trap cleanup EXIT INT TERM

source "$(dirname "$0")/lib/ratelimit.sh"
ratelimit_disable_for_script

echo "=== T6.2 状态后果验收 ==="

eval "$(python3 - <<'PY'
from datetime import date, timedelta
t = date.today()
d1 = t + timedelta(days=7)
d2 = t + timedelta(days=8)
def monday(d): return d - timedelta(days=d.weekday())
print(f"D1={d1.isoformat()}")
print(f"D2={d2.isoformat()}")
print(f"W1={monday(d1).isoformat()}")
print(f"W2={monday(d2).isoformat()}")
PY
)"
WEEK_A="$W1"
WEEK_B="$W2"

ADMIN_LOGIN=$(login admin admin123)
ADMIN_TOKEN=$(token_of "$ADMIN_LOGIN")
U1_LOGIN=$(login user1 user123)
U1_TOKEN=$(token_of "$U1_LOGIN")
U2_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user2';")
APPROVER_LOGIN=$(login approver1 user123)
APPROVER_TOKEN=$(token_of "$APPROVER_LOGIN")
[ -n "$ADMIN_TOKEN" ] && [ -n "$U1_TOKEN" ] && [ -n "$APPROVER_TOKEN" ] \
  && check "账号登录成功" 1 \
  || { check "账号登录" 0 "token 为空"; exit 1; }

# ---------- 造临时会议室（默认策略 need_approval=0） ----------
ROOM_RESP=$(post_api "/api/rooms" "$ADMIN_TOKEN" \
  "{\"name\":\"$ROOM_NAME\",\"groupId\":1,\"capacity\":4,\"location\":\"T62\",\"status\":0}")
ROOM_ID=$(echo "$ROOM_RESP" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("data") or "")')
check "临时会议室创建成功" "$([ -n "$ROOM_ID" ] && echo 1 || echo 0)" "roomId=${ROOM_ID:-?} code=$(code_of "$ROOM_RESP")"
[ -n "$ROOM_ID" ] || exit 1

# 策略改成需审批（后面审批用例用）
STRATEGY_RESP=$(put_api "/api/rooms/$ROOM_ID/strategy" "$ADMIN_TOKEN" \
  '{"needApproval":1,"advanceDays":30,"maxDurationMinutes":240,"cancelDeadlineMinutes":0,"bufferMinutes":0,"allowRecurring":1}')
[ "$(code_of "$STRATEGY_RESP")" = "0" ] || echo "  ⚠️ 策略更新失败: $(code_of "$STRATEGY_RESP")"

# ---------- ① 置为维护 → 列表不可见、详情可见 ----------
ROOM_BODY="{\"name\":\"$ROOM_NAME\",\"groupId\":1,\"capacity\":4,\"location\":\"T62\",\"status\":1,\"images\":[],\"deviceTags\":[]}"
set_maintenance=$(put_api "/api/rooms/$ROOM_ID" "$ADMIN_TOKEN" "$ROOM_BODY")
check "会议室置为维护成功" "$([ "$(code_of "$set_maintenance")" = "0" ] && echo 1 || echo 0)" "code=$(code_of "$set_maintenance")"
LIST_OUT=$(get_api "/api/rooms?status=0" "$U1_TOKEN" | python3 -c "import sys,json;d=json.load(sys.stdin);ids=[r['id'] for r in (d.get('data') or [])];print(int($ROOM_ID in ids))")
check "维护中的会议室不出现在可预约列表" "$([ "$LIST_OUT" = "0" ] && echo 1 || echo 0)"
DETAIL_STATUS=$(get_api "/api/rooms/$ROOM_ID" "$U1_TOKEN" | python3 -c 'import sys,json;print((json.load(sys.stdin).get("data") or {}).get("status"))')
check "会议室详情仍可查且状态=1" "$([ "$DETAIL_STATUS" = "1" ] && echo 1 || echo 0)" "status=$DETAIL_STATUS"

# ---------- ② 维护中创建预约 → 30002 ----------
CREATE_MAINT=$(post_api "/api/meetings" "$U1_TOKEN" \
  "{\"title\":\"T62维护创建-$STAMP\",\"roomId\":$ROOM_ID,\"startTime\":\"$D1 10:00:00\",\"endTime\":\"$D1 11:00:00\"}")
check "维护中创建预约被拒（30002）" "$([ "$(code_of "$CREATE_MAINT")" = "30002" ] && echo 1 || echo 0)" "code=$(code_of "$CREATE_MAINT")"

# ---------- 造临时用户（USER 角色，研发部） ----------
USER_RESP=$(post_api "/api/users" "$ADMIN_TOKEN" \
  "{\"username\":\"$USERNAME\",\"password\":\"t62_pass123\",\"realName\":\"T62停用用户\",\"roleIds\":[1],\"deptId\":2}")
USER_ID=$(echo "$USER_RESP" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("data") or "")')
check "临时用户创建成功" "$([ -n "$USER_ID" ] && echo 1 || echo 0)" "userId=${USER_ID:-?} code=$(code_of "$USER_RESP")"
[ -n "$USER_ID" ] || exit 1

TEMP_LOGIN=$(login "$USERNAME" t62_pass123)
TEMP_TOKEN=$(token_of "$TEMP_LOGIN")
TEMP_REFRESH=$(refresh_of "$TEMP_LOGIN")
check "停用前可以登录" "$([ -n "$TEMP_TOKEN" ] && echo 1 || echo 0)"

# ---------- 造会议 A（room1，带临时用户为参会人） ----------
SLOT_HOUR=""
for h in 10 11 12 13 14 15 16 17; do
  AVAIL=$(post_api "/api/rooms/availability" "$U1_TOKEN" \
    "{\"roomIds\":[1],\"startTime\":\"$D1 $(printf '%02d' "$h"):00:00\",\"endTime\":\"$D1 $(printf '%02d' $((h + 1))):00:00\"}" | \
    python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["items"][0]["available"])')
  if [ "$AVAIL" = "True" ]; then SLOT_HOUR=$h; break; fi
done
check "找到 room1 空闲整点" "$([ -n "$SLOT_HOUR" ] && echo 1 || echo 0)" "$D1 ${SLOT_HOUR:-?}:00"
[ -n "$SLOT_HOUR" ] || exit 1

CREATE_A=$(post_api "/api/meetings" "$U1_TOKEN" \
  "{\"title\":\"T62会议A-$STAMP\",\"roomId\":1,\"startTime\":\"$D1 $(printf '%02d' "$SLOT_HOUR"):00:00\",\"endTime\":\"$D1 $(printf '%02d' $((SLOT_HOUR + 1))):00:00\",\"participants\":{\"userIds\":[$USER_ID,$U2_ID]}}")
MEETING_A=$(echo "$CREATE_A" | python3 -c 'import sys,json;d=json.load(sys.stdin);print((d.get("data") or {}).get("meetingId") or "")')
check "会议 A 创建成功（含临时参会人）" "$([ -n "$MEETING_A" ] && echo 1 || echo 0)" "meetingId=${MEETING_A:-?} code=$(code_of "$CREATE_A")"
[ -n "$MEETING_A" ] || exit 1

# ---------- ③ 改期到维护房 → 30002 ----------
DETAIL_A=$(get_api "/api/meetings/$MEETING_A" "$U1_TOKEN")
VERSION_A=$(echo "$DETAIL_A" | python3 -c 'import sys,json;print((json.load(sys.stdin).get("data") or {}).get("version"))')
UPDATE_MAINT=$(put_api "/api/meetings/$MEETING_A" "$U1_TOKEN" \
  "{\"title\":\"T62会议A-$STAMP\",\"roomId\":$ROOM_ID,\"startTime\":\"$D1 $(printf '%02d' "$SLOT_HOUR"):00:00\",\"endTime\":\"$D1 $(printf '%02d' $((SLOT_HOUR + 1))):00:00\",\"version\":$VERSION_A}")
check "改期到维护房被拒（30002）" "$([ "$(code_of "$UPDATE_MAINT")" = "30002" ] && echo 1 || echo 0)" "code=$(code_of "$UPDATE_MAINT")"

# ---------- ④ 待审批会议在维护后被拒批 ----------
# 先把房间恢复可用，创建一场待审批会议（策略 need_approval=1），再置维护
put_api "/api/rooms/$ROOM_ID" "$ADMIN_TOKEN" \
  "{\"name\":\"$ROOM_NAME\",\"groupId\":1,\"capacity\":4,\"location\":\"T62\",\"status\":0,\"images\":[],\"deviceTags\":[]}" > /dev/null
CREATE_B=$(post_api "/api/meetings" "$U1_TOKEN" \
  "{\"title\":\"T62会议B-$STAMP\",\"roomId\":$ROOM_ID,\"startTime\":\"$D2 10:00:00\",\"endTime\":\"$D2 11:00:00\"}")
MEETING_B=$(echo "$CREATE_B" | python3 -c 'import sys,json;d=json.load(sys.stdin);print((d.get("data") or {}).get("meetingId") or "")')
B_STATUS=$(echo "$CREATE_B" | python3 -c 'import sys,json;print((json.load(sys.stdin).get("data") or {}).get("status"))')
check "维护前创建待审批会议成功" "$([ -n "$MEETING_B" ] && [ "$B_STATUS" = "10" ] && echo 1 || echo 0)" "meetingId=${MEETING_B:-?} status=${B_STATUS:-?}"
[ -n "$MEETING_B" ] || exit 1

put_api "/api/rooms/$ROOM_ID" "$ADMIN_TOKEN" "$ROOM_BODY" > /dev/null
APPROVE_RESP=$(post_api "/api/approvals/$MEETING_B/pass" "$APPROVER_TOKEN" '{"comment":"T62 审批"}')
check "维护中审批通过被拒（30002）" "$([ "$(code_of "$APPROVE_RESP")" = "30002" ] && echo 1 || echo 0)" "code=$(code_of "$APPROVE_RESP")"
B_AFTER=$(sql "SELECT status FROM smt_meeting.meeting WHERE id=$MEETING_B;")
check "审批失败后会议仍是待审批(10)" "$([ "$B_AFTER" = "10" ] && echo 1 || echo 0)" "status=$B_AFTER"

# ---------- ⑤ 停用用户 → 登录/me/refresh 全部失效 ----------
# R14.12：用唯一 username 搜，避免 deptId=2 + LIMIT 50 下“停用用户不在前 50 条”造成的假通过
SEARCH_BEFORE=$(get_api "/api/users/search?keyword=$USERNAME" "$U1_TOKEN" | \
  python3 -c "import sys,json;items=json.load(sys.stdin).get('data') or [];print(int(any(u['id']==$USER_ID for u in items)))")
check "停用前选人搜索能搜到该用户（前置）" "$([ "$SEARCH_BEFORE" = "1" ] && echo 1 || echo 0)"
DISABLE_RESP=$(curl -s -X PUT "$BASE/api/users/$USER_ID/status?status=0" -H "Authorization: Bearer $ADMIN_TOKEN")
check "停用用户成功" "$([ "$(code_of "$DISABLE_RESP")" = "0" ] && echo 1 || echo 0)" "code=$(code_of "$DISABLE_RESP")"
LOGIN_AFTER=$(login "$USERNAME" t62_pass123)
check "停用后登录被拒（20004）" "$([ "$(code_of "$LOGIN_AFTER")" = "20004" ] && echo 1 || echo 0)" "code=$(code_of "$LOGIN_AFTER")"
ME_AFTER=$(get_api "/api/auth/me" "$TEMP_TOKEN")
check "停用后 /auth/me 被拒（20004）" "$([ "$(code_of "$ME_AFTER")" = "20004" ] && echo 1 || echo 0)" "code=$(code_of "$ME_AFTER")"
# 刷新接口是网关白名单（公开），不带 Authorization
REFRESH_AFTER=$(curl -s -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$TEMP_REFRESH\"}")
check "停用后 refresh 被拒（20003）" "$([ "$(code_of "$REFRESH_AFTER")" = "20003" ] && echo 1 || echo 0)" "code=$(code_of "$REFRESH_AFTER")"
# N15：refresh 接口本身也会因状态检查返回 20003；直接看 Redis 用户索引是否已被删，才证明“主动撤销”
REFRESH_LEFT=$(redis_cli EXISTS "auth:refresh:$USER_ID" | tr -d '\r')
check "停用后 refreshToken 用户索引已撤销" \
  "$([ "$REFRESH_LEFT" = "0" ] && echo 1 || echo 0)" "exists=$REFRESH_LEFT"

# 停用参会人保留历史，但**不再收通知**（RecipientMapper.selectParticipantIds 只取启用账号）。
# 会议 A 另有一个启用参会人 user2：更新后 user2 应收到 MEETING_UPDATED，停用用户不应收到。
DETAIL_AFTER_DISABLE=$(get_api "/api/meetings/$MEETING_A" "$U1_TOKEN")
VERSION_AFTER=$(echo "$DETAIL_AFTER_DISABLE" | python3 -c 'import sys,json;print((json.load(sys.stdin).get("data") or {}).get("version"))')
UPD_RESP=$(put_api "/api/meetings/$MEETING_A" "$U1_TOKEN" \
  "{\"title\":\"T62会议A-$STAMP-更新\",\"roomId\":1,\"startTime\":\"$D1 $(printf '%02d' "$SLOT_HOUR"):00:00\",\"endTime\":\"$D1 $(printf '%02d' $((SLOT_HOUR + 1))):00:00\",\"version\":$VERSION_AFTER}")
check "停用后更新会议成功（历史参会人保留）" "$([ "$(code_of "$UPD_RESP")" = "0" ] && echo 1 || echo 0)" "code=$(code_of "$UPD_RESP")"
UPD_U2=0
for _ in $(seq 1 15); do
  UPD_U2=$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MEETING_A AND user_id=$U2_ID AND type='MEETING_UPDATED';")
  [ "$UPD_U2" = "1" ] && break
  sleep 1
done
check "启用的参会人收到 MEETING_UPDATED" "$([ "$UPD_U2" = "1" ] && echo 1 || echo 0)" "count=$UPD_U2"
UPD_DISABLED=$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$MEETING_A AND user_id=$USER_ID AND type='MEETING_UPDATED';")
check "停用参会人不再收 MEETING_UPDATED" "$([ "$UPD_DISABLED" = "0" ] && echo 1 || echo 0)" "count=$UPD_DISABLED"

# ---------- ⑤.5 停用/不存在的用户不能作为参会人（评审 R6） ----------
DISABLED_HOUR=""
for h in 14 15 16 11 9; do
  AVAIL=$(post_api "/api/rooms/availability" "$U1_TOKEN" \
    "{\"roomIds\":[1],\"startTime\":\"$D2 $(printf '%02d' "$h"):00:00\",\"endTime\":\"$D2 $(printf '%02d' $((h + 1))):00:00\"}" | \
    python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["items"][0]["available"])')
  if [ "$AVAIL" = "True" ]; then DISABLED_HOUR=$h; break; fi
done
check "找到用于参会人校验的空闲整点" "$([ -n "$DISABLED_HOUR" ] && echo 1 || echo 0)" "$D2 ${DISABLED_HOUR:-?}:00"
if [ -n "$DISABLED_HOUR" ]; then
  DISABLED_TITLE="T62禁用参会人-$STAMP"
  DISABLED_RESP=$(post_api "/api/meetings" "$U1_TOKEN" \
    "{\"title\":\"$DISABLED_TITLE\",\"roomId\":1,\"startTime\":\"$D2 $(printf '%02d' "$DISABLED_HOUR"):00:00\",\"endTime\":\"$D2 $(printf '%02d' $((DISABLED_HOUR + 1))):00:00\",\"participants\":{\"userIds\":[$USER_ID]}}")
  check "停用用户作为参会人被拒（10001）" "$([ "$(code_of "$DISABLED_RESP")" = "10001" ] && echo 1 || echo 0)" "code=$(code_of "$DISABLED_RESP")"
  DISABLED_LEFT=$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='$DISABLED_TITLE';")
  check "被拒后没有落库（停用参会人）" "$([ "$DISABLED_LEFT" = "0" ] && echo 1 || echo 0)" "count=$DISABLED_LEFT"
  # 防御性清理：若旧实现真的落库了，把 id 交给 cleanup，避免留下测试数据
  MEETING_C=$(sql "SELECT id FROM smt_meeting.meeting WHERE title='$DISABLED_TITLE' LIMIT 1;")

  GHOST_TITLE="T62幽灵参会人-$STAMP"
  GHOST_RESP=$(post_api "/api/meetings" "$U1_TOKEN" \
    "{\"title\":\"$GHOST_TITLE\",\"roomId\":1,\"startTime\":\"$D2 $(printf '%02d' "$DISABLED_HOUR"):00:00\",\"endTime\":\"$D2 $(printf '%02d' $((DISABLED_HOUR + 1))):00:00\",\"participants\":{\"userIds\":[999999]}}")
  check "不存在的用户作为参会人被拒（10001）" "$([ "$(code_of "$GHOST_RESP")" = "10001" ] && echo 1 || echo 0)" "code=$(code_of "$GHOST_RESP")"
  GHOST_LEFT=$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='$GHOST_TITLE';")
  check "被拒后没有落库（幽灵参会人）" "$([ "$GHOST_LEFT" = "0" ] && echo 1 || echo 0)" "count=$GHOST_LEFT"

  # DTO 容器约束：null / 负数 / 超过 100 个，均应在 Service 之前被 10001 拦截
  NULL_RESP=$(post_api "/api/meetings" "$U1_TOKEN" \
    "{\"title\":\"T62空参会人-$STAMP\",\"roomId\":1,\"startTime\":\"$D2 $(printf '%02d' "$DISABLED_HOUR"):00:00\",\"endTime\":\"$D2 $(printf '%02d' $((DISABLED_HOUR + 1))):00:00\",\"participants\":{\"userIds\":[null]}}")
  check "null 参会人 → 10001" "$([ "$(code_of "$NULL_RESP")" = "10001" ] && echo 1 || echo 0)" "code=$(code_of "$NULL_RESP")"
  NEG_RESP=$(post_api "/api/meetings" "$U1_TOKEN" \
    "{\"title\":\"T62负参会人-$STAMP\",\"roomId\":1,\"startTime\":\"$D2 $(printf '%02d' "$DISABLED_HOUR"):00:00\",\"endTime\":\"$D2 $(printf '%02d' $((DISABLED_HOUR + 1))):00:00\",\"participants\":{\"userIds\":[-1]}}")
  check "负数参会人 → 10001" "$([ "$(code_of "$NEG_RESP")" = "10001" ] && echo 1 || echo 0)" "code=$(code_of "$NEG_RESP")"
  MANY_IDS=$(python3 -c "print(','.join(['4']*101))")
  MANY_RESP=$(post_api "/api/meetings" "$U1_TOKEN" \
    "{\"title\":\"T62超量参会人-$STAMP\",\"roomId\":1,\"startTime\":\"$D2 $(printf '%02d' "$DISABLED_HOUR"):00:00\",\"endTime\":\"$D2 $(printf '%02d' $((DISABLED_HOUR + 1))):00:00\",\"participants\":{\"userIds\":[$MANY_IDS]}}")
  check "参会人超过 100 → 10001" "$([ "$(code_of "$MANY_RESP")" = "10001" ] && echo 1 || echo 0)" "code=$(code_of "$MANY_RESP")"
fi

# ---------- ⑤.6 最后一名启用 ADMIN 保护（评审 R7） ----------
ADMIN2_USERNAME="t62_admin_$STAMP"
ADMIN2_RESP=$(post_api "/api/users" "$ADMIN_TOKEN" \
  "{\"username\":\"$ADMIN2_USERNAME\",\"password\":\"t62_pass123\",\"realName\":\"T62临时管理员\",\"roleIds\":[1,3],\"deptId\":2}")
ADMIN2_ID=$(echo "$ADMIN2_RESP" | python3 -c 'import sys,json;print((json.load(sys.stdin).get("data") or ""))')
check "临时 ADMIN 创建成功" "$([ -n "$ADMIN2_ID" ] && echo 1 || echo 0)" "userId=${ADMIN2_ID:-?} code=$(code_of "$ADMIN2_RESP")"
if [ -n "$ADMIN2_ID" ]; then
  ADMIN2_LOGIN=$(login "$ADMIN2_USERNAME" t62_pass123)
  ADMIN2_TOKEN=$(token_of "$ADMIN2_LOGIN")
  check "临时 ADMIN 登录成功" "$([ -n "$ADMIN2_TOKEN" ] && echo 1 || echo 0)"
  # 先把临时管理员降权（A 仍是启用 ADMIN，所以允许），再用它的旧 token 去停用 A
  DEMOTE2=$(put_api "/api/users/$ADMIN2_ID" "$ADMIN_TOKEN" \
    "{\"realName\":\"T62临时管理员\",\"roleIds\":[1],\"deptId\":2}")
  check "管理员 A 把临时 ADMIN 降权成功" "$([ "$(code_of "$DEMOTE2")" = "0" ] && echo 1 || echo 0)" "code=$(code_of "$DEMOTE2")"
  LAST_ADMIN_RESP=$(put_api "/api/users/1/status?status=0" "$ADMIN2_TOKEN" '{}')
  check "降权后的旧 token 不能停用最后一名 ADMIN（20007）" \
    "$([ "$(code_of "$LAST_ADMIN_RESP")" = "20007" ] && echo 1 || echo 0)" "code=$(code_of "$LAST_ADMIN_RESP")"
  check "管理员 A 仍是启用状态" "$(sql "SELECT status FROM smt_meeting.sys_user WHERE id=1;")" "1"
fi

# ---------- ⑥ 选人排除停用用户；管理端仍可见 ----------
SEARCH_HAS=$(get_api "/api/users/search?keyword=$USERNAME" "$U1_TOKEN" | \
  python3 -c "import sys,json;items=json.load(sys.stdin).get('data') or [];print(int(any(u['id']==$USER_ID for u in items)))")
check "选人搜索排除停用用户（唯一关键字，不受 LIMIT 50 影响）" "$([ "$SEARCH_HAS" = "0" ] && echo 1 || echo 0)"
ADMIN_LIST_HAS=$(get_api "/api/users?deptId=2&page=1&size=50" "$ADMIN_TOKEN" | \
  python3 -c "import sys,json;items=json.load(sys.stdin).get('data',{}).get('list') or [];print(int(any(u['id']==$USER_ID for u in items)))")
check "管理端用户列表仍显示停用用户" "$([ "$ADMIN_LIST_HAS" = "1" ] && echo 1 || echo 0)"

# ---------- ⑦ 历史会议参会人记录保留 ----------
DETAIL_A_AFTER=$(get_api "/api/meetings/$MEETING_A" "$U1_TOKEN" | \
  python3 -c "import sys,json;ps=(json.load(sys.stdin).get('data') or {}).get('participants') or [];print(int(any(p['userId']==$USER_ID for p in ps)))")
check "历史会议仍保留停用参会人" "$([ "$DETAIL_A_AFTER" = "1" ] && echo 1 || echo 0)"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
