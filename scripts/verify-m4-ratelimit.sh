#!/usr/bin/env bash
# ============================================================================
# T4.8 验证：预约限流（Redis ZSET 滑动窗口 + Nacos 动态阈值）
#
# 覆盖：
#   ① 连发 12 次 → 前 10 次放行、第 11/12 次 10005（docs/05 T4.8 验收①）
#   ② **Nacos 改阈值 → 不重启立即生效**（改成 3 后，第 4 次就被拦）
#   ③ 阈值 0 = **关闭限流**（演示/压测开关；并发脚本就是靠它）
#   ④ Redis 里是 ZSET 且成员数/ TTL 正确（滑动窗口的物理形态）
#
# ⚠️ "Redis 挂了要降级放行"这条**不在本脚本里验**：本机 Redis 是共享的（auth 的 refreshToken、
#    幂等键、缓存都在上面），停掉它会同时打断其他服务；而且请求会先在幂等切面失败、
#    根本走不到限流切面——现象指不到结论。它由单元测试 `RateLimitAspectTest` 确定性覆盖。
#
# 前置：基础设施 + gateway/auth/meeting 已启动（服务需含 T4.8 的新 jar）。
# 退出码：0 = 全部断言通过。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
MYSQL='docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e'
NACOS_CFG_URL="${NACOS_CFG_URL:-http://127.0.0.1:8848/nacos/v1/cs/configs}"
ROOM=6
PASS=0; FAIL=0
CFG_BAK=/tmp/ratelimit-verify-backup.yml
CFG_TMP=/tmp/ratelimit-verify-new.yml

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
sql() { eval "$MYSQL \"$1\"" 2>/dev/null | grep -v "Using a password"; }
redis() { docker exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-redis123}" smt-redis redis-cli "$@"; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
# 发布阈值到 Nacos 配置中心（用**文件**提交内容：命令行多行内容容易被 shell 拆坏，踩过）
publish_limit() { # publish_limit <per-minute>
  printf 'meeting:\n  ratelimit:\n    reserve-per-minute: %s\n    window-seconds: 60\n' "$1" > "$CFG_TMP"
  curl -s --noproxy '*' -X POST "$NACOS_CFG_URL" -d "dataId=meeting-service.yml" \
    -d "group=DEFAULT_GROUP" -d "type=yaml" --data-urlencode "content@$CFG_TMP" > /dev/null
}
cleanup() {
  for m in ${IDS:-}; do
    sql "DELETE FROM smt_meeting.notification WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=$m;
         DELETE FROM smt_meeting.meeting WHERE id=$m;" > /dev/null
  done
  redis --scan --pattern "limit:reserve:*" 2>/dev/null | tr -d '\r' | while read -r k; do
    [ -n "$k" ] && redis DEL "$k" > /dev/null 2>&1
  done
  # 恢复原配置（没有就恢复成默认 10）
  if [ -s "$CFG_BAK" ] && ! grep -q "config data not exist" "$CFG_BAK"; then
    curl -s --noproxy '*' -X POST "$NACOS_CFG_URL" -d "dataId=meeting-service.yml" \
      -d "group=DEFAULT_GROUP" -d "type=yaml" --data-urlencode "content@$CFG_BAK" > /dev/null 2>&1
  else
    publish_limit 10
  fi
  rm -f "$CFG_TMP"
}
trap cleanup EXIT

login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" | j "d['data']['accessToken']"
}
# 发一次预约请求，回显业务 code（每次都用**新的幂等 token**：否则会被幂等切面挡在限流之前）
book_code() { # book_code <token> <start> <end>
  local body; body=$(printf '{"title":"T48-限流验证","roomId":%s,"startTime":"%s","endTime":"%s"}' "$ROOM" "$2" "$3")
  curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body" | j "d['code']"
}
burst() { # burst <token> <次数> <start> <end> → 空格分隔的 code 列表
  local codes="" i
  for i in $(seq 1 "$2"); do codes="$codes $(book_code "$1" "$3" "$4")"; done
  echo "$codes"
}
count_of() { echo $2 | tr ' ' '\n' | grep -c "^$1$"; }

echo
echo "=== 0. 备份当前 Nacos 配置 ==="
curl -s --noproxy '*' "$NACOS_CFG_URL?dataId=meeting-service.yml&group=DEFAULT_GROUP" > "$CFG_BAK"
echo "  已备份（退出时恢复）"

