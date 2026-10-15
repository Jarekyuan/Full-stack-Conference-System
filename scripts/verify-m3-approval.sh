#!/usr/bin/env bash
# ============================================================================
# M3 审批链路验证（T3.1 待办列表 / T3.2 通过复检 / T3.3 拒绝）
#
# 全程走 HTTP 接口，不用 SQL 造数据 —— "待审批不占时段 + 审批时复检"这条设计
# 本身就允许用接口构造出"复检冲突"：同一时段先提交两场待审批（都不占时段），
# 批掉一场之后，另一场再批就会撞上复检。
#
# 会议都建在**红枫厅（roomId=5，need_approval=1，buffer=15，提前 30 天）**，
# 跑完自动取消，可重复运行。退出码：0 = 全部断言通过。
#
# ⚠️ 写法约定（踩过的坑）：所有 JSON body 一律先用 printf 装进变量，再 --data "$body"。
#    别写 `check "x" "$(curl ... -d "{\"a\":1}" ...)" "y"` —— 命令行里嵌套的双引号+反斜杠
#    会把参数拆开（实测 argc=5），于是"两个空串相等"让断言假通过。
# ============================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
ROOM="${APPROVAL_ROOM:-5}"
PASS=0; FAIL=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
check() {
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}
login() {
  local body; body=$(printf '{"username":"%s","password":"%s"}' "$1" "$2")
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$body" \
    | j "d['data']['accessToken']"
}
availability() { # availability <token> <start> <end> → True/False
  local body; body=$(printf '{"roomIds":[%s],"startTime":"%s","endTime":"%s"}' "$ROOM" "$2" "$3")
  curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d "$body" | j "d['data']['items'][0]['available']"
}
book() { # book <token> <title> <start> <end> → meetingId；创建失败立刻炸掉（别让它退化成后续一串 10004）
  local body resp mid code
  body=$(printf '{"title":"%s","roomId":%s,"startTime":"%s","endTime":"%s","remark":"M3 审批验证用"}' \
    "$2" "$ROOM" "$3" "$4")
  resp=$(curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "X-Idempotency-Token: $(uuidgen)" -d "$body")
  code=$(echo "$resp" | j "d['code']")
  mid=$(echo "$resp" | j "d['data']['meetingId']")
  if [ "$code" != "0" ] || [ -z "$mid" ]; then
    echo "ERROR: 创建会议失败 code=$code resp=$(echo "$resp" | head -c 200)" >&2
    return 1
  fi
  echo "$mid"
}
pass_it() { # pass_it <token> <meetingId> [comment]
  local body; body=$(printf '{"comment":"%s"}' "${3:-同意}")
  curl -s --noproxy '*' -X POST "$BASE/api/approvals/$2/pass" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d "$body"
}
reject_it() { # reject_it <token> <meetingId> [comment]
  local body; body=$(printf '{"comment":"%s"}' "${3:-}")
  curl -s --noproxy '*' -X POST "$BASE/api/approvals/$2/reject" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d "$body"
}
status_of() { curl -s --noproxy '*' "$BASE/api/meetings/$2" -H "Authorization: Bearer $1" | j "d['data']['status']"; }
cancel() {
  local body='{"reason":"M3 验证脚本清理"}'
  curl -s --noproxy '*' -o /dev/null -X POST "$BASE/api/meetings/$2/cancel" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d "$body"
}
# 找一个空闲时段且落在提前预约窗口内（默认 30 天）：从 tomorrow 起逐日试
free_slot() { # free_slot <起始天数偏移>
  local off d start end
  for off in $(seq "$1" $(( $1 + 25 ))); do
    d=$(date -v+"${off}"d +%Y-%m-%d)
    start="${d} 10:00:00"; end="${d} 11:00:00"
    if [ "$(availability "$U1" "$start" "$end")" = "True" ]; then echo "${start}|${end}"; return 0; fi
  done
  echo "ERROR: 找不到空闲时段" >&2; return 1
}

U1=$(login user1 user123); U2=$(login user2 user123)
AP1=$(login approver1 user123); AP2=$(login approver2 user123)
[ -n "$U1" ] && [ -n "$AP1" ] || { echo "登录失败，终止"; exit 1; }
CREATED_U1=""   # user1 建的
CREATED_U2=""   # user2 建的（**必须用 user2 自己取消**：归属校验只认组织人，用 user1 会 40004 静默失败）
cleanup() {
  for m in $CREATED_U1; do cancel "$U1" "$m"; done
  for m in $CREATED_U2; do cancel "$U2" "$m"; done
}
trap cleanup EXIT

echo
echo "=== T3.1 待办列表与权限 ==="
check "普通用户访问待办 → 403" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/approvals/pending" -H "Authorization: Bearer $U1")" "403"

SLOT=$(free_slot 1) || exit 1
S="${SLOT%%|*}"; E="${SLOT##*|}"
echo "  （使用时段 ${S} ~ ${E}，红枫厅需审批）"

M1=$(book "$U1" "M3审批-复检A" "$S" "$E") || exit 1; CREATED_U1="$CREATED_U1 $M1"
check "需审批会议室创建成功" "$([ -n "$M1" ] && echo ok)" "ok"
check "创建后状态 = 10 待审批" "$(status_of "$U1" "$M1")" "10"
check "待审批**不占用**时段（availability 仍可用）" "$(availability "$U1" "$S" "$E")" "True"

