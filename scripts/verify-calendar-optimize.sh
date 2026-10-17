#!/usr/bin/env bash
# ============================================================================
# T6.1 日历体验优化的后端验收（自清理）
#
# 覆盖：
#   ① 过去时间创建 → 30003（后端兜底；前端置灰由 e2e 验）
#   ② 日历包含已结束(40)，取消/拒绝不在查询范围
#   ③ “邀请我的”按请求用户装配：user1=true / user2(组织人)=false / user3=false
#   ④ **缓存隔离**：先 user1 后 user3、先 user3 后 user1 都不能串（两个方向都验）
#   ⑤ /users/search?deptId= 登录可访问、只返回直属成员、无 email、最多 50 条
#
# 前置：四服务已启动。脚本自己削数据（DB + Redis 日历键），失败也会清理。
# 用法：bash scripts/verify-calendar-optimize.sh
# 退出码：0 = 全部断言通过。
# ============================================================================
set -u

BASE="${BASE:-http://127.0.0.1:8080}"
ROOM_ID=1
MYSQL=(docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e)
PASS=0
FAIL=0

# 清理用的变量先初始化（set -u：trap 在早期失败时也会执行）
MID=""
HISTORY_ID=""
FUTURE_WEEK=""
HISTORY_WEEK=""

check() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then
    PASS=$((PASS + 1))
    printf '  ✅ %s%s\n' "$name" "${detail:+ → $detail}"
  else
    FAIL=$((FAIL + 1))
    printf '  ❌ %s%s\n' "$name" "${detail:+ → $detail}"
  fi
}

sql() {
  "${MYSQL[@]}" "$1" 2>/dev/null \
    | grep -v -e 'PAGER set to stdout' -e 'Using a password' \
    | tr -d '\r'
}

redis_cli() {
  # REDISCLI_AUTH 避免把口令放进命令行参数/输出
  docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli "$@" 2>/dev/null
}

login() {
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["accessToken"])'
}

get_api() {
  curl -s "$BASE$1" -H "Authorization: Bearer $2"
}

evict_calendar() { # $1 roomId $2 weekStart
  redis_cli DEL "cache:calendar:$1:$2" > /dev/null
}

cleanup() {
  local ids=""
  [ -n "$MID" ] && ids="$MID"
  [ -n "$HISTORY_ID" ] && ids="${ids:+$ids,}$HISTORY_ID"
  if [ -n "$ids" ]; then
    # 先删通知/日志/参会人，再删会议（无外键，顺序只是习惯）
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id IN ($ids);
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id IN ($ids);
         DELETE FROM smt_meeting.meeting_approval WHERE meeting_id IN ($ids);
         DELETE FROM smt_meeting.meeting_participant WHERE meeting_id IN ($ids);
         DELETE FROM smt_meeting.meeting WHERE id IN ($ids);" > /dev/null
  fi
  [ -n "$FUTURE_WEEK" ] && evict_calendar "$ROOM_ID" "$FUTURE_WEEK"
  [ -n "$HISTORY_WEEK" ] && evict_calendar "$ROOM_ID" "$HISTORY_WEEK"
  # MQ 消费是异步的：最后再清一次通知，避免脚本退出后冒出一条测试通知
  if [ -n "$MID" ]; then
    sleep 1
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$MID;" > /dev/null
  fi
  if [ -n "$ids" ]; then
    local left
    left=$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($ids);")
    [ "$left" = "0" ] && echo "  🧹 已清理本次会议：$ids" || echo "  ⚠️ 清理后仍有残留：$ids"
  fi
}
trap cleanup EXIT INT TERM

source "$(dirname "$0")/lib/ratelimit.sh"
ratelimit_disable_for_script

echo "=== T6.1 日历体验优化验收 ==="

# ---------- 准备日期 ----------
eval "$(python3 - <<'PY'
from datetime import date, timedelta
today = date.today()
future = today + timedelta(days=7)
history = today - timedelta(days=10)
def monday(d):
    return d - timedelta(days=d.weekday())
print(f"FUTURE_DATE={future.isoformat()}")
print(f"FUTURE_WEEK={monday(future).isoformat()}")
print(f"HISTORY_DATE={history.isoformat()}")
print(f"HISTORY_WEEK={monday(history).isoformat()}")
print(f"PAST_DATE={(today - timedelta(days=1)).isoformat()}")
PY
)"

U1=$(login user1 user123)
U2=$(login user2 user123)
U3=$(login user3 user123)
ADMIN=$(login admin admin123)
[ -n "$U1" ] && [ -n "$U2" ] && [ -n "$U3" ] && [ -n "$ADMIN" ] \
  && check "四个账号登录成功" 1 \
  || { check "账号登录" 0 "有 token 为空，先查 auth-service"; exit 1; }

