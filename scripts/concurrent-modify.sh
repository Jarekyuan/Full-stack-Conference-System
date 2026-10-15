#!/usr/bin/env bash
# ============================================================================
# 改期 vs 新建 并发竞态验证脚本 —— 演示 / 回归两用
#
# 背景：create() 有分布式锁 + 锁内复检，但 update()（改期）此前**不经过那把锁**——
#      "把 A 改期到某时段"与"新建 C 到同一时段"会各自读到旧快照、各自通过冲突检测，
#      同一时段落下两场会议（双重预订）。修法：改期与新建共用同一把房间锁，
#      并在事务内加会议室行锁（SELECT ... FOR UPDATE）作数据库层兜底。
#
# 作用：每轮并发执行「改期 A → 目标时段」与「新建 C → 同一目标时段」，断言：
#      ① 目标时段最终**恰好 1 场**会议（核心不变量：无双重预订）
#      ② 并发请求中至少一个成功（保证本轮真的产生了占用，而非空跑）
#
# 用法：
#   bash scripts/concurrent-modify.sh              # 默认 3 个新建并发 × 5 轮
#   bash scripts/concurrent-modify.sh 10           # 10 轮
#
# 环境变量（可选）：
#   BASE_URL          网关地址，默认 http://localhost:8080
#   USERNAME/PASSWORD 登录账号，默认 user1/user123
#   ROOM_ID           会议室ID，默认 1（青竹厅，buffer=0，冲突数=占用数便于计数）
#   N_CREATE          每轮并发新建数，默认 3
#
# 退出码：0 = 全部轮次断言通过；1 = 出现双重预订（可用于 CI）
# 依赖：curl、python3、uuidgen（macOS 自带）
# ============================================================================
set -u

REPEAT="${1:-5}"

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-user1}"
PASSWORD="${PASSWORD:-user123}"
ROOM_ID="${ROOM_ID:-1}"
N_CREATE="${N_CREATE:-3}"

RESULT_FILE="/tmp/concurrent-modify-results.txt"

TOKEN=$(curl -s --noproxy '*' -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)

if [[ -z "${TOKEN:-}" ]]; then
  echo "[ERROR] 登录失败：请确认网关(${BASE_URL})与 auth-service 已启动，账号 $USERNAME 存在"
  exit 1
fi

echo "=============================================="
echo "改期竞态验证：1 改期 + ${N_CREATE} 新建并发 × ${REPEAT} 轮（roomId=${ROOM_ID}）"
echo "期望：每轮目标时段最终恰好 1 场会议（无双重预订）"
echo "=============================================="

# ---------------------------------------------------------------------------
# 时段占用数（availability 的 conflicts 即该时段的占用会议；room 1 buffer=0，数量等价）
# ---------------------------------------------------------------------------
occupants() {
  local start_time=$1 end_time=$2
  curl -s --noproxy '*' -X POST "$BASE_URL/api/rooms/availability" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"roomIds\":[${ROOM_ID}],\"startTime\":\"${start_time}\",\"endTime\":\"${end_time}\"}" \
    | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']['items'][0]['conflicts']))" 2>/dev/null
}

# ---------------------------------------------------------------------------
# 挑一天：基准时段(09-10)与目标时段(15-16)都空闲
# （★ 保证脚本可重复运行——时段被占会导致"全员冲突"误报，与 concurrent-reserve.sh 同理；
#   刻意避开 10:00-11:00 与 14:00-15:00：前者是 concurrent-reserve.sh 的历史测试槽，早被占满）
# 窗口上限 +28 天：会议室默认最多提前 30 天预约（R-5），超出会被 30004 拒绝
# ---------------------------------------------------------------------------
pick_day() {
  local offset d base_s base_e target_s target_e
  for offset in $(seq 0 21); do
    d=$(date -v+"$((7 + offset))"d +%Y-%m-%d)
    base_s="${d} 09:00:00"; base_e="${d} 10:00:00"
    target_s="${d} 15:00:00"; target_e="${d} 16:00:00"
    if [[ "$(occupants "${base_s}" "${base_e}")" == "0" && "$(occupants "${target_s}" "${target_e}")" == "0" ]]; then
      echo "${base_s}|${base_e}|${target_s}|${target_e}"
      return 0
    fi
  done
  echo "[ERROR] 未来 7~28 天候选时段均被占用，请清理测试数据或调整 pick_day" >&2
  return 1
}

# ---------------------------------------------------------------------------
# 取消某时段上的会议（脚本自我清理：释放槽位，保证脚本可重复运行）
# ---------------------------------------------------------------------------
cancel_slot() {
  local start_time=$1 end_time=$2 ids id
  ids=$(curl -s --noproxy '*' -X POST "$BASE_URL/api/rooms/availability" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"roomIds\":[${ROOM_ID}],\"startTime\":\"${start_time}\",\"endTime\":\"${end_time}\"}" \
    | python3 -c "import sys,json; print(' '.join(str(c['meetingId']) for c in json.load(sys.stdin)['data']['items'][0]['conflicts']))" 2>/dev/null)
  for id in $ids; do
    curl -s --noproxy '*' -X POST "$BASE_URL/api/meetings/${id}/cancel" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"reason":"并发脚本清理"}' > /dev/null 2>&1
  done
}

