#!/usr/bin/env bash
# ============================================================================
# M3 定时任务验证（T3.5：20 → 30 → 40 + 多实例互斥 + 幂等）
#
# 本脚本自己**起两个 meeting-service 实例**并把任务间隔压到 1~3 秒：
#   主实例 :8082（脚本接管，跑完会停掉——请自行用默认间隔重启）
#   陪跑 :8092（--spring.cloud.nacos.discovery.enabled=false，不注册、不接流量，只抢任务锁）
#
# ⚠️ 为什么"多实例互斥"要灌 1000 条到点数据：两个实例的任务单次只跑几十毫秒，
#    3 秒间隔下几乎撞不上，光看日志会得出"互斥没生效"的错误结论（第一次跑就是这样）。
#    灌满之后单轮任务持续数秒，另一个实例必然在其间触发 → 才真的测到那把锁。
#
# ⚠️ 跑本脚本会短暂占用 8082 端口（会先停掉你正在跑的服务）。退出码 0 = 全部通过。
# ⚠️ 输出请重定向到文件再查看：脚本会拉起后台 java 子进程，用管道会让读端一直等不到 EOF。
# ============================================================================
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
BASE="http://localhost:8080"
ROOM="${SCHEDULER_ROOM:-6}"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
PASS=0; FAIL=0
MAIN_LOG="$SERVER/logs/scheduler-main.log"
RECON_LOG="$SERVER/logs/scheduler-reconcile.log"
PEER_LOG="$SERVER/logs/scheduler-peer.log"
BATCH_TITLE='M3定时-批量'
STUCK_PENDING_TITLE='M3定时-待审批超时'

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
status_of() { sql "SELECT status FROM smt_meeting.meeting WHERE id=$1;"; }
login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
book() {
  local body resp code mid
  body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s"}' "$2" "$ROOM" "$3" "$4")
  resp=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "$U1_HEADER" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body")
  code=$(echo "$resp" | j "d['code']"); mid=$(echo "$resp" | j "d['data']['meetingId']")
  [ "$code" = "0" ] && [ -n "$mid" ] || { echo "ERROR 创建失败 code=$code $(echo "$resp" | head -c 150)" >&2; return 1; }
  echo "$mid"
}
start_main() { # $1 = 正常轮询间隔；$2 = "reconcile" 时把对账调到每 5 秒（隔离验证用）
  local holder
  # ⚠️ 必须用数组：cron 表达式的空格会被 shell 拆成 6 个参数，
  #    结果传进去的值变成 "*/5"，对账任务静默地永不触发（踩过一次）
  local -a extra=()
  CRON_NOTE="未覆盖（用类里的默认值：每小时 :05）"
  case "${2:-}" in
    *reconcile*)
      extra=(--meeting.task.reconcile.cron='*/5 * * * * *')
      CRON_NOTE="已覆盖为每 5 秒（场景六隔离验证用）"
      ;;
  esac
  holder=$(lsof -nP -iTCP:8082 -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$holder" ] && kill "$holder" 2>/dev/null && sleep 2
  ( cd "$SERVER" && nohup env -u SERVER__PORT java -jar meeting-service/target/meeting-service-1.0.0-SNAPSHOT.jar \
      --meeting.task.status.delay-ms="$1" ${extra[@]+"${extra[@]}"} > "${LOG_TARGET:-$MAIN_LOG}" 2>&1 & echo $! > /tmp/m3-main.pid )
  for _ in $(seq 1 40); do grep -q "Started MeetingServiceApplication" "${LOG_TARGET:-$MAIN_LOG}" 2>/dev/null && break; sleep 2; done
  # 留证：明确说清 cron 覆盖有没有生效（排查"参数没生效"这类问题时，这一行能省掉三轮试跑）
  echo "  [诊断] 对账 cron：$CRON_NOTE"
  grep -q "Started MeetingServiceApplication" "${LOG_TARGET:-$MAIN_LOG}" 2>/dev/null && return 0
  echo "ERROR 主实例未起来"; return 1
}
start_peer() {
  free_port 8092
  ( cd "$SERVER" && nohup env -u SERVER__PORT java -jar meeting-service/target/meeting-service-1.0.0-SNAPSHOT.jar \
      --server.port=8092 --spring.cloud.nacos.discovery.enabled=false --meeting.task.status.delay-ms="$1" \
      > "$PEER_LOG" 2>&1 & echo $! > /tmp/m3-peer.pid )
  for _ in $(seq 1 40); do grep -q "Started MeetingServiceApplication" "$PEER_LOG" 2>/dev/null && return 0; sleep 2; done
  echo "ERROR 陪跑实例未起来"; return 1
}
free_port() { # 杀掉占用该端口的进程（比 pid 文件可靠：孤儿实例不会被漏掉）
  local pid
  pid=$(lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$pid" ] && kill "$pid" 2>/dev/null && sleep 2
  return 0
}
stop_pidfile() { [ -f "$1" ] && kill "$(cat "$1")" 2>/dev/null; rm -f "$1"; sleep 1; }
drop_meeting() {
  sql "DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$1; DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=$1; DELETE FROM smt_meeting.meeting WHERE id=$1;" >/dev/null
}
cleanup() {
  stop_pidfile /tmp/m3-peer.pid
  free_port 8092
  sql "DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id IN (SELECT id FROM (SELECT id FROM smt_meeting.meeting WHERE title='$BATCH_TITLE') t);" >/dev/null
  sql "DELETE FROM smt_meeting.meeting WHERE title='$BATCH_TITLE';" >/dev/null
  sql "DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id IN (SELECT id FROM (SELECT id FROM smt_meeting.meeting WHERE title='$STUCK_PENDING_TITLE') t);" >/dev/null
  sql "DELETE FROM smt_meeting.meeting WHERE title='$STUCK_PENDING_TITLE';" >/dev/null
  for m in ${IDS:-}; do drop_meeting "$m"; done
  # 必须按端口清：`echo $!` 在 `( A && B & )` 里拿到的是子 shell 的 pid 而不是 java 的，
  # pid 文件不可靠（实测漏杀，端口被占着导致后续重启失败）
  stop_pidfile /tmp/m3-main.pid
  free_port 8082
}
trap cleanup EXIT

echo
echo "=== 启动两个实例（任务间隔：各 1 秒）==="
start_main 1000 || exit 1
echo "  主实例就绪（:8082）"
start_peer 1000 || exit 1
echo "  陪跑实例就绪（:8092）"

U1=$(login user1 user123); U1_HEADER="Authorization: Bearer $U1"
[ -n "$U1" ] || { echo "登录失败"; exit 1; }
# 等网关把新实例纳入负载均衡（Nacos 注册 + 网关缓存刷新都有延迟）
READY=no
for _ in $(seq 1 20); do
  if [ "$(curl -s --noproxy '*' "$BASE/api/meetings?scope=mine&page=1&size=1" -H "$U1_HEADER" | j "d['code']")" = "0" ]; then
    READY=yes; break
  fi
  sleep 2
done
[ "$READY" = "yes" ] || { echo "网关 30 秒内仍打不到 meeting-service，终止"; exit 1; }
echo "  网关链路已就绪"
IDS=""

D=$(date -v+2d +%Y-%m-%d)
M1=$(book "$U1" "M3定时-立即到点" "${D} 10:00:00" "${D} 11:00:00") || exit 1; IDS="$IDS $M1"
M2=$(book "$U1" "M3定时-真实等待" "$(date -v+1M +'%Y-%m-%d %H:%M:00')" "$(date -v+3M +'%Y-%m-%d %H:%M:00')") || exit 1; IDS="$IDS $M2"
check "两场都是已预约(20)" "$(status_of "$M1")/$(status_of "$M2")" "20/20"

echo
echo "=== 场景一：把开始时间挪到过去（等价于「到点了」）→ 任务自动 20→30 ==="
sql "UPDATE smt_meeting.meeting SET start_time=NOW()-INTERVAL 1 MINUTE, end_time=NOW()+INTERVAL 30 MINUTE WHERE id=$M1;" >/dev/null
sleep 5
check "任务自动把 M1 推进到进行中(30)" "$(status_of "$M1")" "30"
DETAIL=$(curl -s --noproxy '*' "$BASE/api/meetings/$M1" -H "$U1_HEADER")
check "时间线最后一条是 START" "$(echo "$DETAIL" | j "[l['event'] for l in d['data']['logs']][-1]")" "START"
check "系统操作的 operatorId 为 null（前端显示「系统」）" "$(echo "$DETAIL" | j "d['data']['logs'][-1]['operatorId']")" "None"

echo
echo "=== 场景二：把结束时间挪到过去 → 任务自动 30→40 ==="
sql "UPDATE smt_meeting.meeting SET end_time=NOW()-INTERVAL 1 MINUTE WHERE id=$M1;" >/dev/null
sleep 5
check "任务自动把 M1 推进到已结束(40)" "$(status_of "$M1")" "40"

echo
echo "=== 场景三：真实时间线（不碰 SQL）——M2 在 1 分钟后开始，等任务自己推 ==="
echo "  （最多等待 90 秒…）"
for _ in $(seq 1 30); do
  [ "$(status_of "$M2")" = "30" ] && break
  sleep 3
done
check "M2 到点后自动进入进行中" "$(status_of "$M2")" "30"

echo
echo "=== 场景四：批量流转 + 多实例互斥（灌 1500 条到点会议把任务窗口拉开）==="
sql "INSERT INTO smt_meeting.meeting (title, room_id, organiser_id, start_time, end_time, status)
     SELECT '$BATCH_TITLE', $ROOM, 1, NOW() - INTERVAL 1 MINUTE, NOW() + INTERVAL 2 HOUR, 20
     FROM information_schema.columns LIMIT 1500;" >/dev/null
check "已灌入 1500 条到点的待开始会议" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='$BATCH_TITLE';")" "1500"
sleep 30
check "1500 条全部被推进到进行中(30)" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='$BATCH_TITLE' AND status=30;")" "1500"
# 撞锁的是"没抢到的那一个"，而不是固定的某个实例——两个都要看。
# （第一次跑就是把断言写死在陪跑上，结果实际是主实例在跳过，得出了"互斥失效"的错误结论）
SKIP_MAIN=$(grep -c '本轮跳过' "$MAIN_LOG" 2>/dev/null || true); SKIP_MAIN=${SKIP_MAIN:-0}
SKIP_PEER=$(grep -c '本轮跳过' "$PEER_LOG" 2>/dev/null || true); SKIP_PEER=${SKIP_PEER:-0}
DONE=$(grep -c '状态流转完成' "$MAIN_LOG" 2>/dev/null || true); DONE=${DONE:-0}
echo "  （撞锁跳过：主 $SKIP_MAIN 次 / 陪跑 $SKIP_PEER 次；主实例汇报完成 $DONE 次）"
check "至少一个实例因抢不到锁而跳过本轮（互斥真的生效）" \
  "$([ $((SKIP_MAIN + SKIP_PEER)) -gt 0 ] && echo yes || echo no)" "yes"
check "主实例汇报了流转结果" "$([ "$DONE" -gt 0 ] && echo yes || echo no)" "yes"

echo
echo "=== 场景五：幂等（数据已处理完，再跑若干轮不应产生任何变化）==="
LOG_COUNT_SQL="SELECT COUNT(*) FROM smt_meeting.meeting_status_log WHERE meeting_id IN (SELECT id FROM (SELECT id FROM smt_meeting.meeting WHERE title='$BATCH_TITLE') t);"
BEFORE="$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='$BATCH_TITLE' AND status=30;")|$(sql "$LOG_COUNT_SQL")|$(status_of "$M1")"
sleep 10
AFTER="$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='$BATCH_TITLE' AND status=30;")|$(sql "$LOG_COUNT_SQL")|$(status_of "$M1")"
check "状态与日志条数完全不变（CAS 幂等）" "$AFTER" "$BEFORE"
check "M1 的 START 日志只有一条" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting_status_log WHERE meeting_id=$M1 AND event='START';")" "1"

