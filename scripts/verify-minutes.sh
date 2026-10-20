#!/usr/bin/env bash
# 会议纪要主链路验证：分片上传 → 腾讯 ASR 转写 → 详情/转写/洞察 → AI → 导出 → 权限负例 → 重新转写
# 前置：services 已启动；meeting-service 使用 COS（COS_* 必填）。
# CI 建议 MINUTES_ASR_PROVIDER=mock（真实 ASR 会慢且计费）。
# 清理只删 MySQL 记录，不删除 COS 桶内 minutes/{meetingId}/ 测试对象。
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${BASE:-http://localhost:8080/api}"
MYSQL="docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e"
TS="$(date +%s)"
TITLE="E2E纪要验证-${TS}"
PASS=0
FAIL=0
MEETING_ID=""
UPLOAD_ID=""
TMP_WAV="/tmp/smt-minutes-${TS}.wav"
TMP_DOCX="/tmp/smt-minutes-${TS}.docx"

check() {
  local name="$1" actual="$2" expected="$3"
  if [ "$actual" = "$expected" ]; then
    PASS=$((PASS + 1)); echo "✓ ${name}"
  else
    FAIL=$((FAIL + 1)); echo "✗ ${name}  expected=${expected} actual=${actual}"
  fi
}

jget() {
  python3 -c 'import sys,json
d=json.load(sys.stdin)
for p in sys.argv[1].split("."):
    d = d[int(p)] if p.isdigit() else d[p]
print(d)' "$1"
}

cleanup() {
  set +e
  if [ -n "$MEETING_ID" ]; then
    $MYSQL "USE smt_meeting;
      DELETE s FROM meeting_transcript_segment s JOIN meeting_minutes m ON m.id=s.minutes_id WHERE m.meeting_id=${MEETING_ID};
      DELETE sp FROM meeting_minutes_speaker sp JOIN meeting_minutes m ON m.id=sp.minutes_id WHERE m.meeting_id=${MEETING_ID};
      DELETE FROM meeting_minutes WHERE meeting_id=${MEETING_ID};
      DELETE FROM file_object WHERE biz_type='MINUTES_AUDIO' AND biz_id=${MEETING_ID};
      DELETE FROM file_upload_session WHERE biz_type='MINUTES_AUDIO' AND biz_id=${MEETING_ID};
      DELETE FROM meeting WHERE id=${MEETING_ID};" >/dev/null 2>&1
  fi
  rm -f "$TMP_WAV" "$TMP_DOCX"
}
trap cleanup EXIT

# 1) 登录
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"user1","password":"user123"}' | jget data.accessToken)
check "user1 登录" "$([ -n "$TOKEN" ] && echo 1 || echo 0)" "1"