# 新建会议，回显 id（失败回显空）
create_meeting() {
  local title=$1 start_time=$2 end_time=$3
  curl -s --noproxy '*' -X POST "$BASE_URL/api/meetings" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -H "X-Idempotency-Token: $(uuidgen)" \
    -d "{\"title\":\"${title}\",\"roomId\":${ROOM_ID},\"startTime\":\"${start_time}\",\"endTime\":\"${end_time}\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['meetingId'] if d['code']==0 else '')" 2>/dev/null
}

# 会议乐观锁版本号（PUT 需原样带回）
meeting_version() {
  curl -s --noproxy '*' "$BASE_URL/api/meetings/$1" -H "Authorization: Bearer $TOKEN" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['version'])" 2>/dev/null
}

# ---------------------------------------------------------------------------
# 执行一轮
# ---------------------------------------------------------------------------
run_round() {
  local round=$1 slot
  if ! slot=$(pick_day); then
    echo ""
    echo "第 ${round}/${REPEAT} 轮：${slot}"
    return 1
  fi
  local base_s base_e target_s target_e
  IFS='|' read -r base_s base_e target_s target_e <<< "$slot"

  # 先造一场"待改期"的会议 A（位于基准时段）
  local meeting_id version
  meeting_id=$(create_meeting "改期竞态A-R${round}" "$base_s" "$base_e")
  if [[ -z "${meeting_id}" ]]; then
    echo ""
    echo "第 ${round}/${REPEAT} 轮  [${base_s}] ❌ 前置失败：会议 A 未创建成功（见服务端日志）"
    cancel_slot "$base_s" "$base_e"
    return 1
  fi
  version=$(meeting_version "$meeting_id")

  # ★ 并发：1 个改期（A → 目标时段） + N 个新建（目标时段）
  # 结果打标签（modify=/create=），既便于统计也便于看清"谁赢了这把锁"
  : > "$RESULT_FILE"
  (
    curl -s --noproxy '*' -X PUT "$BASE_URL/api/meetings/${meeting_id}" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "{\"title\":\"改期竞态A-R${round}\",\"roomId\":${ROOM_ID},\"startTime\":\"${target_s}\",\"endTime\":\"${target_e}\",\"version\":${version}}" \
      | python3 -c "import sys,json; print('modify=' + str(json.load(sys.stdin)['code']))" >> "$RESULT_FILE" 2>/dev/null
  ) &
  local i
  for i in $(seq 1 "$N_CREATE"); do
    (
      curl -s --noproxy '*' -X POST "$BASE_URL/api/meetings" \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -H "X-Idempotency-Token: $(uuidgen)" \
        -d "{\"title\":\"新建竞态C${i}-R${round}\",\"roomId\":${ROOM_ID},\"startTime\":\"${target_s}\",\"endTime\":\"${target_e}\"}" \
        | python3 -c "import sys,json; print('create=' + str(json.load(sys.stdin)['code']))" >> "$RESULT_FILE" 2>/dev/null
    ) &
  done
  wait

  # 统计并发结果 + 复查目标时段占用数
  local success conflict busy other winner final
  read -r success conflict busy other winner <<< "$(python3 - "$RESULT_FILE" "$((N_CREATE + 1))" <<'PY'
import sys
lines = [line.strip() for line in open(sys.argv[1]) if '=' in line]
total = int(sys.argv[2])
codes = [line.split('=', 1)[1] for line in lines]
s, c, b = codes.count('0'), codes.count('40001'), codes.count('10007')
won = [line.split('=', 1)[0] for line in lines if line.endswith('=0')]
print(f"{s} {c} {b} {total - s - c - b} {'+'.join(won) if won else '无'}")
PY
)"
  final=$(occupants "$target_s" "$target_e")

  echo ""
  echo "第 ${round}/${REPEAT} 轮  [${target_s}]"
  echo "  并发结果：成功=$success  冲突40001=$conflict  繁忙10007=$busy  其他=$other"
  echo "  胜出方：$winner"
  echo "  目标时段最终占用：${final} 场（应为 1）"

  # 清理本轮产生的会议（取消后不再占用时段），保证脚本可重复运行
  cancel_slot "$base_s" "$base_e"
  cancel_slot "$target_s" "$target_e"
  echo "  （已取消本轮会议，槽位释放）"

  if [[ "$final" != "1" ]]; then
    echo "  ❌ 断言失败：目标时段落下 ${final} 场会议 —— 双重预订复现！"
    return 1
  fi
  if [[ "$success" -lt 1 ]]; then
    echo "  ❌ 断言失败：本轮无任何请求成功，未真正产生占用"
    return 1
  fi
  echo "  ✅ 断言通过：恰好 1 场会议占用（改期与新建已互斥）"
  return 0
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
PASSED=0
FAILED=0
for round in $(seq 1 "$REPEAT"); do
  if run_round "$round"; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
  fi
done

echo ""
echo "=============================================="
echo "总计：${PASSED} 轮通过 / ${FAILED} 轮失败（共 ${REPEAT} 轮）"
if [[ "$FAILED" -eq 0 ]]; then
  echo "✅ 全部通过——改期与新建不会双重预订"
  exit 0
else
  echo "❌ 存在失败轮次——检查并发防护是否被绕过"
  exit 1
fi
