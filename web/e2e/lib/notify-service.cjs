/**
 * e2e 公共前置（T4.6/T4.F1 评审后抽）：确保 notification-service 在跑。
 *
 * 为什么需要：该服务由多个脚本"按需起停"（每个脚本跑完会把它关掉），
 * 于是"某个 e2e 跑的时候它还开着"完全是运气——第一版 e2e-notify-ws 就因此在整轮串跑里挂掉，
 * 报的还是"连接失败"（看着像 WS 坏了）。前置条件要**自己校验并补齐**，不能假设环境是对的。
 */
const { spawn, execSync } = require('child_process');
const path = require('path');

const ROOT = path.resolve(__dirname, '../../..');
const JAR = `${ROOT}/server/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar`;

async function health() {
  try {
    const res = await fetch('http://127.0.0.1:8083/actuator/health');
    return (await res.json()).status;
  } catch {
    return null;
  }
}

async function ensureNotificationService(timeoutSec = 60) {
  if ((await health()) === 'UP') return;
  console.log('  … notification-service 没在跑，先按约定路径拉起它');
  // ⚠️ 取消环境变量要 delete：写成 `SERVER__PORT: ''` **不等于未设置**（Spring 会把空串当端口，服务起不来）
  const env = { ...process.env };
  delete env.SERVER__PORT;
  const fs = require('fs');
  const logFd = fs.openSync('/tmp/e2e-notify-service.log', 'a');
  spawn('java', ['-jar', JAR], {
    cwd: `${ROOT}/server`, detached: true, stdio: ['ignore', logFd, logFd], env,
  }).unref();
  for (let i = 0; i < timeoutSec; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    if ((await health()) === 'UP') return;
  }
  throw new Error('notification-service 起不来（看 /tmp/e2e-notify-service.log）');
}

module.exports = { ensureNotificationService };

// 给 bash 侧用的小工具（保持"进程退出后服务仍在"的语义）
if (require.main === module && process.argv[2] === 'ensure') {
  ensureNotificationService().then(() => process.exit(0)).catch((e) => { console.error(e.message); process.exit(1); });
}
