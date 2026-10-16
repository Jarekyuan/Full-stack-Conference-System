#!/usr/bin/env bash
# ============================================================================
# 并发预约验证脚本（T2.7）—— 演示 / 回归两用
#
# 作用：N 个独立客户端并发抢同一会议室同一时段，断言"恰好 1 个成功、其余冲突"
#      （验证 Redisson 分布式锁 + 锁内冲突检测的并发防护，docs/01 R-8）
#
# 用法：
#   bash scripts/concurrent-reserve.sh              # 默认 5 并发 × 1 轮
#   bash scripts/concurrent-reserve.sh 10           # 10 并发 × 1 轮
#   bash scripts/concurrent-reserve.sh 5 --repeat 10 # 5 并发 × 10 轮（回归）
#
# 环境变量（可选）：
#   BASE_URL          网关地址，默认 http://localhost:8080
#   USERNAME/PASSWORD 登录账号，默认 user1/user123
#   ROOM_ID           会议室ID，默认 1（青竹厅，免审批）
#   EXPECTED_SUCCESS  期望成功数，默认 1（需审批会议室因 R-4 会 >1，可调大）
#
# 退出码：0 = 全部轮次断言通过；1 = 存在失败轮次（可用于 CI）
# 依赖：curl、python3、uuidgen（macOS 自带）
# ============================================================================
set -u

# 限流开关：本脚本单轮要发几十次预约请求、全是同一个用户，必然撞限流——
# 那验的就不是锁了（失败信息还恰好指向锁）。通过 Nacos 临时关掉，退出时自动恢复。
# 实现抽在 scripts/lib/ratelimit.sh（两个并发脚本共用一份；含"读回确认"与多信号恢复，见该文件注释）
source "$(dirname "$0")/lib/ratelimit.sh"
ratelimit_disable_for_script



CONCURRENCY="${1:-5}"
shift 2>/dev/null || true
REPEAT=1
if [[ "${1:-}" == "--repeat" ]]; then
  REPEAT="${2:-1}"
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-user1}"
PASSWORD="${PASSWORD:-user123}"
ROOM_ID="${ROOM_ID:-1}"
EXPECTED_SUCCESS="${EXPECTED_SUCCESS:-1}"

RESULT_FILE="/tmp/concurrent-reserve-results.txt"

# ---------------------------------------------------------------------------
# 登录拿 token
# ---------------------------------------------------------------------------
TOKEN=$(curl -s --noproxy '*' -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)

if [[ -z "${TOKEN:-}" ]]; then
  echo "[ERROR] 登录失败：请确认网关(${BASE_URL})与 auth-service 已启动，账号 $USERNAME 存在"
  exit 1
fi

echo "=============================================="
echo "并发预约验证：${CONCURRENCY} 并发 × ${REPEAT} 轮（会议室 roomId=${ROOM_ID}）"
echo "期望：每轮恰好 ${EXPECTED_SUCCESS} 个成功，其余为冲突(40001)"
echo "=============================================="

# ---------------------------------------------------------------------------
# 时段选择（★ 关键：若时段已被占，会出现"全员冲突"误报）
# 策略：从"7 天后 + 轮次偏移"起，用 availability 接口**预检**是否空闲，
#     被占则日期后推再试（最多 10 次）——保证脚本可重复运行、结果可预期
# 注：date -v 为 macOS(BSD) 语法；Linux 请改用 date -d "+N days"
# ---------------------------------------------------------------------------
is_available() {
  local start_time=$1 end_time=$2
  curl -s --noproxy '*' -X POST "$BASE_URL/api/rooms/availability" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"roomIds\":[${ROOM_ID}],\"startTime\":\"${start_time}\",\"endTime\":\"${end_time}\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['items'][0]['available'])" 2>/dev/null
}

pick_slot() {
  local round=$1
  local offset d start_time end_time
  for offset in $(seq 0 10); do
    d=$(date -v+"$((7 + round + offset))"d +%Y-%m-%d)
    start_time="${d} 10:00:00"
    end_time="${d} 11:00:00"
    if [[ "$(is_available "$start_time" "$end_time")" == "True" ]]; then
      echo "${start_time}|${end_time}"
      return 0
    fi
  done
  echo "[ERROR] 连续 10 天候选时段均被占用，请检查数据或调整 pick_slot" >&2
  return 1
}

# ---------------------------------------------------------------------------
# 取消某时段上的会议（脚本自我清理：释放槽位，保证脚本可重复运行）
#
# 2026-10-15 补：本脚本原先**不清理**每轮胜出的那场会议，跑 10 轮就在该会议室
# 留下 10 场 status=20 的"并发验证-R*"，几轮回归之后候选时段全被占满，
# 脚本就再也跑不动了（实测：青竹厅 10-22~10-29 每天 10:00 都被历史残留占着，
# 报"连续 10 天候选时段均被占用"）。同一套 cancel_slot 与 concurrent-modify.sh 保持一致。
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

# ---------------------------------------------------------------------------
# 执行一轮
# ---------------------------------------------------------------------------
run_round() {
  local round=$1
  local slot start_time end_time
  if ! slot=$(pick_slot "$round"); then
    echo ""
    echo "第 ${round}/${REPEAT} 轮：${slot}"
    return 1
  fi
  start_time="${slot%%|*}"
  end_time="${slot##*|}"

  : > "$RESULT_FILE"

  local i
  for i in $(seq 1 "$CONCURRENCY"); do
    (
      curl -s --noproxy '*' -X POST "$BASE_URL/api/meetings" \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -H "X-Idempotency-Token: $(uuidgen)" \
        -d "{\"title\":\"并发验证-R${round}-${i}\",\"roomId\":${ROOM_ID},\"startTime\":\"${start_time}\",\"endTime\":\"${end_time}\"}" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null >> "$RESULT_FILE"
    ) &
  done
  wait

  local success conflict busy other
  # 用 python 一次读取统计（避开 grep -c 无匹配时"输出 0 且退出码 1"与 `|| echo 0` 叠加产生两行的坑）
  read -r success conflict busy other <<< "$(python3 - "$RESULT_FILE" "$CONCURRENCY" <<'PY'
import sys
codes = [line.strip() for line in open(sys.argv[1]) if line.strip()]
total = int(sys.argv[2])
s, c, b = codes.count('0'), codes.count('40001'), codes.count('10007')
print(f"{s} {c} {b} {total - s - c - b}")
PY
)"

  echo ""
  echo "第 ${round}/${REPEAT} 轮  [${start_time}]"
  echo "  成功=$success  冲突=$conflict  繁忙=$busy  其他=$other"

  # 自清理：释放本轮占下的槽位（否则跑 N 轮就在该会议室留 N 场，把候选时段耗干）
  cancel_slot "$start_time" "$end_time"

  if [[ "$success" -eq "$EXPECTED_SUCCESS" ]]; then
    echo "  ✅ 断言通过：恰好 ${EXPECTED_SUCCESS} 个成功（并发防护生效）"
    return 0
  else
    echo "  ❌ 断言失败：期望成功 ${EXPECTED_SUCCESS} 个，实际 ${success} 个（本轮残留已清理）"
    return 1
  fi
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
  echo "✅ 全部通过——并发防护稳定"
  exit 0
else
  echo "❌ 存在失败轮次"
  exit 1
fi
