#!/usr/bin/env bash
# 第 4 批修复的逐条实测（R7/R8/P1-6/9/10/11/13/16/17 + Redis 口令）
# 只看 body 的 code（业务错误是 HTTP 200 + 非 0 code），协议层错误才看 HTTP 状态码
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

BASE="http://localhost:8080"
DIRECT="http://127.0.0.1:8081"
PASS=0; FAIL=0

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

check() { # check <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then echo "  ✅ $1 → $2"; PASS=$((PASS+1));
  else echo "  ❌ $1 → 实际 [$2] 期望 [$3]"; FAIL=$((FAIL+1)); fi
}

token() {
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | j "d['data']['accessToken']"
}

ADMIN=$(token admin admin123)
USER=$(token user1 user123)
[ -n "$ADMIN" ] && echo "登录成功（admin/user1）" || { echo "登录失败，终止"; exit 1; }
AH="Authorization: Bearer $ADMIN"
UH="Authorization: Bearer $USER"

echo
echo "=== R7 roomIds 入参（上限 / 去重 / 元素校验）==="
# ⚠️ 构造 JSON 必须带 import json：漏了会得到空请求体，而空体也回 10001——
#    "碰巧通过"但验的是另一条路径（本次执行就踩到了，故这里显式留痕）
# ⚠️ 时间跟着今天算（评审 O10）：写死日期的话，过了那天用例会因"不可预约过去的时间"变红——
# 红的原因与它要验的修复无关，排查时非常误导
CHECK_DAY=$(python3 -c "import datetime;print((datetime.date.today()+datetime.timedelta(days=3)))")
BIG=$(python3 -c "import json,sys;d=sys.argv[1];print(json.dumps({'roomIds':list(range(1,53)),'startTime':d+' 10:00:00','endTime':d+' 11:00:00'}))" "$CHECK_DAY")
check "51 个 roomIds 被拒（上限 50，且是上限而非解析失败）" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "$UH" -H 'Content-Type: application/json' -d "$BIG" | j "d['msg'].split(' ')[1]")" "一次最多查询"
EXACT=$(python3 -c "import json,sys;d=sys.argv[1];print(json.dumps({'roomIds':list(range(1,51)),'startTime':d+' 10:00:00','endTime':d+' 11:00:00'}))" "$CHECK_DAY")
check "恰好 50 个仍放行（边界）" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "$UH" -H 'Content-Type: application/json' -d "$EXACT" | j "d['code']")" "0"
check "重复 id [3,3,3] 去重成 1 条" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "$UH" -H 'Content-Type: application/json' -d "{\"roomIds\":[3,3,3],\"startTime\":\"$CHECK_DAY 10:00:00\",\"endTime\":\"$CHECK_DAY 11:00:00\"}" | j "len(d['data']['items'])")" "1"
check "负数 id 被拒" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "$UH" -H 'Content-Type: application/json' -d "{\"roomIds\":[-1],\"startTime\":\"$CHECK_DAY 10:00:00\",\"endTime\":\"$CHECK_DAY 11:00:00\"}" | j "d['code']")" "10001"
check "正常单个 id 仍可用" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms/availability" -H "$UH" -H 'Content-Type: application/json' -d "{\"roomIds\":[1],\"startTime\":\"$CHECK_DAY 10:00:00\",\"endTime\":\"$CHECK_DAY 11:00:00\"}" | j "d['code']")" "0"

echo
echo "=== R8 详情不再返回参会人 email ==="
# ⚠️ 取样的会议**必须有参会人**，否则下一条断言在空字典上求值、恒为 False → 空转通过（评审 O10）。
# 做法：先按"有参会人"筛一场（列表接口带 participantCount），取不到就明确报错而不是静默放行。
MID=$(curl -s --noproxy '*' "$BASE/api/meetings?scope=all&page=1&size=50" -H "$AH" \
  | python3 -c "import sys,json;print(next((m['id'] for m in json.load(sys.stdin)['data']['list'] if m.get('participantCount')), ''))")
if [ -z "$MID" ]; then
  echo "  ❌ 找不到带参会人的会议——本条断言无从验证（不能当通过）"
  FAIL=$((FAIL+1))
else
  echo "  （取样会议 id=$MID，含参会人）"
  check "participants 无 email 字段" \
    "$(curl -s --noproxy '*' "$BASE/api/meetings/$MID" -H "$AH" | j "'email' in (d['data']['participants'][0] if d['data']['participants'] else {})")" "False"
fi

echo
echo "=== P1-13 /users/search 只回 id/realName/deptName ==="
check "字段集合" \
  "$(curl -s --noproxy '*' "$BASE/api/users/search?keyword=%E5%BC%A0" -H "$AH" | j "sorted(d['data'][0].keys()) if d['data'] else 'EMPTY'")" "['deptName', 'id', 'realName']"
check "deptName 不再是 null（JOIN 生效）" \
  "$(curl -s --noproxy '*' "$BASE/api/users/search?keyword=%E5%BC%A0" -H "$AH" | j "d['data'][0]['deptName']")" "研发部"

echo
echo "=== P1-6 会议室 device 筛选 ==="
TAG=$(curl -s --noproxy '*' "$BASE/api/rooms" -H "$UH" | j "d['data'][0]['deviceTags'][0]")
echo "  （取第一个会议室的标签：$TAG）"
check "按 device 筛出的房间都含该标签" \
  "$(curl -s --noproxy '*' --get "$BASE/api/rooms" -H "$UH" --data-urlencode "device=$TAG" | j "all('$TAG' in r['deviceTags'] for r in d['data']) and len(d['data'])>0")" "True"
