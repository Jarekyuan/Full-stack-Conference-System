#!/usr/bin/env bash
# ============================================================================
# T4.3 验证：通知消费（幂等 / 手动 ACK / 重试上限 / 死信）
#
# 覆盖：
#   ① 五类事件各自生成正确的通知（收件人规则按 docs/04 5.3）
#   ② 重复 msgId 重发 → 不重复插入；同内容不同 msgId → 正常插入（去重按 msgId，不是按内容）
#   ③ 毒消息（结构非法）→ 重试 3 次后进 meeting.dlq，且不产生任何通知
#   ④ 500 字审批意见 → 通知内容截断到列宽内、写得进去（CLAUDE.md 踩坑 #10 的同类场景）
#
# 前置：基础设施 + gateway/auth/meeting 三服务已启动（notification 由本脚本自起自收，同 T4.2 的脚本）。
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
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/server/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar"
LOG=/tmp/t43-notify.log
PORT=8083
ROOM_OPEN=6       # 云杉厅：免审批
ROOM_APPROVAL=5   # 红枫厅：需审批
SYNTH_MEETING=900001  # 幂等验证用的"人造会议"（不落 meeting 表，只验证消费端行为）
PASS=0; FAIL=0
STARTED=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
# 轮询等待某个 SQL 计数达到期望值（事件是异步消费的，不能睡固定时间就断言）
wait_count() { # wait_count <expected> <sql> [timeout_seconds]
  local want=$1 query=$2 timeout=${3:-20} i got
  for i in $(seq 1 "$timeout"); do
    got=$(sql "$query")
    [ "$got" = "$want" ] && { echo "$got"; return 0; }
    sleep 1
  done
  echo "$got"
}
# Redis 里的幂等键同样是"脚本自己的残留"：mq:idem:{msgId} TTL 24h，
# 不清理的话**第二次跑脚本**时同 msgId 的消息会被正确地判成重复、断言却期待"新插入"（踩坑 #9 的 Redis 版本）。
# 口令走 REDISCLI_AUTH 环境变量传入，不写在命令行里（避免口令进入进程列表/输出）。
redis_del() {
  docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli DEL "$@" > /dev/null 2>&1
}
cleanup() {
  redis_del mq:idem:t43-idem-0001 mq:idem:t43-idem-0002 mq:idem:t43-poison-0001 mq:idem:t43-poison-0002
  dlq_drain > /dev/null
  for m in ${IDS:-}; do
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_approval WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting WHERE id=$m;" > /dev/null
  done
  sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$SYNTH_MEETING;" > /dev/null
  local pid
  pid=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$pid" ] && { kill "$pid" 2>/dev/null; sleep 2; }
}
trap cleanup EXIT

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" \
    | j "d['data']['accessToken']"
}
book() { # book <token> <title> <roomId> <start> <end> [participants-csv]
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
# 直接往交换机投消息（造幂等/毒消息场景；management API 的 publish 是同步的）
publish() { # publish <routingKey> <payload字符串>
  python3 - "$1" "$2" << 'PY' > /tmp/t43-pub.json
import json, sys
print(json.dumps({"properties": {"content_type": "application/json"},
                  "routing_key": sys.argv[1], "payload": sys.argv[2], "payload_encoding": "string"}))
PY
  curl -s --noproxy '*' -u "$AUTH" -X POST "$AMQP_API/exchanges/%2F/meeting.events/publish" \
    -H 'Content-Type: application/json' --data-binary @/tmp/t43-pub.json | j "d['routed']"
}
# 看死信队列（不走 /api/queues 的统计：那玩意有 ~5 秒缓存，会误判）。
# ack_requeue_true = 只看不取走：多阶段断言才能用"累计条数"，也不会把消息提前消费掉。
dlq_get() {
  curl -s --noproxy '*' -u "$AUTH" -X POST "$AMQP_API/queues/%2F/meeting.dlq/get" \
    -H 'Content-Type: application/json' -d '{"count":50,"ackmode":"ack_requeue_true","encoding":"auto"}' \
    > /tmp/t43-dlq.json
  python3 -c "import json;print(len(json.load(open('/tmp/t43-dlq.json'))))" 2>/dev/null || echo 0
}
# 真正取走（只为清理，不留残留）
dlq_drain() {
  curl -s --noproxy '*' -u "$AUTH" -X POST "$AMQP_API/queues/%2F/meeting.dlq/get" \
    -H 'Content-Type: application/json' -d '{"count":50,"ackmode":"ack_requeue_false","encoding":"auto"}' \
    > /dev/null
}
dlq_field() { python3 -c "
import json
m = json.load(open('/tmp/t43-dlq.json'))
print($1) if m else print('')
" 2>/dev/null; }

echo
echo "=== 0. 起 notification-service（消费端跑起来才有得验）==="
OLD=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')
[ -n "$OLD" ] && { echo "  停掉 8083 上的既有实例 $OLD（避免与新实例抢消费）"; kill $OLD 2>/dev/null; sleep 2; }
[ -f "$JAR" ] || { echo "❌ 找不到 jar：$JAR"; exit 1; }
(cd "$ROOT/server" && env -u SERVER__PORT java -jar "$JAR" > "$LOG" 2>&1) &
STARTED=1
UP=""
for i in $(seq 1 60); do
  UP=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/actuator/health" | j "d['status']")
  [ "$UP" = "UP" ] && break
  sleep 1
done
check "notification-service health UP" "${UP:-未响应}" "UP"
[ "$UP" = "UP" ] || { tail -20 "$LOG"; exit 1; }
# 消费者接入（同样防统计缓存：轮询到 5 个事件队列都有 consumer）
CONSUMERS=""
for i in $(seq 1 20); do
  CONSUMERS=$(curl -s --noproxy '*' -u "$AUTH" "$AMQP_API/queues" | python3 -c "
import sys,json
qs=json.load(sys.stdin)
print(sum(1 for q in qs if q['name'].startswith('meeting.') and q['name']!='meeting.dlq' and (q.get('consumers') or 0)>0))")
  [ "$CONSUMERS" = "6" ] && break
  sleep 1
done
check "6 个事件队列都有消费者（T4.5 起 remind 也接上了）" "$CONSUMERS" "6"

echo
echo "=== 1. 清空历史消息（只留本次产生的）==="
for q in meeting.created meeting.approved meeting.rejected meeting.cancelled meeting.updated meeting.dlq; do
  curl -s --noproxy '*' -u "$AUTH" -X POST "$AMQP_API/queues/%2F/$q/get" -H 'Content-Type: application/json' \
    -d '{"count":100,"ackmode":"ack_requeue_false","encoding":"auto"}' > /dev/null
done
# Redis 幂等键也要清：mq:idem:{msgId} TTL 24h，留着的话同 msgId 会（正确地）被判重复，
# 于是"第一次投递应产生 1 条"的断言反倒失败——脚本必须先把自己弄到干净状态（踩坑 #9 的 Redis 版本）
redis_del mq:idem:t43-idem-0001 mq:idem:t43-idem-0002 mq:idem:t43-poison-0001 mq:idem:t43-poison-0002
echo "  已清空（含 Redis 幂等键）"

U1=$(login user1 user123); U2=$(login user2 user123); U3=$(login user3 user123)
AP1=$(login approver1 user123)
{ [ -n "$U1" ] && [ -n "$U2" ] && [ -n "$U3" ] && [ -n "$AP1" ]; } || { echo "登录失败，终止"; exit 1; }
U2_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user2';")
U3_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user3';")
U1_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user1';")
APPROVER_COUNT=$(sql "SELECT COUNT(DISTINCT ur.user_id) FROM smt_meeting.sys_user_role ur
  JOIN smt_meeting.sys_role r ON r.id=ur.role_id JOIN smt_meeting.sys_user u ON u.id=ur.user_id
  WHERE r.code='APPROVER' AND r.status=1 AND u.status=1;")
IDS=""

echo
echo "=== 2. 免审批预约 → 组织人收 MEETING_CREATED（且不惊动审批人）==="
SLOT=$(free_slot $ROOM_OPEN 1) || exit 1
M1=$(book "$U1" "T43-免审会" "$ROOM_OPEN" "${SLOT%%|*}" "${SLOT##*|}") || exit 1; IDS="$IDS $M1"
check "组织人收到 1 条通知" "$(wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M1 AND user_id=$U1_ID;")" "1"
check "类型是 MEETING_CREATED" "$(sql "SELECT type FROM smt_meeting.notification WHERE meeting_id=$M1;")" "MEETING_CREATED"
check "标题是「预约成功」（免审批）" "$(sql "SELECT title FROM smt_meeting.notification WHERE meeting_id=$M1;")" "预约成功"
check "免审批预约不产生 APPROVAL_TODO" "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M1 AND type='APPROVAL_TODO';")" "0"
check "read_flag 初始为 0（未读）" "$(sql "SELECT read_flag FROM smt_meeting.notification WHERE meeting_id=$M1;")" "0"

echo
echo "=== 3. 审批房预约 → 审批人各收 APPROVAL_TODO（组织人另收 MEETING_CREATED）==="
SLOT2=$(free_slot $ROOM_APPROVAL 1) || exit 1
M2=$(book "$U1" "T43-待审会" "$ROOM_APPROVAL" "${SLOT2%%|*}" "${SLOT2##*|}") || exit 1; IDS="$IDS $M2"
check "审批人收到 APPROVAL_TODO 条数 = 库里启用的 APPROVER 人数（$APPROVER_COUNT）" \
  "$(wait_count "$APPROVER_COUNT" "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M2 AND type='APPROVAL_TODO';")" "$APPROVER_COUNT"
check "组织人收到的是 MEETING_CREATED 而非待办" \
  "$(sql "SELECT type FROM smt_meeting.notification WHERE meeting_id=$M2 AND user_id=$U1_ID;")" "MEETING_CREATED"
check "组织人不在待办收件人里" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M2 AND type='APPROVAL_TODO' AND user_id=$U1_ID;")" "0"

echo
echo "=== 4. 审批通过 → 组织人收 APPROVAL_RESULT（带审批意见）==="
LONG_COMMENT=$(python3 -c "print('意见' * 240)")   # 480 字：逼近 comment 列宽 500（踩坑 #10 的同类场景）
PASS_BODY=$(printf '{"comment":"%s"}' "$LONG_COMMENT")
check "审批接口返回 20" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/approvals/$M2/pass" -H "Authorization: Bearer $AP1" \
     -H 'Content-Type: application/json' -d "$PASS_BODY" | j "d['data']['status']")" "20"
check "组织人收到 APPROVAL_RESULT" \
  "$(wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M2 AND user_id=$U1_ID AND type='APPROVAL_RESULT';")" "1"
check "标题是「审批通过」" \
  "$(sql "SELECT title FROM smt_meeting.notification WHERE meeting_id=$M2 AND type='APPROVAL_RESULT';")" "审批通过"
# 480 字意见 + 前缀文案远超 content 的 500 列宽：必须被截断到 ≤500 才写得进去
check "内容长度不超过列宽 500（超宽会 1406 整批回滚）" \
  "$(sql "SELECT IF(CHAR_LENGTH(content)<=500, 'ok', CONCAT('超宽:', CHAR_LENGTH(content))) FROM smt_meeting.notification WHERE meeting_id=$M2 AND type='APPROVAL_RESULT';")" "ok"
check "内容确实被截断了（说明长意见走到了截断分支）" \
  "$(sql "SELECT IF(CHAR_LENGTH(content)=500, 'clamped', 'short') FROM smt_meeting.notification WHERE meeting_id=$M2 AND type='APPROVAL_RESULT';")" "clamped"

echo
echo "=== 5. 取消（带参会人）→ 参会人收 MEETING_CANCELLED，组织人不收 ==="
SLOT3=$(free_slot $ROOM_OPEN 3) || exit 1
M3=$(book "$U1" "T43-取消会" "$ROOM_OPEN" "${SLOT3%%|*}" "${SLOT3##*|}" "$U2_ID,$U3_ID") || exit 1; IDS="$IDS $M3"
wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M3 AND user_id=$U1_ID;" > /dev/null
check "取消接口 200" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST "$BASE/api/meetings/$M3/cancel" \
     -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' -d '{"reason":"T43 验证取消"}' | grep -c 200)" "1"
check "参会人 user2 收到 MEETING_CANCELLED" \
  "$(wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M3 AND user_id=$U2_ID AND type='MEETING_CANCELLED';")" "1"
