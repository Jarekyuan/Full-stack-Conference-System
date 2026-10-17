/**
 * T4.F1 验收：前端通知中心（铃铛角标 + 通知列表 + 实时推送 + 断线重连）
 *
 * 覆盖：
 *   ① 铃铛角标显示真实未读数（首屏从 REST 拉）——与库里对得上
 *   ② **另一端创建预约 → 本端角标秒级 +1**（WS 推送，不走轮询）
 *   ③ 打开通知中心能看到那条通知；点它 → 标记已读 → **角标减少**
 *   ④ 全部已读 → 角标归零
 *   ⑤ **断线自动重连**：把 notification-service 停掉再拉起，等前端自己重连后，
 *      再产生一条通知仍能秒级到达（不刷新页面）
 *
 * 用法：cd web && node e2e/e2e-notify-center.cjs
 * 前置：四个服务 + vite dev(5173) 已启动；notification-service 能被本脚本按约定路径重启
 *       （脚本会用 `server/logs/notification-service.log` 拉起它）。
 * 退出码：0 = 全部断言通过。
 */
const { chromium } = require('playwright');
const { disableRateLimit } = require('./lib/ratelimit.cjs');
const { execSync, spawn } = require('child_process');

const ROOT = require('path').resolve(__dirname, '../..');
const JAR = `${ROOT}/server/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar`;
const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080/api';
const MYSQL = 'docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e';

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};