echo
echo "=== 场景六：对账补偿（T3.6）——隔离验证：关掉正常轮询，只留每 5 秒一次的对账 ==="
# 为什么必须隔离：对账的时间条件（过了 2 小时）是正常轮询条件（<= NOW()）的**子集**，
# 两者同时开着时，正常轮询一定先把数据修完，对账永远看不到东西——测不出它到底在不在工作。
stop_pidfile /tmp/m3-peer.pid
free_port 8092
# ⚠️ 必须**全清**再起新实例并换日志文件：被 kill 的旧实例若还活着（异步退出），
#    它的 fd 会继续往同一个日志文件写，新旧实例的日志交织在一起——
#    上一次跑就是这么把"对账没打告警"误判出来的
pkill -f 'meeting-service-1.0.0-SNAPSHOT.jar' 2>/dev/null; sleep 3
rm -f "$RECON_LOG"; LOG_TARGET="$RECON_LOG"
start_main 3600000 --reconcile || exit 1        # 正常轮询≈关闭（1 小时一次），对账每 5 秒
echo "  已切到「只对账」模式"

D3=$(date -v+4d +%Y-%m-%d)
M3=$(book "$U1" "M3对账-造脏" "${D3} 10:00:00" "${D3} 11:00:00") || exit 1; IDS="$IDS $M3"
M4=$(book "$U1" "M3对账-正常数据" "${D3} 14:00:00" "${D3} 15:00:00") || exit 1; IDS="$IDS $M4"
# 把 M3 做坏成"该开始却没开始、该结束也没结束"：开始时间 3 小时前、结束时间 2 小时前、状态仍是 20
sql "UPDATE smt_meeting.meeting SET start_time=NOW()-INTERVAL 3 HOUR, end_time=NOW()-INTERVAL 2 HOUR WHERE id=$M3;" >/dev/null
check "脏数据已就位（20 且时间早过）" "$(status_of "$M3")" "20"
sleep 12
check "对账把它一路修到已结束(40)" "$(status_of "$M3")" "40"
check "正常数据（未来时段）没被动" "$(status_of "$M4")" "20"
# 待审批超时那类：10 既不占时段也没被任何自动流转覆盖，只有对账能发现——它只告警、不自动修
sql "INSERT INTO smt_meeting.meeting (title,room_id,organiser_id,start_time,end_time,status)
     VALUES ('$STUCK_PENDING_TITLE', $ROOM, 1, NOW()-INTERVAL 5 HOUR, NOW()-INTERVAL 4 HOUR, 10);" >/dev/null