check "参会人 user3 也收到（同一事件通知多人）" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M3 AND user_id=$U3_ID AND type='MEETING_CANCELLED';")" "1"
check "取消原因带在内容里" \
  "$(sql "SELECT IF(content LIKE '%T43 验证取消%','yes','no') FROM smt_meeting.notification WHERE meeting_id=$M3 AND user_id=$U2_ID;")" "yes"
check "组织人没有收到取消通知（docs/04 5.3 收件人只有参会人）" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$M3 AND user_id=$U1_ID AND type='MEETING_CANCELLED';")" "0"

echo
echo "=== 6. 消费幂等：同一 msgId 重发 → 不重复插入 ==="
IDEM_MSG=$(printf '{"msgId":"t43-idem-0001","occurredAt":"2026-10-16T12:00:00","data":{"type":"CREATED","meetingId":%s,"title":"T43-幂等验证","roomId":%s,"organiserId":%s,"startTime":"2026-11-20T10:00:00","endTime":"2026-11-20T11:00:00","status":20,"operatorId":%s,"comment":null}}' \
  "$SYNTH_MEETING" "$ROOM_OPEN" "$U1_ID" "$U1_ID")
check "第一次投递被路由" "$(publish meeting.created "$IDEM_MSG")" "True"
check "第一次投递产生 1 条通知" "$(wait_count 1 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$SYNTH_MEETING;")" "1"
check "第二次投递（同 msgId）被路由" "$(publish meeting.created "$IDEM_MSG")" "True"
# ⚠️ 不用"睡 3 秒再数行"（评审 O3）：那分不清"去重生效"与"第二条还没消费完"。
# 等**消费端的证据日志**（"重复投递，丢弃"）出现，才算真的走了去重分支
IDEM_EVIDENCE=0
for i in $(seq 1 20); do
  IDEM_EVIDENCE=$(grep -c "重复投递，丢弃.*t43-idem-0001" "$LOG" 2>/dev/null | head -1)
  [ "$IDEM_EVIDENCE" -ge 1 ] && break
  sleep 1
