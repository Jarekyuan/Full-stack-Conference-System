#!/usr/bin/env bash
# AI 纪要状态机验证：10→20→30；MOCK_FAIL → 40 → 手动重试 → 30；重复触发幂等
# 前置：meeting-service 必须以 MINUTES_AI_PROVIDER=mock 启动（失败注入依赖 mock）
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${BASE:-http://localhost:8080/api}"
MYSQL="docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e"
TS="$(date +%s)"
MEETING_ID=""
PASS=0
FAIL=0
SEGMENT_TEXT="正常内容${TS}"

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
}
trap cleanup EXIT

TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"user1","password":"user123"}' | jget data.accessToken)
MEETING_ID=$($MYSQL "USE smt_meeting; INSERT INTO meeting
  (title, room_id, organiser_id, start_time, end_time, status, remark)
  VALUES ('E2EAI验证-${TS}', 1, 4, NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 1 HOUR, 40, '');
  SELECT LAST_INSERT_ID();" | tail -1 | tr -d '[:space:]')
check "会议已创建" "$([ -n "$MEETING_ID" ] && echo 1 || echo 0)" "1"

# 直接造纪要 + 分段 + 发言人（本脚本只验 AI 状态机）
$MYSQL "USE smt_meeting;
  INSERT INTO meeting_minutes (meeting_id, room_id, transcript_source, duration_ms, ai_status, created_by)
  VALUES (${MEETING_ID}, 1, 'TENCENT_ASR', 1000, 0, 4);
  SET @mid = LAST_INSERT_ID();
  INSERT INTO meeting_minutes_speaker (minutes_id, speaker_key, display_name, color)
  VALUES (@mid, '0', '说话人 1', '#7D8CFF');
  INSERT INTO meeting_transcript_segment (minutes_id, seq, speaker_key, start_ms, end_ms, text)
  VALUES (@mid, 0, '0', 0, 1000, '${SEGMENT_TEXT}');"

# 正常生成
curl -s -X POST "$BASE/meetings/${MEETING_ID}/minutes/generate" \
  -H "Authorization: Bearer $TOKEN" >/dev/null
check "重复触发生成幂等（仍为 10/20）" \
  "$(curl -s -X POST "$BASE/meetings/${MEETING_ID}/minutes/generate" -H "Authorization: Bearer $TOKEN" \
    | jget data.aiStatus | awk '{print ($1==10||$1==20)?1:0}')" "1"
STATUS=0
for _ in $(seq 1 30); do
  sleep 2
  STATUS=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN" | jget data.aiStatus)
  [ "$STATUS" = "30" ] && break
done
check "正常生成到 30" "$STATUS" "30"
check "正常生成有摘要" \
  "$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN" \
    | jget data.summary.summary | awk '{print (length($0)>0)?1:0}')" "1"
LOG_FILE="${MEETING_LOG:-server/logs/meeting-service.log}"
if [ -f "$LOG_FILE" ]; then
  check "服务日志不含语音转写正文/AI 摘要" \
    "$(grep -c "$SEGMENT_TEXT" "$LOG_FILE" || true)" "0"
fi

# 造失败：文本含 MOCK_FAIL，重置 AI 后再触发
$MYSQL "USE smt_meeting; UPDATE meeting_transcript_segment s
  JOIN meeting_minutes m ON m.id=s.minutes_id
  SET s.text='MOCK_FAIL' WHERE m.meeting_id=${MEETING_ID};
  UPDATE meeting_minutes SET ai_status=0, ai_attempts=0, summary_json=NULL, ai_error=NULL
  WHERE meeting_id=${MEETING_ID};"
curl -s -X POST "$BASE/meetings/${MEETING_ID}/minutes/generate" \
  -H "Authorization: Bearer $TOKEN" >/dev/null
STATUS=0
for _ in $(seq 1 45); do
  sleep 2
  STATUS=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN" | jget data.aiStatus)
  [ "$STATUS" = "40" ] && break
done
check "mock 失败最终到 40" "$STATUS" "40"
check "失败有 aiError" \
  "$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN" \
    | jget data.aiError | awk '{print (length($0)>0)?1:0}')" "1"

# 手动重试：恢复文本并触发
$MYSQL "USE smt_meeting; UPDATE meeting_transcript_segment s
  JOIN meeting_minutes m ON m.id=s.minutes_id
  SET s.text='恢复内容' WHERE m.meeting_id=${MEETING_ID};
  UPDATE meeting_minutes SET ai_finished_at = NOW() - INTERVAL 2 MINUTE
  WHERE meeting_id=${MEETING_ID};"
curl -s -X POST "$BASE/meetings/${MEETING_ID}/minutes/generate" \
  -H "Authorization: Bearer $TOKEN" >/dev/null
STATUS=0
for _ in $(seq 1 30); do
  sleep 2
  STATUS=$(curl -s "$BASE/meetings/${MEETING_ID}/minutes" -H "Authorization: Bearer $TOKEN" | jget data.aiStatus)
  [ "$STATUS" = "30" ] && break
done
check "手动重试后到 30" "$STATUS" "30"

echo "----------------------------------------"
echo "PASS=${PASS} FAIL=${FAIL}"
[ "$FAIL" -eq 0 ]