sleep 8
check "待审批超时仍保持 10（只告警不自动修，收尾策略待产品决策）" \
  "$(sql "SELECT status FROM smt_meeting.meeting WHERE title='$STUCK_PENDING_TITLE';")" "10"
ALARM=$(grep -c '\[对账\] 修复' "$RECON_LOG" 2>/dev/null || true); ALARM=${ALARM:-0}
check "打了「修复」ERROR 告警" "$([ "$ALARM" -gt 0 ] && echo yes || echo no)" "yes"
PENDING_ALARM=$(grep -c '待审批但时间已过' "$RECON_LOG" 2>/dev/null || true); PENDING_ALARM=${PENDING_ALARM:-0}
check "打了「待审批超时」ERROR 告警" "$([ "$PENDING_ALARM" -gt 0 ] && echo yes || echo no)" "yes"
echo "  （修复告警：$(grep '\[对账\] 修复' "$RECON_LOG" | tail -1 | sed 's/.*: //' | cut -c1-100)）"

echo
echo "=== 清理（删掉本次造出的数据；8082 上的服务已停，请自行重启）==="
cleanup
LEFTOVER=$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE title='$BATCH_TITLE' OR id IN ($M1,$M2);")
check "库里已无本次数据" "${LEFTOVER:-x}" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