done
check "日志出现"重复投递，丢弃"（证明走的是去重分支，不是还没消费）" "$IDEM_EVIDENCE" "1"
check "重复 msgId 后仍是 1 条（幂等生效）" "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$SYNTH_MEETING;")" "1"
IDEM_MSG2=$(printf '%s' "$IDEM_MSG" | sed 's/t43-idem-0001/t43-idem-0002/')
check "换一个 msgId 的同样内容被路由（证明去重按 msgId 而非内容）" "$(publish meeting.created "$IDEM_MSG2")" "True"
check "于是变成 2 条" "$(wait_count 2 "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id=$SYNTH_MEETING;")" "2"

echo
echo "=== 7. 死信：两类失败路径都要进 DLQ，且行为不同 ==="
# 7a 结构非法（data 缺失）：重试它也不会变好 → 应当**立即**转死信，不做无谓重试
POISON1=$(printf '{"msgId":"t43-poison-0001","occurredAt":"2026-10-16T12:00:00","data":null}')
check "结构非法消息被路由" "$(publish meeting.created "$POISON1")" "True"
DLQ_COUNT=0
for i in $(seq 1 20); do
  DLQ_COUNT=$(dlq_get)
  [ "$DLQ_COUNT" -ge 1 ] && break
  sleep 1
