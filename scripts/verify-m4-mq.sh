#!/usr/bin/env bash
# ============================================================================
# T4.1 验证：领域事件 → MQ（创建/修改/取消/通过/拒绝 五类事件都要真的发出去）
#
# 用 RabbitMQ 管理台 API 读队列，而不是 `rabbitmqctl`：
#   - 管理台 API 的**统计数字有 ~5 秒缓存**（`/api/queues` 的 messages 字段），刚发完就查会显示 0，
#     看起来像"消息没发出去"——实测踩过一次。所以本脚本用 `/api/queues/{q}/get` 直接取（实时、且能把消息取走）。
#   - basic auth 用 guest/guest：容器里 guest 只允许从 localhost 登录，正好与本机开发一致。
#
# 前置：三服务 + 基础设施（含 RabbitMQ 管理台 15672）已启动。
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
AMQP_API="http://127.0.0.1:15672/api"
AUTH="guest:guest"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
ROOM_OPEN=6     # 云杉厅：免审批
ROOM_APPROVAL=5 # 红枫厅：需审批
PASS=0; FAIL=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" \
    | j "d['data']['accessToken']"
}
# 取走并清空一个队列里的消息，回显条数（用 get 而不是看统计：统计有 5 秒缓存）
drain() {
  local q=$1 body
  body=$(curl -s --noproxy '*' -u "$AUTH" -X POST "$AMQP_API/queues/%2F/$q/get" \
    -H 'Content-Type: application/json' -d '{"count":100,"ackmode":"ack_requeue_false","encoding":"auto"}')
  echo "$body" | python3 -c "
import sys, json, os
msgs = json.load(sys.stdin)
print(len(msgs))
open('/tmp/m4-last-drain.json','w').write(json.dumps(msgs, ensure_ascii=False))
" 2>/dev/null || echo 0
}
# 从上次 drain 的消息里读字段
drained() { python3 -c "
import json
msgs = json.load(open('/tmp/m4-last-drain.json'))
print($1) if msgs else print('')
" 2>/dev/null; }

book() { # book <token> <title> <roomId> <start> <end> → meetingId
  local body resp code mid
  body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$3" "$4" "$5")
  resp=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body")
  code=$(echo "$resp" | j "d['code']"); mid=$(echo "$resp" | j "d['data']['meetingId']")
  [ "$code" = "0" ] && [ -n "$mid" ] || { echo "ERROR 创建失败 code=$code" >&2; return 1; }
  echo "$mid"
}
free_slot() { # free_slot <roomId> <起始天数偏移>
  local off d start end last
  last=$(( $2 + 20 )); [ "$last" -gt 28 ] && last=28
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

U1=$(login user1 user123); AP1=$(login approver1 user123)
[ -n "$U1" ] && [ -n "$AP1" ] || { echo "登录失败，终止"; exit 1; }
IDS=""
cleanup() {
  for m in $IDS; do
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_approval WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting WHERE id=$m;" >/dev/null
  done
}
trap cleanup EXIT

# ⚠️ 前置守卫（T4.3 起）：本脚本断言的是"消息还在队列里"，而 notification-service
# 就是这些队列的消费者——它一在跑，消息在断言之前就被 ack 掉了，看到的全是 0 条，
# 表现得像"生产端没发出去"（实测：消费者在跑时本脚本 5/11，且失败信息完全指向错误的方向）。
if [ -n "$(lsof -nP -iTCP:8083 -sTCP:LISTEN -t 2>/dev/null)" ]; then
  echo "❌ 检测到 notification-service（:8083）正在运行：它会消费掉待断言的消息。"
  echo "   先停掉它再跑本脚本（例：kill \$(lsof -nP -iTCP:8083 -sTCP:LISTEN -t)），"
  echo "   或改跑 scripts/verify-m4-notify-consume.sh（那个脚本验的是消费侧，会自己起停 8083）。"
  exit 1
fi

echo
echo "=== 清空历史消息（保证断言只看到本次产生的）==="
for q in meeting.created meeting.updated meeting.cancelled meeting.approved meeting.rejected; do
  drain "$q" >/dev/null
done
echo "  已清空 5 个事件队列"

echo
echo "=== 事件一：创建预约 → meeting.created ==="
SLOT=$(free_slot $ROOM_OPEN 1) || exit 1
M1=$(book "$U1" "T41-创建" "$ROOM_OPEN" "${SLOT%%|*}" "${SLOT##*|}") || exit 1; IDS="$IDS $M1"
sleep 1
check "meeting.created 收到 1 条" "$(drain meeting.created)" "1"
check "消息体带 msgId" "$([ -n "$(drained "msgs[0]['payload'] and 'msgId' in msgs[0]['payload'] and 'yes'")" ] && echo yes || echo no)" "yes"
check "payload.data.meetingId 与本次一致" \
  "$(drained "json.loads(msgs[0]['payload'])['data']['meetingId']")" "$M1"
check "routingKey = meeting.created" "$(drained "msgs[0]['routing_key']")" "meeting.created"

echo
echo "=== 事件二：修改会议 → meeting.updated ==="
UPD=$(printf '{"title":"T41-创建-改后","roomId":%s,"startTime":"%s","endTime":"%s","version":0}' \
  "$ROOM_OPEN" "${SLOT%%|*}" "${SLOT##*|}")
check "修改接口返回 0" "$(curl -s --noproxy '*' -X PUT "$BASE/api/meetings/$M1" -H "Authorization: Bearer $U1" \
  -H 'Content-Type: application/json' -d "$UPD" | j "d['code']")" "0"
sleep 1
check "meeting.updated 收到 1 条" "$(drain meeting.updated)" "1"

echo
echo "=== 事件三：取消 → meeting.cancelled（带取消原因）==="
check "取消接口返回 0" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST "$BASE/api/meetings/$M1/cancel" \
  -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' -d '{"reason":"T41 验证取消"}' | grep -c 200)" "1"
sleep 1
check "meeting.cancelled 收到 1 条" "$(drain meeting.cancelled)" "1"
check "payload 带取消原因（comment 字段复用为原因）" \
  "$(drained "json.loads(msgs[0]['payload'])['data']['comment']")" "T41 验证取消"

echo
echo "=== 事件四/五：审批通过 / 拒绝 → meeting.approved / meeting.rejected ==="
SLOT2=$(free_slot $ROOM_APPROVAL 1) || exit 1
M2=$(book "$U1" "T41-待审A" "$ROOM_APPROVAL" "${SLOT2%%|*}" "${SLOT2##*|}") || exit 1; IDS="$IDS $M2"
sleep 1
drain meeting.created >/dev/null   # 创建事件本步不验，清掉避免干扰后续断言
check "审批通过接口返回 20" "$(curl -s --noproxy '*' -X POST "$BASE/api/approvals/$M2/pass" \
  -H "Authorization: Bearer $AP1" -H 'Content-Type: application/json' -d '{"comment":"同意"}' | j "d['data']['status']")" "20"
sleep 1
check "meeting.approved 收到 1 条" "$(drain meeting.approved)" "1"
# ⚠️ 原来是"非空即通过"——organiserId 也在 payload 里，两者写反了照样绿（评审 R6）。
# 取 approver1 的真实 id 来比，顺带把 organiserId 也钉住。
AP1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='approver1';")
U1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user1';")
check "approved 消息的 operatorId == approver1 的 id" \
  "$(drained "json.loads(msgs[0]['payload'])['data']['operatorId']")" "$AP1_ID"
check "approved 消息的 organiserId == user1 的 id（两者没写反）" \
  "$(drained "json.loads(msgs[0]['payload'])['data']['organiserId']")" "$U1_ID"

SLOT3=$(free_slot $ROOM_APPROVAL 3) || exit 1
M3=$(book "$U1" "T41-待审B" "$ROOM_APPROVAL" "${SLOT3%%|*}" "${SLOT3##*|}") || exit 1; IDS="$IDS $M3"
sleep 1
drain meeting.created >/dev/null
check "审批拒绝接口返回 60" "$(curl -s --noproxy '*' -X POST "$BASE/api/approvals/$M3/reject" \
  -H "Authorization: Bearer $AP1" -H 'Content-Type: application/json' -d '{"comment":"时段冲突，请改期"}' | j "d['data']['status']")" "60"
sleep 1
check "meeting.rejected 收到 1 条" "$(drain meeting.rejected)" "1"
check "rejected 消息带审批意见" \
  "$(drained "json.loads(msgs[0]['payload'])['data']['comment']")" "时段冲突，请改期"

echo
echo "=== 清理 ==="
cleanup
LEFTOVER=0
for m in $IDS; do LEFTOVER=$(( LEFTOVER + $(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id=$m;") )); done
check "库里已无本次数据" "$LEFTOVER" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