check "不存在的标签返回空列表" \
  "$(curl -s --noproxy '*' --get "$BASE/api/rooms" -H "$UH" --data-urlencode "device=不存在的设备xyz" | j "len(d['data'])")" "0"

echo
echo "=== P1-9 会议室分组悬空引用 ==="
check "POST /rooms groupId=99999 被拒" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/rooms" -H "$AH" -H 'Content-Type: application/json' -d '{"name":"悬空分组测试","groupId":99999,"capacity":8}' | j "d['code']")" "10001"

echo
echo "=== R4 建用户的 deptId / roleIds 悬空引用 ==="
check "deptId=99999 被拒" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/users" -H "$AH" -H 'Content-Type: application/json' -d '{"username":"tmp_u1","password":"tmp123456","realName":"临时","roleIds":[1],"deptId":99999}' | j "d['code']")" "10001"
check "roleIds=[99999] 被拒" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/users" -H "$AH" -H 'Content-Type: application/json' -d '{"username":"tmp_u2","password":"tmp123456","realName":"临时","roleIds":[99999]}' | j "d['code']")" "10001"

echo
echo "=== P1-11 框架级异常不得吞成 500 ==="
check "?status=abc（参数类型错）" \
  "$(curl -s --noproxy '*' "$BASE/api/rooms?status=abc" -H "$UH" | j "d['code']")" "10001"
check "/rooms/abc（路径变量类型错）" \
  "$(curl -s --noproxy '*' "$BASE/api/rooms/abc" -H "$UH" | j "d['code']")" "10001"
check "缺 week 参数" \
  "$(curl -s --noproxy '*' "$BASE/api/rooms/1/calendar" -H "$UH" | j "d['code']")" "10001"
check "PUT /api/auth/login → HTTP 405" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{}')" "405"
check "PUT /api/auth/login → body 10008" \
  "$(curl -s --noproxy '*' -X PUT "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{}' | j "d['code']")" "10008"
check "表单 Content-Type 打 JSON 接口 → HTTP 415" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST "$BASE/api/meetings" -H "$UH" -d 'a=b')" "415"
check "表单 Content-Type 打 JSON 接口 → body 10009" \
  "$(curl -s --noproxy '*' -X POST "$BASE/api/meetings" -H "$UH" -d 'a=b' | j "d['code']")" "10009"

echo
echo "=== P1-10 匿名访问 ADMIN 接口 → 401（而非 403）==="
check "直连 8081 无身份头打 /users（HTTP 状态）" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$DIRECT/users?page=1&size=1")" "401"
check "直连 8081 无身份头打 /users（body code）" \
  "$(curl -s --noproxy '*' "$DIRECT/users?page=1&size=1" | j "d['code']")" "10002"
check "带 USER 身份打 /users 仍是 403" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$DIRECT/users?page=1&size=1" -H 'X-User-Id: 3' -H 'X-User-Roles: USER')" "403"

echo
echo "=== P1-17 traceId / CORS / OPTIONS 预检 ==="
check "登录响应带 X-Trace-Id" \
  "$(curl -s --noproxy '*' -D - -o /dev/null -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' | grep -ci '^x-trace-id:')" "1"
check "401（无 token）响应也带 X-Trace-Id" \
  "$(curl -s --noproxy '*' -D - -o /dev/null "$BASE/api/meetings" | grep -ci '^x-trace-id:')" "1"
check "自带 traceId 被沿用" \
  "$(curl -s --noproxy '*' -D - -o /dev/null "$BASE/api/meetings" -H 'X-Trace-Id: my-trace-abcdefgh' | grep -ci 'x-trace-id: my-trace-abcdefgh')" "1"
check "非法 traceId 被替换（不原样回显）" \
  "$(curl -s --noproxy '*' -D - -o /dev/null "$BASE/api/meetings" -H 'X-Trace-Id: bad<id>' | grep -ci 'x-trace-id: bad')" "0"
check "OPTIONS 预检 → HTTP 200（不是 401）" \
  "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X OPTIONS "$BASE/api/meetings" -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: authorization,content-type')" "200"
check "OPTIONS 预检带 Access-Control-Allow-Origin" \
  "$(curl -s --noproxy '*' -D - -o /dev/null -X OPTIONS "$BASE/api/meetings" -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' | grep -ci 'access-control-allow-origin')" "1"
check "预检暴露 X-Trace-Id 给 JS" \
  "$(curl -s --noproxy '*' -D - -o /dev/null -X OPTIONS "$BASE/api/meetings" -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' | grep -ci 'access-control-expose-headers')" "1"

echo
echo "=== 回归：核心链路仍然可用 ==="
check "登录→刷新 闭环" \
  "$(RT=$(curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"user1","password":"user123"}' | j "d['data']['refreshToken']"); curl -s --noproxy '*' -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}" | j "d['code']")" "0"
check "会议列表可查" \
  "$(curl -s --noproxy '*' "$BASE/api/meetings?scope=mine&page=1&size=5" -H "$UH" | j "d['code']")" "0"
check "日历可查" \
  "$(curl -s --noproxy '*' "$BASE/api/rooms/1/calendar?week=2026-10-20" -H "$UH" | j "d['code']")" "0"

echo
echo "==================== 通过 $PASS / 失败 $FAIL ===================="
[ "$FAIL" -eq 0 ]