done
check "死信队列收到 1 条" "$DLQ_COUNT" "1"
check "死信消息保留原 routingKey（能看出是哪个事件死的）" "$(dlq_field "m[0]['routing_key']")" "meeting.created"
check "日志判定为'结构不合法'" "$(grep -c '消息结构不合法.*t43-poison-0001' "$LOG")" "1"
# ⚠️ 别用 'Retry: count=' 计数来判"有没有重试"：RetryTemplate 在**每次尝试开始时**都会打这一行，
# 处理成功的消息同样有 count=0。要判"没重试"，看的是它压根没进过失败/耗尽路径。
check "结构非法**不走重试**（没进过失败路径）" "$(grep -c '消费失败.*t43-poison-0001' "$LOG")" "0"

# 7b 结构合法、但业务处理失败（type 为 null → NotificationService 抛异常）：
#    这才走"重试 3 次 → 耗尽 → 死信"的路径，也就是 docs/05 T4.3 ④ 要验的那条
POISON2=$(printf '{"msgId":"t43-poison-0002","occurredAt":"2026-10-16T12:00:00","data":{"type":null,"meetingId":900002,"title":"T43-毒消息","roomId":6,"organiserId":%s,"startTime":"2026-11-20T10:00:00","endTime":"2026-11-20T11:00:00","status":20,"operatorId":null,"comment":null}}' "$U1_ID")
check "业务失败消息被路由" "$(publish meeting.created "$POISON2")" "True"
DLQ_COUNT=0
for i in $(seq 1 20); do
  DLQ_COUNT=$(dlq_get)
  [ "$DLQ_COUNT" -ge 2 ] && break
  sleep 1
