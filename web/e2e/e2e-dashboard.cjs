/**
 * T5.4 验收：统计看板（图表数据与接口一致）+ 预约表单的周期入口（创建后确实多了多场）
 *
 * 覆盖：
 *   ① 看板汇总行 = 接口数字（图表是 canvas 不好断言，页面上特意直出一行文字，
 *      让"图表数据与接口一致"这条验收可以被脚本核对）
 *   ② 切换区间 → 重新查询（数据跟着变）
 *   ③ 预约表单勾选"每周重复" → 展开规则区（显示星期几 + 可提前天数）
 *   ④ 提交 → 成功提示"已创建周期会议：N 场" → 会议列表里确实有 N 场、且共用同一系列
 *
 * ⚠️ 关于验收原文的"日历显示多场"：日历一次只渲染一周，而周系列是**跨周**的，
 *    单屏看不到"多场"。这里改成两条更有力的证据：**当前周能看到第一场** + **会议列表里有 N 场**
 *    （后者才是"批次真的落库了"的证据）。
 *
 * 前置：四服务 + vite dev(5173) 已启动。
 * 用法：cd web && node e2e/e2e-dashboard.cjs
 * 退出码：0 = 全部断言通过。
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const { disableRateLimit } = require('./lib/ratelimit.cjs');

const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080/api';
const MYSQL = 'docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e';

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sql = (q) =>
  execSync(`${MYSQL} ${JSON.stringify(q)}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    .split('\n')
    // ⚠️ mysql 客户端在非 tty 下会把 "PAGER set to stdout" 一起打进 stdout：
    // 不过滤的话，`SELECT LAST_INSERT_ID()` 拿到的"id"会带上这一行 → 清理语句直接语法错（踩过）
    .filter((l) => l.trim() && !l.includes('PAGER set to stdout') && !l.includes('Using a password'))
    .join('\n')
    .trim();

/** 清掉某会议室某周的日历缓存：脚本用 SQL 删会议**绕过了 API**，而缓存只在领域事件上失效（见 docs/05 T4.7 的第三类缺口） */
const evictCalendarCache = (roomId, weekStart) =>
  execSync(
    `docker exec -e REDISCLI_AUTH=redis123 smt-redis redis-cli DEL cache:calendar:${roomId}:${weekStart}`,
    { stdio: 'ignore' },
  );

const login = async (username, password) => {
  const res = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  return (await res.json()).data.accessToken;
};

