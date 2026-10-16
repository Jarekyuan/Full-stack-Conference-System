#!/usr/bin/env bash
# ============================================================================
# T4.7 验证：Redis 缓存（会议室注解式 + 日历手写式）
#
# 判据说明（重要）：**不用"耗时对比"**判断命中缓存。
# 本机回环 + 小表，命中与未命中的耗时差在噪声里（`curl -w %{time_total}` 抖一下就把结论翻了）；
# 改用**服务端 SQL 执行次数**（MyBatis 的 `==> Preparing` 日志行）：
# 第二次查询 SQL 计数不增加 = 真的没回源。这条判据是确定性的。
#
# 覆盖：
#   ① 会议室列表/详情：二次查询不回源；写路径（改名）清缓存 → 再查拿到新值
#   ② 日历：二次查询不回源；TTL 落在 5min±60s（随机抖动防雪崩）
#   ③ 空周：TTL 落在 1 分钟（空值短 TTL 防穿透）
#   ④ 事件驱动失效：预约后该周缓存立刻消失 → 再查能看到新会议
#   ⑤ 并发 miss：10 个并发请求同一周，**只回源一次**（single-flight 防击穿）
#
# ⚠️ 本脚本会**接管 8082**（需要控制服务的 SQL 日志），结束时按默认配置重启；
#    前置：基础设施 + gateway/auth 已启动。
# 退出码：0 = 全部断言通过。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/server/meeting-service/target/meeting-service-1.0.0-SNAPSHOT.jar"
LOG=/tmp/t47-meeting.log
PORT=8082
ROOM=6            # 云杉厅（免审批，用来造会议）
PASS=0; FAIL=0
TOOK_OVER=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
redis() { docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli "$@"; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
# SQL 执行次数（MyBatis 在 DEBUG 下按 statement id 打 "==>  Preparing:"）
# ⚠️ 不能写 `grep -c ... || echo 0`：grep -c 在**无匹配**时既打印 "0" 又返回退出码 1，
# `||` 于是再补一个 0 → 返回两行 → 外层 $(( )) 直接语法错误（踩过）。
# ⚠️ 也别用全限定 logger 名匹配：logback 会**按总长度缩写** logger（同一份日志里
#    `c.s.m.m.MeetingMapper.selectConflicts` 是全名，`MeetingMapper.selectByRoomAndRange` 却缩成 `M.…`）。
# ⚠️ 更别写死冒号前的空格数：MyBatis 会把语句名**补齐到固定宽度**再打 `: ==>  Preparing:`，
#    实际是 `selectList     : ==>`（5 个空格）——按单空格匹配会一条都匹配不到（踩过，导致"回源次数"恒为 0，
#    还让"二次查询不回源"变成 0==0 的**假通过**）。
# 结论：用**方法名 + 容忍空白**的匹配（这几个方法名在本项目里唯一）。
sql_count() { local n; n=$(grep -Ec "$1.*==>  Preparing:" "$LOG" 2>/dev/null); echo "${n:-0}"; }
# 在 [min,max] 闭区间内（TTL 断言用）
in_range() { [ "$1" -ge "$2" ] && [ "$1" -le "$3" ] && echo yes || echo "no($1)"; }
cleanup() {
  for m in ${IDS:-}; do
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting WHERE id=$m;" > /dev/null
  done
  # 复原被改名的会议室
  [ -n "${ROOM_OLD_NAME:-}" ] && sql "UPDATE smt_meeting.meeting_room SET name='$ROOM_OLD_NAME' WHERE id=$ROOM;" > /dev/null
  # 本次造出来的缓存键（其余让它自然过期）
  redis --scan --pattern "cache:calendar:${ROOM}:*" 2>/dev/null | tr -d '\r' | while read -r k; do
    [ -n "$k" ] && redis DEL "$k" > /dev/null 2>&1
  done
  if [ "$TOOK_OVER" = "1" ]; then
    kill "$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | head -1)" 2>/dev/null
    sleep 2
    echo "  ↻ 按默认配置重启 meeting-service（日志回 server/logs/meeting.log）"
    (cd "$ROOT/server" && nohup env -u SERVER__PORT java -jar "$JAR" > logs/meeting.log 2>&1 &)
  fi
}
trap cleanup EXIT

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
api() { # api <token> <method> <path> [body]
  local token=$1 method=$2 path=$3 body=${4:-}
  if [ -n "$body" ]; then
    curl -s --noproxy '*' -X "$method" "$BASE$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -s --noproxy '*' -X "$method" "$BASE$path" -H "Authorization: Bearer $token"
  fi
}

echo
echo "=== 0. 接管 8082（需要服务的 SQL 日志做判据；结束时会按默认配置重启）==="
OLD=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ')
[ -n "$OLD" ] && { echo "  停掉既有实例 $OLD"; kill $OLD 2>/dev/null; sleep 3; }
[ -f "$JAR" ] || { echo "❌ 找不到 jar：$JAR"; exit 1; }
(cd "$ROOT/server" && env -u SERVER__PORT java -jar "$JAR" > "$LOG" 2>&1) &
TOOK_OVER=1
UP=""
for i in $(seq 1 60); do
  UP=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/actuator/health" | j "d['status']")
  [ "$UP" = "UP" ] && break
  sleep 1
done
check "meeting-service health UP" "${UP:-未响应}" "UP"
[ "$UP" = "UP" ] || { tail -20 "$LOG"; exit 1; }

U1=$(login user1 user123); ADMIN=$(login admin admin123)
{ [ -n "$U1" ] && [ -n "$ADMIN" ]; } || { echo "登录失败，终止"; exit 1; }
IDS=""

echo
echo "=== 1. 会议室列表：二次查询不回源 ==="
redis --scan --pattern "room:list*" 2>/dev/null | tr -d '\r' | while read -r k; do [ -n "$k" ] && redis DEL "$k" >/dev/null; done
BEFORE=$(sql_count "selectList")
R1=$(api "$U1" GET "/api/rooms?status=0" | j "len(d['data'])")
AFTER1=$(sql_count "selectList")
check "第一次查询回源了（SQL +1）" "$((AFTER1 - BEFORE))" "1"
check "缓存键已写入（room:list::…）" "$(redis --scan --pattern 'room:list*' 2>/dev/null | grep -c .)" "1"
R2=$(api "$U1" GET "/api/rooms?status=0" | j "len(d['data'])")
AFTER2=$(sql_count "selectList")
check "第二次查询**没有**回源（SQL 计数不变）" "$((AFTER2 - AFTER1))" "0"
check "两次返回内容一致" "$R1" "$R2"
check "缓存 TTL 在 30 分钟内（约 1790s）" \
  "$(in_range "$(redis TTL "$(redis --scan --pattern 'room:list*' 2>/dev/null | tr -d '\r' | head -1)")" 1500 1800)" "yes"

echo
echo "=== 2. 会议室详情 + 写路径清缓存 ==="
api "$U1" GET "/api/rooms/$ROOM" > /dev/null
D_BEFORE=$(sql_count "selectDetailById")
api "$U1" GET "/api/rooms/$ROOM" > /dev/null
check "详情二次查询不回源" "$(sql_count "selectDetailById")" "$D_BEFORE"
ROOM_OLD_NAME=$(sql "SELECT name FROM smt_meeting.meeting_room WHERE id=$ROOM;")
ROOM_JSON=$(api "$U1" GET "/api/rooms/$ROOM")
# ⚠️ 用 -c + argv，不用 heredoc：heredoc 占着 stdin，里面 json.load(sys.stdin) 读到的是**脚本本身**
# （第一次跑就是这么把改名请求发成空体、拿到 10001 的）
UPD=$(python3 -c "
import json,sys
d=json.loads(sys.argv[1])['data']
print(json.dumps({'name':'缓存验证厅','groupId':d['groupId'],'capacity':d['capacity'],
 'location':d.get('location'),'images':d.get('images'),'deviceTags':d.get('deviceTags'),'status':d['status']}))
" "$ROOM_JSON")
check "管理员改名成功" "$(api "$ADMIN" PUT "/api/rooms/$ROOM" "$UPD" | j "d['code']")" "0"
check "写路径清空了列表缓存" "$(redis --scan --pattern 'room:list*' 2>/dev/null | grep -c .)" "0"
check "写路径清空了详情缓存" "$(redis EXISTS "room:detail::$ROOM" | tr -d '\r')" "0"
check "再查拿到新名字（不是旧缓存）" "$(api "$U1" GET "/api/rooms/$ROOM" | j "d['data']['name']")" "缓存验证厅"
# 复原
REV=$(printf '%s' "$UPD" | python3 -c "import sys,json;d=json.load(sys.stdin);d['name']='$ROOM_OLD_NAME';print(json.dumps(d))")
api "$ADMIN" PUT "/api/rooms/$ROOM" "$REV" > /dev/null
check "名字已复原（脚本自清理）" "$(api "$U1" GET "/api/rooms/$ROOM" | j "d['data']['name']")" "$ROOM_OLD_NAME"

echo
echo "=== 2.5 先在目标周造一场会（下面要验的是"有数据"的 TTL，空周会走 1 分钟短 TTL）==="
SLOT=$(python3 - << 'PY'
import datetime
d = datetime.date.today() + datetime.timedelta(days=8)
print(f"{d} 09:00:00|{d} 10:00:00")
PY
)
BOOK_A=$(printf '{"title":"T47-缓存验证A","roomId":%s,"startTime":"%s","endTime":"%s"}' "$ROOM" "${SLOT%%|*}" "${SLOT##*|}")
MID_A=$(api "$U1" POST "/api/meetings" "$BOOK_A" | j "d['data']['meetingId']")
[ -n "$MID_A" ] && [ "$MID_A" != "None" ] || { echo "❌ 造会失败"; exit 1; }
IDS="$IDS $MID_A"
check "目标周已有一场会（id=$MID_A）" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id=$MID_A;")" "1"

echo
echo "=== 3. 日历缓存：命中 + 随机 TTL ==="
# 周次**不自己算**：calendar 接口对 week 会归一化，响应里直接带 weekStart。
# （自己算踩过坑：BSD 的 `date -v-mon` 是"把星期设成周一"，会把日期**往后**推到下周一，
#  不是"回到本周一"；再加上重复拼了一次时间，算出个四不像。）
CAL=$(api "$U1" GET "/api/rooms/$ROOM/calendar?week=${SLOT%% *}")
WEEK=$(echo "$CAL" | j "d['data']['weekStart']")
CKEY="cache:calendar:${ROOM}:${WEEK}"
redis DEL "$CKEY" > /dev/null
C_BEFORE=$(sql_count "selectByRoomAndRange")
api "$U1" GET "/api/rooms/$ROOM/calendar?week=$WEEK" > /dev/null
check "第一次查询回源（SQL +1）" "$(( $(sql_count "selectByRoomAndRange") - C_BEFORE ))" "1"
check "缓存键已写入（cache:calendar:{roomId}:{week}）" "$(redis EXISTS "$CKEY" | tr -d '\r')" "1"
TTL=$(redis TTL "$CKEY" | tr -d '\r')
check "TTL 落在 5min±60s（随机抖动防雪崩）" "$(in_range "$TTL" 240 360)" "yes"
C_AFTER=$(sql_count "selectByRoomAndRange")
api "$U1" GET "/api/rooms/$ROOM/calendar?week=$WEEK" > /dev/null
check "第二次查询不回源" "$(sql_count "selectByRoomAndRange")" "$C_AFTER"

echo
echo "=== 4. 空周短 TTL（防穿透）==="
# 找一个 8 天后那周里没有任何会议的会议室：用 room 6 但查一个**远期空周**
EMPTY_WEEK=$(python3 - << 'PY'
import datetime
d = datetime.date.today() + datetime.timedelta(days=25)
print(d - datetime.timedelta(days=d.weekday()))
PY
)
EKEY="cache:calendar:${ROOM}:${EMPTY_WEEK}"
redis DEL "$EKEY" > /dev/null
EMPTY_COUNT=$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE room_id=$ROOM AND status IN (10,20,30) AND start_time >= '$EMPTY_WEEK 00:00:00' AND start_time < DATE_ADD('$EMPTY_WEEK 00:00:00', INTERVAL 7 DAY);")
EMPTY_TTL=$(api "$U1" GET "/api/rooms/$ROOM/calendar?week=$EMPTY_WEEK" > /dev/null; redis TTL "$EKEY" | tr -d '\r')
check "该周确实没有会议（$EMPTY_COUNT 场）——空周判断才有意义" "$EMPTY_COUNT" "0"
check "空周的 TTL 是 1 分钟（远短于有数据的 5 分钟）" "$(in_range "$EMPTY_TTL" 1 60)" "yes"

echo
echo "=== 5. 事件驱动失效：再预约一场后该周缓存立刻消失 ==="
SLOT2=$(python3 -c "
import datetime
d = datetime.date.today() + datetime.timedelta(days=8)
print(f'{d} 11:00:00|{d} 12:00:00')")
BOOK_BODY=$(printf '{"title":"T47-缓存失效验证","roomId":%s,"startTime":"%s","endTime":"%s"}' "$ROOM" "${SLOT2%%|*}" "${SLOT2##*|}")
# 先让该周缓存存在（上一步已建），再预约
check "预约前该周缓存存在" "$(redis EXISTS "$CKEY" | tr -d '\r')" "1"
MID=$(api "$U1" POST "/api/meetings" "$BOOK_BODY" | j "d['data']['meetingId']")
[ -n "$MID" ] && [ "$MID" != "None" ] || { echo "❌ 预约失败"; exit 1; }
IDS="$IDS $MID"
sleep 1   # 失效走 AFTER_COMMIT 事件，给它一拍
check "预约后该周缓存被删除（事件驱动失效）" "$(redis EXISTS "$CKEY" | tr -d '\r')" "0"
check "再查日历能看到这场新会议" \
  "$(api "$U1" GET "/api/rooms/$ROOM/calendar?week=$WEEK" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
ids=[m['meetingId'] for day in d['days'] for m in day['meetings']]
print('$MID' in [str(i) for i in ids])")" "True"

echo
echo "=== 6. 并发 miss：只回源一次（single-flight 防击穿）==="
redis DEL "$CKEY" > /dev/null
CONC_BEFORE=$(sql_count "selectByRoomAndRange")
# ⚠️ 只能 `wait` 这些 curl 的 pid，**不能裸 `wait`**：本脚本第 0 节起了个永不退出的 java 服务，
# 裸 wait 会把脚本永久挂在那里（实测：跑了 10 分钟没有任何输出，服务却一切正常——
# 排查方向极易跑偏到"接口卡住了"）。同一类坑在调度脚本上踩过一次（那次是管道被子进程抓住）。
PIDS=""
for i in $(seq 1 10); do
  api "$U1" GET "/api/rooms/$ROOM/calendar?week=$WEEK" > /dev/null &
  PIDS="$PIDS $!"
done
wait $PIDS
check "10 个并发请求只回源 1 次" "$(( $(sql_count "selectByRoomAndRange") - CONC_BEFORE ))" "1"

echo
echo "=== 7. 清理与残留 ==="
cleanup
trap - EXIT
LEFT=0
for m in $IDS; do LEFT=$(( LEFT + $(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id=$m;") )); done
check "库里已无本次数据" "$LEFT" "0"
check "会议室名字已复原" "$(sql "SELECT name FROM smt_meeting.meeting_room WHERE id=$ROOM;")" "$ROOM_OLD_NAME"
check "本次造的日历缓存已清" "$(redis --scan --pattern "cache:calendar:${ROOM}:*" 2>/dev/null | grep -c .)" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