PENDING=$(curl -s --noproxy '*' "$BASE/api/approvals/pending?page=1&size=50" -H "Authorization: Bearer $AP1")
check "approver1 待办里能看到它" "$(echo "$PENDING" | j "any(str(x['meetingId'])=='$M1' for x in d['data']['list'])")" "True"
check "待办项带用途备注 remark（审批人判断依据）" \
  "$(echo "$PENDING" | j "[x['remark'] for x in d['data']['list'] if str(x['meetingId'])=='$M1'][0]")" "M3 审批验证用"
check "待办项带组织人姓名（JOIN 直出）" \
  "$(echo "$PENDING" | j "[x['organiserName'] for x in d['data']['list'] if str(x['meetingId'])=='$M1'][0]")" "张三"
check "待办按会议开始时间升序" \
  "$(echo "$PENDING" | j "all(d['data']['list'][i]['startTime'] <= d['data']['list'][i+1]['startTime'] for i in range(len(d['data']['list'])-1))")" "True"

echo
echo "=== T3.2 复检：同一时段两场待审批，批掉一场后另一场必被拦 ==="
M2=$(book "$U2" "M3审批-复检B" "$S" "$E") || exit 1; CREATED_U2="$CREATED_U2 $M2"
check "第二场同时段待审批也能提交（10 不占时段）" "$(status_of "$U2" "$M2")" "10"
check "approver1 批准 M2 → 20" "$(pass_it "$AP1" "$M2" | j "d['data']['status']")" "20"
check "批准后时段**变为占用**" "$(availability "$U1" "$S" "$E")" "False"
CONFLICT=$(pass_it "$AP1" "$M1")
check "再批 M1 → 50001（复检拦下）" "$(echo "$CONFLICT" | j "d['code']")" "50001"
check "50001 的 data.conflicts 指向占用方 M2" "$(echo "$CONFLICT" | j "str(d['data']['conflicts'][0]['meetingId'])")" "$M2"
check "被拦下后 M1 仍是 10（没被误改）" "$(status_of "$U1" "$M1")" "10"
check "重复审批 M2 → 50002（已被处理）" "$(pass_it "$AP2" "$M2" | j "d['code']")" "50002"

echo
echo "=== T3.2 并发双人同审：恰好一个成功 ==="
SLOT2=$(free_slot 3) || exit 1
S2="${SLOT2%%|*}"; E2="${SLOT2##*|}"
M3=$(book "$U1" "M3审批-并发" "$S2" "$E2") || exit 1; CREATED_U1="$CREATED_U1 $M3"
R1=$(mktemp); R2=$(mktemp)
( pass_it "$AP1" "$M3" A | j "d['code']" > "$R1" ) &
( pass_it "$AP2" "$M3" B | j "d['code']" > "$R2" ) &
wait
C1=$(cat "$R1"); C2=$(cat "$R2"); rm -f "$R1" "$R2"
check "两人同时审 → 结果集 {0,50002}" "$(printf '%s\n%s\n' "$C1" "$C2" | sort | tr '\n' ',' | sed 's/,$//')" "0,50002"
check "会议最终状态 20（只迁了一次）" "$(status_of "$U1" "$M3")" "20"

echo
echo "=== T3.3 拒绝 ==="
SLOT3=$(free_slot 5) || exit 1
S3="${SLOT3%%|*}"; E3="${SLOT3##*|}"
M4=$(book "$U1" "M3审批-拒绝" "$S3" "$E3") || exit 1; CREATED_U1="$CREATED_U1 $M4"
check "无 comment 拒绝 → 10001" "$(reject_it "$AP1" "$M4" | j "d['code']")" "10001"
check "纯空白 comment 也拦（10001）" "$(reject_it "$AP1" "$M4" "   " | j "d['code']")" "10001"
check "带理由拒绝 → 60" "$(reject_it "$AP1" "$M4" "该时段已安排客户接待，请改期" | j "d['data']['status']")" "60"
MOD_BODY=$(printf '{"title":"x","roomId":%s,"startTime":"%s","endTime":"%s","version":0}' "$ROOM" "$S3" "$E3")
check "被拒后不可再修改（R-11 终态 → 40002）" \
  "$(curl -s --noproxy '*' -X PUT "$BASE/api/meetings/$M4" -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' -d "$MOD_BODY" | j "d['code']")" "40002"
HIST=$(curl -s --noproxy '*' "$BASE/api/approvals/history?page=1&size=50" -H "Authorization: Bearer $AP1")
check "已办列表含这条 REJECT 记录" \
  "$(echo "$HIST" | j "any(str(x['meetingId'])=='$M4' and x['action']=='REJECT' for x in d['data']['list'])")" "True"
check "已办列表含之前的 PASS 记录（M2）" \
  "$(echo "$HIST" | j "any(str(x['meetingId'])=='$M2' and x['action']=='PASS' for x in d['data']['list'])")" "True"
check "已办记录带审批意见" \
  "$(echo "$HIST" | j "[x['comment'] for x in d['data']['list'] if str(x['meetingId'])=='$M4'][0]")" "该时段已安排客户接待，请改期"
check "approver2 的已办箱看不到 approver1 的处理" \
  "$(curl -s --noproxy '*' "$BASE/api/approvals/history?page=1&size=50" -H "Authorization: Bearer $AP2" \
     | j "any(str(x['meetingId'])=='$M4' for x in d['data']['list'])")" "False"

echo
echo "=== 清理 ==="
cleanup
echo "  已取消本次创建的会议：user1 的 [$CREATED_U1] / user2 的 [$CREATED_U2]"
check "清理后待办不再含本次数据" \
  "$(curl -s --noproxy '*' "$BASE/api/approvals/pending?page=1&size=50" -H "Authorization: Bearer $AP1" \
     | j "any(str(x['meetingId']) in '$CREATED_U1 $CREATED_U2'.split() for x in d['data']['list'])")" "False"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
