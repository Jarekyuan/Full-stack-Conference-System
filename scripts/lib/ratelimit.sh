#!/usr/bin/env bash
# ============================================================================
# 限流开关（T4.8 起"创建预约"带用户级限流，默认 10 次/分钟）
#
# 谁需要它：**同一个用户连发几十次预约请求**的脚本（并发锁验证等）——它们必然撞限流，
# 那时脚本报的是"大量 10005"，看着像并发锁失效（验错了对象）。
#
# 用法（在脚本里）：
#     source "$(dirname "$0")/lib/ratelimit.sh"
#     ratelimit_disable_for_script      # 备份原值 → 设 0（关闭）→ **读回确认** → 装好各种信号的恢复钩子
#
# ⚠️ 2026-10-16 评审 O1 补强（原实现在两个脚本里各抄一份，且三处都可能静默失效）：
#   ① 原来 curl 带 `|| true`、不看返回码就打印"已关闭"——Nacos 挂了会打出**假的成功**，
#      随后几十发请求撞 10005，失败信息恰好指向锁（正是要避免的误判）→ 现在**读回确认**，不成就退出；
#   ② 原来只挂 EXIT trap，SIGTERM/SIGKILL 不走 EXIT → 限流会**留在关闭状态**且无人知晓 →
#      现在 EXIT/INT/TERM 都挂（SIGKILL 挡不了，属已知边界）；
#   ③ 原来 20 行复制两份（违背项目踩坑 #16 的结论"带固定前缀的调用抽函数，错也只错一次"）→ 现在是这一份。
# ============================================================================
NACOS_CFG_URL="${NACOS_CFG_URL:-http://127.0.0.1:8848/nacos/v1/cs/configs}"
RATELIMIT_BAK="/tmp/ratelimit-backup.$$"
RATELIMIT_TMP="/tmp/ratelimit-new.$$"

_ratelimit_publish() {  # _ratelimit_publish <per-minute>
  printf 'meeting:\n  ratelimit:\n    reserve-per-minute: %s\n    window-seconds: 60\n' "$1" > "$RATELIMIT_TMP"
  curl -s --noproxy '*' -X POST "$NACOS_CFG_URL" -d "dataId=meeting-service.yml" \
    -d "group=DEFAULT_GROUP" -d "type=yaml" --data-urlencode "content@$RATELIMIT_TMP" > /dev/null 2>&1
}

_ratelimit_readback() {
  curl -s --noproxy '*' "$NACOS_CFG_URL?dataId=meeting-service.yml&group=DEFAULT_GROUP" 2>/dev/null
}

ratelimit_restore() {
  if [ -s "$RATELIMIT_BAK" ] && ! grep -q "config data not exist" "$RATELIMIT_BAK"; then
    curl -s --noproxy '*' -X POST "$NACOS_CFG_URL" -d "dataId=meeting-service.yml" \
      -d "group=DEFAULT_GROUP" -d "type=yaml" --data-urlencode "content@$RATELIMIT_BAK" > /dev/null 2>&1 || true
    echo "[限流] 已恢复原阈值"
  fi
  rm -f "$RATELIMIT_BAK" "$RATELIMIT_TMP"
}

ratelimit_disable_for_script() {
  _ratelimit_readback > "$RATELIMIT_BAK"
  _ratelimit_publish 0
  sleep 2   # 等 Nacos 长轮询把新值推给服务（实测 3~4 秒内生效，这里给足余量）
  # **读回确认**：不确认就打印"已关闭"，等于给自己一个假的安心（评审 O1）
  if ! _ratelimit_readback | grep -q 'reserve-per-minute: 0'; then
    echo "❌ [限流] 关闭失败（Nacos 不可达或配置被拒）——继续跑会撞 10005，先停"
    ratelimit_restore
    exit 1
  fi
  echo "[限流] 本脚本已临时关闭预约限流（Nacos: reserve-per-minute=0），结束自动恢复"
  # ⚠️ 链式安装：**不能直接覆盖**调用脚本已有的 EXIT trap——那会把它的自清理悄悄吃掉
  # （公共库最经典的雷：调用方以为自己还在清理，其实早被换掉了）
  local prev
  prev="$(trap -p EXIT | sed -e "s/^trap -- '//" -e "s/' EXIT$//")"
  if [ -n "$prev" ]; then
    trap "$prev; ratelimit_restore" EXIT INT TERM
  else
    trap ratelimit_restore EXIT INT TERM
  fi
}