U2=$(login user2 user123); U3=$(login user3 user123)
{ [ -n "$U2" ] && [ -n "$U3" ]; } || { echo "登录失败，终止"; exit 1; }
U2_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user2';")
U3_ID=$(sql "SELECT id FROM smt_meeting.sys_user WHERE username='user3';")
IDS=""

# 一个空闲时段（第一发会真的建成会议，其余同 slot 的会拿 40001——**40001 也算"进了业务"**，
# 一样会被限流计数，所以整段只造出 1 场会议，清理成本最低）
SLOT=$(python3 -c "
import datetime
d = datetime.date.today() + datetime.timedelta(days=12)
print(f'{d} 09:00:00|{d} 10:00:00')")
START="${SLOT%%|*}"; END="${SLOT##*|}"

echo
echo "=== 1. 阈值 10：连发 12 次 → 第 11、12 次被拦 ==="
publish_limit 10; sleep 5   # 等 Nacos 长轮询推给服务
CODES=$(burst "$U2" 12 "$START" "$END")
echo "  codes:$CODES"
check "前 10 次没有一次是 10005" "$(echo $CODES | tr ' ' '\n' | head -10 | grep -c '^10005$')" "0"
check "第 11 次 = 10005" "$(echo $CODES | tr ' ' '\n' | sed -n '11p')" "10005"
check "第 12 次 = 10005" "$(echo $CODES | tr ' ' '\n' | sed -n '12p')" "10005"
check "窗口内只有 1 场会议建成（同 slot 其余是 40001，不重复建会）" \
  "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE room_id=$ROOM AND start_time='$START';")" "1"
MID=$(sql "SELECT id FROM smt_meeting.meeting WHERE room_id=$ROOM AND start_time='$START';")
[ -n "$MID" ] && IDS="$IDS $MID"

echo
echo "=== 2. 滑动窗口的物理形态（Redis ZSET）==="
LKEY="limit:reserve:${U2_ID}"
check "键存在" "$(redis EXISTS "$LKEY" | tr -d '\r')" "1"
check "类型是 zset" "$(redis TYPE "$LKEY" | tr -d '\r')" "zset"
# 被拦的两次**不写入**，所以成员数 = 放行次数 = 10
check "成员数 = 放行次数 10（被拦的不计数）" "$(redis ZCARD "$LKEY" | tr -d '\r')" "10"
TTL=$(redis TTL "$LKEY" | tr -d '\r')
check "TTL 在 (0, 60] 秒内（窗口续期生效）" "$([ "$TTL" -gt 0 ] && [ "$TTL" -le 60 ] && echo yes || echo "no($TTL)")" "yes"

echo
echo "=== 3. Nacos 改阈值 → **不重启**立即生效（3/分钟）==="
publish_limit 3; sleep 5
CODES3=$(burst "$U3" 5 "$START" "$END")
echo "  codes:$CODES3"
check "第 1-3 次放行（无 10005）" "$(echo $CODES3 | tr ' ' '\n' | head -3 | grep -c '^10005$')" "0"
check "第 4 次 = 10005（新阈值已生效，服务没重启过）" "$(echo $CODES3 | tr ' ' '\n' | sed -n '4p')" "10005"
check "第 5 次 = 10005" "$(echo $CODES3 | tr ' ' '\n' | sed -n '5p')" "10005"

echo
echo "=== 4. 阈值 0 = 关闭限流（演示/压测开关）==="
publish_limit 0; sleep 5
CODES0=$(burst "$U3" 6 "$START" "$END")
check "连发 6 次全部放行（一次 10005 都没有）" "$(echo $CODES0 | tr ' ' '\n' | grep -c '^10005$')" "0"

echo
echo "=== 5. 恢复默认阈值并读回确认 ==="
publish_limit 10; sleep 1
check "配置中心里已恢复为 10" \
  "$(curl -s --noproxy '*' "$NACOS_CFG_URL?dataId=meeting-service.yml&group=DEFAULT_GROUP" | grep -c 'reserve-per-minute: 10')" "1"

echo
echo "=== 6. 清理与残留 ==="
cleanup
ID_LIST=$(echo $IDS | tr ' ' ',')
check "库里已无本次数据" "$(sql "SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN ($ID_LIST);")" "0"
check "限流键已清" "$(redis --scan --pattern 'limit:reserve:*' 2>/dev/null | grep -c .)" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