const sql = (query) =>
  execSync(`${MYSQL} ${JSON.stringify(query)}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    .split('\n').map((l) => l.trim()).filter((l) => l && !l.includes('Using a password')).join('\n');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * 用某个 token 创建一场预约（"另一端"的动作，走真实 API）。
 * ⚠️ 时段**先探测再订**：写死时段会被上一次失败残留的数据占掉（第一次跑就撞了 40001）。
 */
async function book(token, title, dayOffsetFrom = 20, hourFrom = 9) {
  const pad = (n) => String(n).padStart(2, '0');
  const fmt = (d, h) => {
    const x = new Date(Date.now() + d * 86400000);
    return `${x.getFullYear()}-${pad(x.getMonth() + 1)}-${pad(x.getDate())} ${pad(h)}:00:00`;
  };
  for (let d = dayOffsetFrom; d < dayOffsetFrom + 6; d++) {
    for (let h = hourFrom; h < 18; h++) {
      const start = fmt(d, h);
      const end = fmt(d, h + 1);
      const availRes = await fetch(`${API}/rooms/availability`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ roomIds: [6], startTime: start, endTime: end }),
      });
      const available = (await availRes.json())?.data?.items?.[0]?.available;
      if (!available) continue;
      const res = await fetch(`${API}/meetings`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
          'X-Idempotency-Token': crypto.randomUUID(),
        },
        body: JSON.stringify({ title, roomId: 6, startTime: start, endTime: end }),
      });
      const body = await res.json();
      if (body.code === 0) return body.data.meetingId;
    }
  }
  throw new Error('连续多天多个时段都订不上，先清理测试数据');
}

/** 读角标数字（没有角标元素 = 0） */
async function badgeCount(page) {
  const el = page.locator('.ant-badge-count');
  if ((await el.count()) === 0) return 0;
  const text = (await el.first().textContent()) ?? '';
  return Number(text.replace(/\D/g, '') || '0');
}

async function waitBadge(page, predicate, timeoutMs = 15000, label = '') {
  const start = Date.now();
  let last = -1;
  while (Date.now() - start < timeoutMs) {
    last = await badgeCount(page);
    if (predicate(last)) return last;
    await sleep(300);
  }
  return last;
}

(async () => {
  const created = [];
  let browser;
  let notifyProc = null;
  let restoreRateLimit = async () => {};
  try {
    restoreRateLimit = await disableRateLimit();
    // ---------- 前置：notification-service 必须在跑（没有它就没有消费、没有推送）----------
    // 脚本自己校验并补齐：上一轮如果把它停了、或启动失败，直接跑会得到一堆"推送没来"的假失败
    const healthOf = async () => {
      try {
        const res = await fetch('http://127.0.0.1:8083/actuator/health');
        return (await res.json()).status;
      } catch {
        return null;
      }
    };
    if ((await healthOf()) !== 'UP') {
      console.log('  … notification-service 没在跑，先按约定路径拉起它');
      const env = { ...process.env };
      delete env.SERVER__PORT;
      const p = spawn('java', ['-jar', JAR], {
        cwd: `${ROOT}/server`, detached: true,
        stdio: ['ignore', require('fs').openSync('/tmp/e2e-notify-restart.log', 'a'),
                require('fs').openSync('/tmp/e2e-notify-restart.log', 'a')],
        env,
      });
      p.unref();
      for (let i = 0; i < 60; i++) {
        await sleep(1000);
        if ((await healthOf()) === 'UP') break;
      }
    }
    check('notification-service 已就绪（脚本会自己拉起）', (await healthOf()) === 'UP');
    // 登录（页面里做，顺便拿到 token 给"另一端"用）
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('user1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    const token = await page.evaluate(() => localStorage.getItem('meeting.accessToken'));

    // ---------- ① 首屏角标 = 库里真实的未读数 ----------
    await sleep(1500); // 等 REST 拉未读数
    const dbUnread = Number(sql("SELECT COUNT(*) FROM smt_meeting.notification WHERE user_id=(SELECT id FROM smt_meeting.sys_user WHERE username='user1') AND read_flag=0;"));
    const initial = await waitBadge(page, (n) => n === Math.min(dbUnread, 99), 8000);
    check(`首屏角标与库里未读数一致（库 ${dbUnread} 条）`, initial === Math.min(dbUnread, 99), String(initial));

    // ---------- ② 另一端创建预约 → 角标秒级 +1（WS，不刷新页面）----------
    const before = initial;
    const m1 = await book(token, 'E2E-F1-推送验证');
    created.push(m1);
    const afterPush = await waitBadge(page, (n) => n === before + 1, 15000);
    check('另一端创建预约后角标 +1（WS 推送，未刷新页面）', afterPush === before + 1,
      `${before} → ${afterPush}`);

    // ---------- ③ 通知中心：能看到那条 → 点击已读 → 角标 -1 ----------
    await page.getByRole('menuitem', { name: /通知中心/ }).click();
    await page.waitForURL('**/notifications', { timeout: 8000 });
    await page.waitForTimeout(1200);
    const itemVisible = await page.getByText('E2E-F1-推送验证').first().isVisible();
    check('通知列表里能看到刚推来的那条', itemVisible);
    await page.getByText('E2E-F1-推送验证').first().click();
    // T6.2：预约创建通知 → 会议列表（不再直接进详情）
    await page.waitForTimeout(1200);
    check('预约创建通知点击 → 会议列表', page.url().endsWith('/meetings'), page.url());
    await page.goto(`${BASE}/notifications`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);
    const afterRead = await waitBadge(page, (n) => n === before, 10000);
    check('标记已读后角标 -1', afterRead === before, `${afterPush} → ${afterRead}`);

    // ---------- ④ 全部已读 → 角标归零 ----------
    // 先再造一条，确保"全部已读"有东西可标
    const m2 = await book(token, 'E2E-F1-全部已读');
    created.push(m2);
    await waitBadge(page, (n) => n >= 1, 15000);
    await page.getByRole('button', { name: /全部已读/ }).click();
    const afterAll = await waitBadge(page, (n) => n === 0, 15000);
    check('全部已读后角标归零', afterAll === 0, String(afterAll));

    // ---------- ④.5 删除通知（T6.2）----------
    await page.goto(`${BASE}/notifications`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);
    const firstItem = page.locator('.ant-list-item').first();
    const firstItemText = (await firstItem.textContent()) ?? '';
    /** 唯一标题：两条测试通知的标题都以 E2E-F1- 开头，用它精确判断这一条是否消失 */
    const firstTitle = firstItemText.match(/E2E-F1-[^\s]+/)?.[0] ?? '';
    await firstItem.locator('.ant-checkbox-input').check({ force: true });
    await page.waitForTimeout(300);
    check('勾选一条后「删除选中（1）」可用',
      (await page.getByRole('button', { name: /删除选中（1）/ }).count()) === 1);
    await page.getByRole('button', { name: /删除选中/ }).click();
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /删\s*除/ }).click();
    await page.waitForTimeout(1500);
    // 不能用"正文前 20 字"判断：两条通知的类型标签/开头完全相同，会误判成没删掉
    check('选中的通知已从列表消失',
      !!firstTitle && (await page.locator('.ant-list-item', { hasText: firstTitle }).count()) === 0,
      firstTitle || '（未提取到唯一标题）');

    await page.getByRole('button', { name: /全部删除/ }).click();
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /全部删除/ }).click();
    await page.waitForTimeout(1500);
    check('全部删除后显示空状态', (await page.getByText('还没有通知').count()) > 0);
    check('全部删除后角标为 0', (await badgeCount(page)) === 0);

    // ---------- ⑤ 断线自动重连：停掉 notification-service 再拉起 ----------
    console.log('  … 停掉 notification-service，验证前端自动重连（最多等 60s）');
    execSync("lsof -nP -iTCP:8083 -sTCP:LISTEN -t | xargs -r kill", { stdio: 'ignore' });
    await sleep(2000);
    // ⚠️ 防"环境变量覆盖端口"（项目踩坑 #5）：要**删掉**这个键，不能设成空串——
    // 空串 ≠ 未设置，Spring 会拿它当端口值解析、服务直接起不来（第一次就是这么失败的，
    // 而且 stdio 全 ignore、连日志都没有，只能靠"8083 没人监听"反推）。
    const childEnv = { ...process.env };
    delete childEnv.SERVER__PORT;
    notifyProc = spawn('java', ['-jar', JAR], {
      cwd: `${ROOT}/server`,
      detached: true,
      stdio: ['ignore', require('fs').openSync('/tmp/e2e-notify-restart.log', 'a'), require('fs').openSync('/tmp/e2e-notify-restart.log', 'a')],
      env: childEnv,
    });
    notifyProc.unref();
    // 等它起来 + 等前端重连（退避封顶 30s，给足余量）
    // 先等它真的起来（日志写 /tmp/e2e-notify-restart.log，失败时能查）
    for (let i = 0; i < 30; i++) {
      await sleep(1000);
      try {
        const res = await fetch('http://127.0.0.1:8083/actuator/health');
        if ((await res.json()).status === 'UP') break;
      } catch { /* 还没起来 */ }
    }
    let reconnected = false;
    const reconnectStart = Date.now();
    while (Date.now() - reconnectStart < 60000) {
      await sleep(3000);
      try {
        const m3 = await book(token, 'E2E-F1-重连验证');
        created.push(m3);
        const n = await waitBadge(page, (x) => x >= 1, 20000);
        if (n >= 1) { reconnected = true; break; }
      } catch {
        // 服务还没起来，继续等
      }
    }
    check('服务重启后前端自动重连，新通知仍能到达（未刷新页面）', reconnected);

  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    await restoreRateLimit();
    // 清理放 finally：**异常路径同样要清**——第一次跑失败时数据留在了库里，
    // 下一次跑就被自己的残留堵住（"脚本要自清理"不只是跑成功时的事）
    for (const id of created) {
      sql(`DELETE FROM smt_meeting.notification WHERE meeting_id=${id};
           DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=${id};
           DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=${id};
           DELETE FROM smt_meeting.meeting WHERE id=${id};`);
    }
    if (created.length > 0) {
      const left = Number(sql(`SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN (${created.join(',')});`));
      check('库里已无本次数据', left === 0, String(left));
    }
    if (browser) await browser.close();
  }

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n==================== 通过 ${results.length - failed} / 失败 ${failed} ====================`);
  process.exit(failed === 0 ? 0 : 1);
})();