# 2) 造一场已结束会议（status=40，room 1，user1=4）
MEETING_ID=$($MYSQL "USE smt_meeting; INSERT INTO meeting
  (title, room_id, organiser_id, start_time, end_time, status, remark)
  VALUES ('${TITLE}', 1, 4, NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 1 HOUR, 40, '');
  SELECT LAST_INSERT_ID();" | tail -1 | tr -d '[:space:]')
check "会议已创建" "$([ -n "$MEETING_ID" ] && echo 1 || echo 0)" "1"

# 3) 初始化分片上传
head -c 1024 /dev/zero > "$TMP_WAV"
INIT=$(curl -s -X POST "$BASE/files/multipart/init" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"bizType\":\"MINUTES_AUDIO\",\"bizId\":${MEETING_ID},\"fileName\":\"test.wav\",\"fileSize\":1024,\"contentType\":\"audio/wav\"}")
UPLOAD_ID=$(echo "$INIT" | jget data.uploadId)
check "init 返回总分片 1" "$(echo "$INIT" | jget data.totalParts)" "1"

# 4) 取分片 URL 并 PUT
PART_URL=$(curl -s "$BASE/files/multipart/${UPLOAD_ID}/parts?partNumbers=1" \
  -H "Authorization: Bearer $TOKEN" | jget data.0.url)
# 用 --upload-file（隐含 PUT、无 form content-type）：--data-binary 会被 Spring 的
# OrderedFormContentFilter 当成表单消费掉，controller 读到的 inputStream 为空。
ETAG=$(curl -s -D - -o /dev/null --upload-file "$TMP_WAV" "$PART_URL" \
  | awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
check "分片拿到 ETag" "$([ -n "$ETAG" ] && echo 1 || echo 0)" "1"

# 5) complete
BODY=$(python3 -c 'import json,sys; print(json.dumps({"parts":[{"partNumber":1,"etag":sys.argv[1]}]}))' "$ETAG")
COMPLETE=$(curl -s -X POST "$BASE/files/multipart/${UPLOAD_ID}/complete" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$BODY")
FILE_ID=$(echo "$COMPLETE" | jget data.fileId)
check "complete 返回 fileId" "$([ -n "$FILE_ID" ] && [ "$FILE_ID" != "None" ] && echo 1 || echo 0)" "1"
SESSION=$(curl -s "$BASE/files/multipart/${UPLOAD_ID}" -H "Authorization: Bearer $TOKEN")
check "已完成会话返回 fileId" "$(echo "$SESSION" | jget data.fileId)" "$FILE_ID"
check "已完成会话 status 10" "$(echo "$SESSION" | jget data.status)" "10"

# 6) 发起 ASR 转写
TRANSCRIBE=$(curl -s -X POST "$BASE/meetings/${MEETING_ID}/minutes/transcribe" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"fileId\":${FILE_ID}}")
check "转写已入队" "$(echo "$TRANSCRIBE" | jget data.asrStatus | awk '{print ($1==10||$1==20)?1:0}')" "1"

STATUS=0
for _ in $(seq 1 60); do
  sleep 2
  DETAIL=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN")
  STATUS=$(echo "$DETAIL" | jget data.asrStatus)
  [ "$STATUS" = "30" ] && break
  [ "$STATUS" = "40" ] && break
done
check "ASR 转写成功" "$STATUS" "30"
check "转写有 2 个发言人" \
  "$(echo "$DETAIL" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["data"]["speakers"]))')" "2"
check "转写有 2 段" "$(echo "$DETAIL" | jget data.insights.totalSegments)" "2"
check "AI 初始状态 0" "$(echo "$DETAIL" | jget data.aiStatus)" "0"

# 7) 转写接口 / 隐私
TRANSCRIPT=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes/transcript" -H "Authorization: Bearer $TOKEN")
check "转写第 1 段文本" "$(echo "$TRANSCRIPT" | jget data.segments.0.text)" "你好，"

# 8) 生成 AI（mock/DeepSeek）
GEN=$(curl -s -X POST "$BASE/meetings/${MEETING_ID}/minutes/generate" \
  -H "Authorization: Bearer $TOKEN")
check "触发生成返回 10 或 20" "$(echo "$GEN" | jget data.aiStatus | awk '{print ($1==10||$1==20)?1:0}')" "1"
AI_STATUS=0
for _ in $(seq 1 60); do
  sleep 2
  AI_STATUS=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN" | jget data.aiStatus)
  [ "$AI_STATUS" = "30" ] && break
  [ "$AI_STATUS" = "40" ] && break
done
check "AI 生成成功" "$AI_STATUS" "30"
SUMMARY=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN" | jget data.summary.summary)
check "AI 摘要非空" "$([ -n "$SUMMARY" ] && echo 1 || echo 0)" "1"

# 9) 导出 docx
curl -s -o "$TMP_DOCX" "$BASE/meetings/${MEETING_ID}/minutes/export?format=docx" -H "Authorization: Bearer $TOKEN"
check "docx 是 ZIP 魔数" "$(head -c 2 "$TMP_DOCX")" "PK"
if python3 - "$TMP_DOCX" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    text = z.read('word/document.xml').decode('utf-8')
assert '会议纪要' in text and '完整转写' in text and '你好' in text
PY
then DOCX_OK=1; else DOCX_OK=0; fi
check "docx 含标题/转写" "$DOCX_OK" "1"

# 10) 权限负例：user2 非参会人
TOKEN2=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"user2","password":"user123"}' | jget data.accessToken)
DENY=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN2" | jget code)
check "非参会人查看被拒 10003" "$DENY" "10003"

# 11) 重新转写：覆盖旧分段并重置 AI
RE=$(curl -s -X POST "$BASE/meetings/${MEETING_ID}/minutes/transcribe" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"fileId\":${FILE_ID}}")
check "重新转写已入队" "$(echo "$RE" | jget data.asrStatus | awk '{print ($1==10||$1==20)?1:0}')" "1"
STATUS=0
for _ in $(seq 1 60); do
  sleep 2
  DETAIL=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN")
  STATUS=$(echo "$DETAIL" | jget data.asrStatus)
  [ "$STATUS" = "30" ] && break
  [ "$STATUS" = "40" ] && break
done
check "重新转写成功" "$STATUS" "30"
check "重新转写后 AI 重置为 0" "$(echo "$DETAIL" | jget data.aiStatus)" "0"
check "重新转写后仍为 2 段" "$(echo "$DETAIL" | jget data.insights.totalSegments)" "2"

echo "----------------------------------------"
echo "PASS=${PASS} FAIL=${FAIL}"
[ "$FAIL" -eq 0 ]