done
check "重试耗尽后死信队列收到第 2 条" "$DLQ_COUNT" "2"
# 证据一：只有这一条消息"重试耗尽"（结构非法那条不走重试，正常消息不会耗尽）
check "只有一条消息重试耗尽" "$(grep -c 'Retry failed last attempt' "$LOG")" "1"
# 证据二：两次“Sleeping for”（500ms、1000ms）= 指数退避真的发生了，不是连着立刻重试 3 次
check "退避睡了两次（指数退避生效）" "$(grep -c 'Sleeping for' "$LOG")" "2"
check "日志能看到'已重试 3 次'（模式不含全角括号，避免又写成对不上的字面量）" \
  "$(grep -c '已重试 3 次.*t43-poison-0002' "$LOG")" "1"
# 失败的毒消息**不能留下幂等标记**（否则将来重放死信会被自己的标记挡住、白攒）
check "业务失败的毒消息没有留下幂等标记（重放死信时能重新处理）" \
  "$(docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli EXISTS mq:idem:t43-poison-0002 2>/dev/null | tr -d '\r')" "0"
check "两条毒消息都没有产生通知" "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id IN (900001,900002) AND title LIKE '%毒%';")" "0"

echo
echo "=== 8. 清理与残留检查 ==="
cleanup
LEFTOVER=0
for m in $IDS; do LEFTOVER=$(( LEFTOVER + $(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id=$m;") )); done
check "库里已无本次数据" "$LEFTOVER" "0"
check "通知表无本次残留" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id IN ($SYNTH_MEETING${IDS// /,});")" "0"
check "事件队列无积压（消费即 ack）" \
  "$(curl -s --noproxy '*' -u "$AUTH" "$AMQP_API/queues/%2F/meeting.created" | j "d['messages']")" "0"
check "死信队列已清空（本脚本取走的毒消息都已消费掉）" "$(dlq_drain > /dev/null; dlq_get)" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
