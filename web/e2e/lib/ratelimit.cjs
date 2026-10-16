/**
 * e2e 公共前置：临时关闭预约限流（T4.8 起"创建预约"有用户级限流，默认 10 次/分钟）。
 *
 * 为什么 e2e 也要：脚本为了验证**别的功能**会连发多次预约，不开这一刀就会撞 10005——
 * 报出来的却是"造会失败"，看着像功能坏了（这一坑在整轮串跑时踩到过）。
 * 限流本身由 `scripts/verify-m4-ratelimit.sh` 专门验证；这里只借用同一个开关。
 *
 * 用法：`const restore = await disableRateLimit(); try { ... } finally { await restore(); }`
 */
const NACOS = process.env.NACOS_CFG_URL || 'http://127.0.0.1:8848/nacos/v1/cs/configs';
const DATA_ID = 'meeting-service.yml';

const readConfig = async () => {
  const res = await fetch(`${NACOS}?dataId=${DATA_ID}&group=DEFAULT_GROUP`);
  const text = await res.text();
  return text.includes('config data not exist') ? '' : text;
};

const publish = async (content) => {
  const body = new URLSearchParams({ dataId: DATA_ID, group: 'DEFAULT_GROUP', type: 'yaml', content });
  await fetch(NACOS, { method: 'POST', body });
};

async function disableRateLimit() {
  const backup = await readConfig();
  const off = 'meeting:\n  ratelimit:\n    reserve-per-minute: 0\n    window-seconds: 60\n';
  await publish(off);
  // 读回确认：不确认就打印"已关闭"，等于给自己假的安心（与 scripts/lib/ratelimit.sh 同一原则）
  await new Promise((r) => setTimeout(r, 2500));
  if (!(await readConfig()).includes('reserve-per-minute: 0')) {
    throw new Error('限流关闭失败（Nacos 不可达或配置被拒）——继续跑会撞 10005，先停');
  }
  console.log('  [限流] 已临时关闭（Nacos: reserve-per-minute=0），结束恢复');
  return async () => {
    if (backup) await publish(backup);
    console.log('  [限流] 已恢复原阈值');
  };
}

module.exports = { disableRateLimit };
