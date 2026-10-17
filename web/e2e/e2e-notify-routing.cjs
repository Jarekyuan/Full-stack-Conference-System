/**
 * T6.2 验收：待审批通知的点击去向
 *
 * 覆盖：
 *   ① 待审批通知（APPROVAL_TODO）点击 → 审批中心（仍为 10）
 *   ② 会议已被审批后再点同一条 → 提示“该会议已审核或不存在”，留在通知中心
 *
 * 预约创建/结果/提醒/变更/取消的去向由 `notificationRouting.test.ts` 单测覆盖；
 * 这里只验需要真实状态的 APPROVAL_TODO（要先查会议状态）。
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const { disableRateLimit } = require('./lib/ratelimit.cjs');

const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080/api';
const MYSQL = 'docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e';
const TS = Date.now();
const pad = (v) => String(v).padStart(2, '0');
const fmt = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const MONDAY = (() => {
  const n = new Date();
  const d = new Date(n);
  d.setDate(n.getDate() - (n.getDay() === 0 ? 6 : n.getDay() - 1));
  d.setDate(d.getDate() + 7);
  return d;
})();
const D = new Date(MONDAY.getTime() + 3 * 86400000);

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};
const sql = (q) =>
  execSync(`${MYSQL} ${JSON.stringify(q)}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    .split('\n').filter((l) => l.trim() && !l.includes('PAGER set to stdout') && !l.includes('Using a password'))
    .join('\n').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  let meetingId = null;
  let browser;
  let restoreRateLimit = async () => {};
  try {
    restoreRateLimit = await disableRateLimit();
    const login = async (username, password) => {
      const res = await fetch(`${API}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      return (await res.json()).data.accessToken;
    };
    const userToken = await login('user1', 'user123');
    const approverToken = await login('approver1', 'user123');
    check('user1 / approver1 登录成功', !!userToken && !!approverToken);

    // 找 room3（需审批）未来空闲整点
    let hour;
    for (const h of [10, 11, 12, 13, 14, 15, 16, 17]) {
      const r = await (
        await fetch(`${API}/rooms/availability`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${userToken}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({
            roomIds: [3],
            startTime: `${fmt(D)} ${pad(h)}:00:00`,
            endTime: `${fmt(D)} ${pad(h + 1)}:00:00`,
          }),
        })
      ).json();
      if (r?.data?.items?.[0]?.available) {
        hour = h;
        break;
      }
    }
    check('找到 room3 空闲整点', hour !== undefined, `${fmt(D)} ${hour}:00`);
    if (hour === undefined) throw new Error('room3 无空闲时段');

    const title = `E2E待审批路由-${TS}`;
    const created = await (
      await fetch(`${API}/meetings`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${userToken}`,
          'Content-Type': 'application/json',
          'X-Idempotency-Token': crypto.randomUUID(),
        },
        body: JSON.stringify({
          title,
          roomId: 3,
          startTime: `${fmt(D)} ${pad(hour)}:00:00`,
          endTime: `${fmt(D)} ${pad(hour + 1)}:00:00`,
        }),
      })
    ).json();
    meetingId = created.data?.meetingId;
    check('创建待审批会议成功（status=10）', created.code === 0 && created.data?.status === 10,
      `id=${meetingId} status=${created.data?.status}`);
    if (!meetingId) throw new Error('创建待审批会议失败');

    // 等 APPROVAL_TODO 落库（异步 MQ）
    let notifCount = 0;
    for (let i = 0; i < 30; i++) {
      notifCount = Number(sql(
        `SELECT COUNT(*) FROM smt_meeting.notification
          WHERE user_id=(SELECT id FROM smt_meeting.sys_user WHERE username='approver1')
            AND type='APPROVAL_TODO' AND content LIKE '%${title}%';`,
      ));
      if (notifCount > 0) break;
      await sleep(1000);
    }
    check('approver1 收到待审批通知', notifCount > 0, `count=${notifCount}`);

    // ① 点击 → 审批中心
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('approver1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    await page.goto(`${BASE}/notifications`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);
    await page.getByText(title).first().click();
    await page.waitForTimeout(1200);
    check('待审批通知点击 → 审批中心', page.url().includes('/approvals'), page.url());

    // 通过审批（API），让同一条通知变成“已审核”
    const pass = await (
      await fetch(`${API}/approvals/${meetingId}/pass`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${approverToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ comment: 'E2E 路由验收' }),
      })
    ).json();
    check('审批通过成功（用于制造已审核状态）', pass.code === 0, `code=${pass.code}`);

    // ② 再点同一条 → 提示已审核，留在通知中心
    await page.goto(`${BASE}/notifications`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);
    await page.getByText(title).first().click();
    await page.waitForTimeout(1000);
    check('已审核后再点 → 提示“该会议已审核或不存在”',
      (await page.getByText('该会议已审核或不存在').count()) > 0);
    check('已审核后再点 → 留在通知中心', page.url().includes('/notifications'), page.url());

    await page.close();
  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    await restoreRateLimit();
    if (meetingId) {
      try {
        sql(`DELETE FROM smt_meeting.notification WHERE meeting_id=${meetingId};
             DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=${meetingId};
             DELETE FROM smt_meeting.meeting_approval WHERE meeting_id=${meetingId};
             DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=${meetingId};
             DELETE FROM smt_meeting.meeting WHERE id=${meetingId};`);
        check('清理完成（测试会议无残留）', sql(`SELECT COUNT(*) FROM smt_meeting.meeting WHERE id=${meetingId};`) === '0');
      } catch (cleanupErr) {
        console.error('自清理失败:', cleanupErr.message);
      }
    }
    if (browser) await browser.close();
  }
  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n结果：${results.length - failed}/${results.length} 通过`);
  process.exit(failed ? 1 : 0);
})();