# ---------- ① 过去时间创建 → 30003 ----------
PAST_RESP=$(curl -s -X POST "$BASE/api/meetings" \
  -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' \
  -H "X-Idempotency-Token: $(python3 -c 'import uuid;print(uuid.uuid4())')" \
  -d "{\"title\":\"T61过去时间-$(date +%s)\",\"roomId\":$ROOM_ID,\"startTime\":\"${PAST_DATE} 10:00:00\",\"endTime\":\"${PAST_DATE} 11:00:00\"}")
PAST_CODE=$(echo "$PAST_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["code"])')
check "过去时间创建被后端拒绝（30003）" "$([ "$PAST_CODE" = "30003" ] && echo 1 || echo 0)" "code=$PAST_CODE"

# ---------- ② 日历包含已结束(40) ----------
# 直接插一条上周的 40（API 不允许创建历史会议，只能造数据）
HISTORY_TITLE="T61历史-$(date +%s)"
HISTORY_ID=$(sql "INSERT INTO smt_meeting.meeting
  (title, room_id, organiser_id, start_time, end_time, status)
  VALUES ('$HISTORY_TITLE', $ROOM_ID, 4, '${HISTORY_DATE} 10:00:00', '${HISTORY_DATE} 11:00:00', 40);
  SELECT LAST_INSERT_ID();")
check "插入历史(40)会议" "$([ -n "$HISTORY_ID" ] && echo 1 || echo 0)" "meetingId=${HISTORY_ID:-?}"
if [ -n "$HISTORY_ID" ]; then
  evict_calendar "$ROOM_ID" "$HISTORY_WEEK"
  HISTORY_STATUS=$(get_api "/api/rooms/$ROOM_ID/calendar?week=$HISTORY_WEEK" "$ADMIN" | \
    python3 -c "import sys,json;d=json.load(sys.stdin);m=[x for day in d['data']['days'] for x in day['meetings'] if x['meetingId']==$HISTORY_ID];print(m[0]['status'] if m else 'missing')")
  check "日历包含已结束(40)的会议" "$([ "$HISTORY_STATUS" = "40" ] && echo 1 || echo 0)" "status=$HISTORY_STATUS"
fi

# ---------- ③ 创建一场“邀请 user1”的未来会议（user2 组织） ----------
SLOT_HOUR=""
for h in 10 11 12 13 14 15 16 17; do
  START="${FUTURE_DATE} $(printf '%02d' "$h"):00:00"
  END="${FUTURE_DATE} $(printf '%02d' $((h + 1))):00:00"
  AVAILABLE=$(curl -s -X POST "$BASE/api/rooms/availability" \
    -H "Authorization: Bearer $U2" -H 'Content-Type: application/json' \
    -d "{\"roomIds\":[$ROOM_ID],\"startTime\":\"$START\",\"endTime\":\"$END\"}" | \
    python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["items"][0]["available"])')
  if [ "$AVAILABLE" = "True" ]; then SLOT_HOUR=$h; break; fi
done
check "找到未来空闲整点" "$([ -n "$SLOT_HOUR" ] && echo 1 || echo 0)" "${FUTURE_DATE} ${SLOT_HOUR:-?}:00"
[ -n "$SLOT_HOUR" ] || exit 1

CREATE_TITLE="T61邀请-$(date +%s)"
CREATE_RESP=$(curl -s -X POST "$BASE/api/meetings" \
  -H "Authorization: Bearer $U2" -H 'Content-Type: application/json' \
  -H "X-Idempotency-Token: $(python3 -c 'import uuid;print(uuid.uuid4())')" \
  -d "{\"title\":\"$CREATE_TITLE\",\"roomId\":$ROOM_ID,\"startTime\":\"${FUTURE_DATE} $(printf '%02d' "$SLOT_HOUR"):00:00\",\"endTime\":\"${FUTURE_DATE} $(printf '%02d' $((SLOT_HOUR + 1))):00:00\",\"participants\":{\"userIds\":[4]}}")
MID=$(echo "$CREATE_RESP" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["data"]["meetingId"] if d["code"]==0 else "")')
check "创建带参会人的会议成功" "$([ -n "$MID" ] && echo 1 || echo 0)" "meetingId=${MID:-?}"
[ -n "$MID" ] || exit 1

invited_flag() { # $1 token
  get_api "/api/rooms/$ROOM_ID/calendar?week=$FUTURE_WEEK" "$1" | \
    python3 -c "import sys,json;d=json.load(sys.stdin);m=[x for day in d['data']['days'] for x in day['meetings'] if x['meetingId']==$MID];print(m[0].get('invited') if m else 'missing')"
}

# ---------- ④ 邀请关系 + 缓存隔离（两个顺序都验） ----------
evict_calendar "$ROOM_ID" "$FUTURE_WEEK"
F1=$(invited_flag "$U1")
F3_AFTER_HIT=$(invited_flag "$U3")   # 缓存已由 user1 的请求建立
check "user1（参会人）看到 invited=true" "$([ "$F1" = "True" ] && echo 1 || echo 0)" "invited=$F1"
check "缓存命中后 user3 仍是 invited=false（不串用户）" "$([ "$F3_AFTER_HIT" = "False" ] && echo 1 || echo 0)" "invited=$F3_AFTER_HIT"

evict_calendar "$ROOM_ID" "$FUTURE_WEEK"
F3=$(invited_flag "$U3")
F1_AFTER_HIT=$(invited_flag "$U1")   # 缓存由 user3 的请求建立
check "user3（无关）看到 invited=false" "$([ "$F3" = "False" ] && echo 1 || echo 0)" "invited=$F3"
check "缓存命中后 user1 仍是 invited=true（反向也不串）" "$([ "$F1_AFTER_HIT" = "True" ] && echo 1 || echo 0)" "invited=$F1_AFTER_HIT"
F2=$(invited_flag "$U2")
check "user2（组织人）invited=false" "$([ "$F2" = "False" ] && echo 1 || echo 0)" "invited=$F2"

# ---------- ④.5 批量接口（T6.3） ----------
# 一次取多个会议室（故意传重复 id，验证 Service 去重）；room1 里应能看到 MID 的 invited 标记
BATCH=$(get_api "/api/rooms/calendar?week=$FUTURE_WEEK&roomIds=1,6,1" "$U1")
read -r B_CODE B_ITEMS B_FAILED B_DAYS_OK B_INVITED B_WEEK <<< "$(echo "$BATCH" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data') or {}
items = data.get('items') or []
room1 = next((x for x in items if x['roomId'] == 1), None) or {}
meeting = next((m for day in (room1.get('days') or []) for m in day.get('meetings', []) if m['meetingId'] == $MID), None) or {}
days_ok = int(all(len(x.get('days') or []) == 7 for x in items))
print(d.get('code'), len(items), len(data.get('failedRoomIds') or []), days_ok,
      meeting.get('invited'), data.get('weekStart'))
")"
check "批量日历 code=0" "$([ "$B_CODE" = "0" ] && echo 1 || echo 0)" "code=$B_CODE"
check "批量日历去重后返回 2 个房间" "$([ "$B_ITEMS" = "2" ] && echo 1 || echo 0)" "items=$B_ITEMS"
check "批量日历无失败房间" "$([ "$B_FAILED" = "0" ] && echo 1 || echo 0)" "failed=$B_FAILED"
check "批量日历每个房间补齐 7 天" "$([ "$B_DAYS_OK" = "1" ] && echo 1 || echo 0)"
check "批量日历 invited 装饰正确（user1=true）" "$([ "$B_INVITED" = "True" ] && echo 1 || echo 0)" "invited=$B_INVITED"
check "批量日历 weekStart 正确" "$([ "$B_WEEK" = "$FUTURE_WEEK" ] && echo 1 || echo 0)" "weekStart=$B_WEEK"

# ---------- ⑤ 按部门选人接口 ----------
SEARCH_OUT=$(get_api "/api/users/search?deptId=2" "$U1" | python3 -c '
import sys, json
d = json.load(sys.stdin)
items = d.get("data") or []
print(d.get("code"), len(items), int(any(u.get("id") == 4 for u in items)), int(any("email" in u for u in items)))
')
read -r S_CODE S_LEN S_HAS_U1 S_HAS_EMAIL <<< "$SEARCH_OUT"
check "普通用户可按部门列人（code=0）" "$([ "$S_CODE" = "0" ] && echo 1 || echo 0)" "code=$S_CODE"
check "研发部列表包含 user1" "$([ "$S_HAS_U1" = "1" ] && echo 1 || echo 0)"
check "按部门结果不含 email" "$([ "$S_HAS_EMAIL" = "0" ] && echo 1 || echo 0)"
check "结果上限 ≤ 50" "$([ "$S_LEN" -le 50 ] && echo 1 || echo 0)" "len=$S_LEN"

EMPTY_LEN=$(get_api "/api/users/search" "$U1" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("data") or []))')
check "keyword 与 deptId 都为空 → 空数组" "$([ "$EMPTY_LEN" = "0" ] && echo 1 || echo 0)" "len=$EMPTY_LEN"

CROSS_LEN=$(get_api "/api/users/search?keyword=%E7%8E%8B&deptId=2" "$U1" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("data") or []))')
check "关键字+部门取交集（王五不在研发部 → 0 条）" "$([ "$CROSS_LEN" = "0" ] && echo 1 || echo 0)" "len=$CROSS_LEN"

ZHANG_LEN=$(get_api "/api/users/search?keyword=%E5%BC%A0&deptId=2" "$U1" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("data") or []))')
check "关键字+部门命中（张* 在研发部）" "$([ "$ZHANG_LEN" -ge 1 ] && echo 1 || echo 0)" "len=$ZHANG_LEN"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