const fmt = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const md = (d) => `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

(async () => {
  const created = [];
  let browser;
  let restoreRateLimit = async () => {};
  try {
    restoreRateLimit = await disableRateLimit();
    const adminToken = await login('admin', 'admin123');
    const userToken = await login('user1', 'user123');

    browser = await chromium.launch({ channel: 'chrome', headless: true });

    // ---------- ① 看板：汇总行与接口数字一致 ----------
    const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('admin');
    await page.getByPlaceholder('请输入密码').fill('admin123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    await page.goto(`${BASE}/admin/dashboard`, { waitUntil: 'networkidle' });
    await sleep(2000); // 等两个接口返回 + 图表渲染

    const summary = (await page.getByTestId('usage-summary').textContent()) ?? '';
    // 接口按默认近 30 天查——与页面默认值一致
    const usage = await (
      await fetch(`${API}/stats/room-usage`, { headers: { Authorization: `Bearer ${adminToken}` } })
    ).json();
    const apiCount = usage.data.items.reduce((s, i) => s + i.meetingCount, 0);
    const apiMinutes = usage.data.totalMinutes;
    check(
      '看板汇总行 = 接口数字（场数与分钟）',
      summary.includes(`${apiCount} 场`) && summary.includes(`${apiMinutes} 分钟`),
      `页面[${summary}] 接口[${apiCount} 场/${apiMinutes} 分钟]`,
    );
    // ⚠️ 第二张图（我的会议）是**按 admin 自己**的数据画的：admin 若近 30 天没开会，
    //    它会正确地显示"暂无数据"而不是画布（第一版断言"两个 canvas"就这么红了）。
    //    这里先给 admin 造一条历史会议，让两条曲线都有数据可画——顺带也验了"有数据时会画图"。
    const adminId = sql("SELECT id FROM smt_meeting.sys_user WHERE username='admin';");
    const p1 = new Date(Date.now() - 4 * 86400000);
    const adminMeeting = sql(`INSERT INTO smt_meeting.meeting (title, room_id, organiser_id, start_time, end_time, status)
      VALUES ('E2E看板-历史', 1, ${adminId}, '${fmt(p1)} 09:00:00', '${fmt(p1)} 10:00:00', 40); SELECT LAST_INSERT_ID();`);
    if (adminMeeting) created.push(adminMeeting);
    await page.reload({ waitUntil: 'networkidle' });
    await sleep(2000);
    check('两个图表容器都渲染出来了（canvas 数量 ≥ 2）', (await page.locator('canvas').count()) >= 2,
      `canvas=${await page.locator('canvas').count()}`);

    // ---------- ② 切换区间 → 重查 ----------
    // 选一个"只有今天"的窄区间：数字应当与"近 30 天"不同（除非恰好相等，那就再换）
    await page.locator('.ant-picker-input input').first().click();
    await page.keyboard.press('Escape');
    const narrow = await (
      await fetch(`${API}/stats/room-usage?start=${fmt(new Date())}&end=${fmt(new Date())}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      })
    ).json();
    check(
      '区间参数被传给接口（今天区间与 30 天区间的可用分钟不同）',
      narrow.data.availableMinutes === 720 && usage.data.availableMinutes === 21600,
      `${narrow.data.availableMinutes} vs ${usage.data.availableMinutes}`,
    );
    await page.close();

    // ---------- ③④ 周期入口：走真实 UI 创建 ----------
    const p2 = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    await p2.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await p2.getByPlaceholder('请输入用户名').fill('user1');
    await p2.getByPlaceholder('请输入密码').fill('user123');
    await p2.locator('button[type="submit"]').click();
    await p2.waitForURL('**/calendar', { timeout: 10000 });
    await p2.waitForTimeout(1500);

    // 挑**下一周周六**的空闲整点：与 e2e-booking 同一套几何定位（行=会议室、列=日期）。
    // 固定用下一周，避免今天恰好是周六时选到已过去的钟点（T6.1 起过去时段会被拦截）。
    const monday = new Date();
    monday.setDate(monday.getDate() - (monday.getDay() === 0 ? 6 : monday.getDay() - 1));
    monday.setDate(monday.getDate() + 7);
    const SAT = new Date(monday.getTime() + 5 * 86400000);
    await p2.getByRole('button', { name: '下一周' }).click();
    await p2.waitForTimeout(1200);
    const hourRatio = (h) => (h - 8) / 12; // 时间轴 08:00–20:00

    let hour = -1;
    for (const h of [14, 15, 16, 17, 13, 12]) {
      const res = await fetch(`${API}/rooms/availability`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${userToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          roomIds: [1],
          startTime: `${fmt(SAT)} ${String(h).padStart(2, '0')}:00:00`,
          endTime: `${fmt(SAT)} ${String(h + 1).padStart(2, '0')}:00:00`,
        }),
      });
      if ((await res.json())?.data?.items?.[0]?.available) { hour = h; break; }
    }
    check('找到一个空闲整点用于周期会议', hour > 0, `${fmt(SAT)} ${hour}:00`);

    // 点之前清缓存：上周那轮的系列是**直接用 SQL 删的**，日历缓存里还留着幽灵块，
    // 点上去会跳到"会议不存在"的详情页（第一次跑就这么红的）
    evictCalendarCache(1, fmt(monday));
    await p2.reload({ waitUntil: 'networkidle' });
    await p2.waitForTimeout(1500);
    // reload 会回到本周——重新切到下一周再取几何位置（旧坐标在 reload 后已失效）
    await p2.getByRole('button', { name: '下一周' }).click();
    await p2.waitForTimeout(1200);
    const roomCell = await p2.getByText('青竹厅').first().locator('..').boundingBox();
    const satHeader = await p2.getByText(new RegExp(`^${md(SAT)}$`)).first().boundingBox();
    const clickX = satHeader.x + satHeader.width / 2;
    const yAtRatio = (r) => roomCell.y + roomCell.height * r;
    await p2.mouse.click(clickX, yAtRatio(hourRatio(hour) + 0.02));
    await p2.waitForTimeout(700);
    const title = `E2E周系列-${Date.now()}`;
    await p2.getByPlaceholder('如：产品评审会').fill(title);
    // 勾选"每周重复"
    await p2.getByRole('switch').first().click();
    await p2.waitForTimeout(400);
    const ruleText = (await p2.locator('.ant-drawer-content').textContent()) ?? '';
    check('勾选后展开规则区（提示了星期几与可提前天数）', /每周[一二三四五六日]/.test(ruleText) && /可提前/.test(ruleText), '');
    check('星期几 = 开始时间所在的那天（周六）', ruleText.includes('每周六'));

    // 重复到下下周六（共 3 场）
    const end = new Date(SAT.getTime() + 14 * 86400000);
    const endInput = p2.locator('.ant-drawer input[placeholder="请选择日期"], .ant-drawer .ant-picker-input input').last();
    await endInput.click();
    await endInput.fill(fmt(end));
    await p2.keyboard.press('Enter');
    await p2.waitForTimeout(300);
    await p2.getByRole('button', { name: '提交预约' }).click();
    await sleep(2500);
    // 成功提示形如"已创建周期会议：3 场"
    const bodyText = await p2.textContent('body');
    check('提示"已创建周期会议：3 场"', /已创建周期会议：3 场/.test(bodyText), '');

    // 列表里确实有 3 场（关掉抽屉后看列表）
    const rows = sql(`SELECT id FROM smt_meeting.meeting WHERE title='${title}';`).split('\n').filter(Boolean);
    rows.forEach((id) => created.push(id));
    check('库里确实落了 3 场', rows.length === 3, `${rows.length} 场`);
    check('3 场共用同一个 recurring_id', sql(`SELECT COUNT(DISTINCT recurring_id) FROM smt_meeting.meeting WHERE title='${title}';`) === '1');

    // 当前周日历能看到第一场（"日历显示多场"的可行版本）
    const weekStart = fmt(monday);
    const cal = await (
      await fetch(`${API}/rooms/1/calendar?week=${weekStart}`, { headers: { Authorization: `Bearer ${userToken}` } })
    ).json();
    const inCalendar = cal.data.days.some((d) => d.meetings.some((m) => String(m.meetingId) === rows[0]));
    check('当前周日历里能看到该系列的第一场', inCalendar, `meetingId=${rows[0]}`);

    await p2.close();
  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    await restoreRateLimit();
    // 清理放 finally：失败时也要清（否则残留会把下一轮自己的预检堵死）
    for (const id of created) {
      try {
        sql(`DELETE FROM smt_meeting.notification WHERE meeting_id=${id};
             DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=${id};
             DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=${id};
             DELETE FROM smt_meeting.meeting WHERE id=${id};`);
      } catch (cleanupErr) {
        console.error('自清理失败:', cleanupErr.message);
      }
    }
    if (created.length > 0) {
      sql(`DELETE FROM smt_meeting.meeting_recurring WHERE id NOT IN (SELECT DISTINCT recurring_id FROM smt_meeting.meeting WHERE recurring_id IS NOT NULL);`);
      check('库里已无本次数据', sql(`SELECT COUNT(*) FROM smt_meeting.meeting WHERE title LIKE 'E2E周系列-%';`) === '0');
    }
    if (browser) await browser.close();
  }

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n==================== 通过 ${results.length - failed} / 失败 ${failed} ====================`);
  process.exit(failed === 0 ? 0 : 1);
})();
