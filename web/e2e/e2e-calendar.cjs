/**
 * T2.F1 / T6.1 验收：日历页（周切换 / 会议落格 / 点空格带出预填时间）
 *
 * 2026-10-17 升级：
 * ① 不再依赖“10-16 手工造的产品周会/需求评审会”——脚本自己插、自己清；
 * ② 点空格改用**本周六**（未来）：T6.1 起过去时段不可预约，旧版点本周四会被正确拦截；
 * ③ 行高从 240 变 360，位置断言全部按实际 boundingBox 计算，不写死像素。
 *
 * 前置：四服务 + vite dev(5173) 已启动。
 * 用法：cd web && node e2e/e2e-calendar.cjs
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');

const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080/api';
const MYSQL = 'docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e';

const fmt = (d) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const md = (d) => `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sql = (q) =>
  execSync(`${MYSQL} ${JSON.stringify(q)}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    .split('\n')
    .filter((l) => l.trim() && !l.includes('PAGER set to stdout') && !l.includes('Using a password'))
    .join('\n')
    .trim();
const evictCalendarCache = (roomId, weekStart) =>
  execSync(
    `docker exec -e REDISCLI_AUTH=${process.env.REDIS_PASSWORD || 'redis123'} smt-redis redis-cli DEL cache:calendar:${roomId}:${weekStart}`,
    { stdio: 'ignore' },
  );

(async () => {
  const createdIds = [];
  let browser;
  const now = new Date();
  const dow = now.getDay();
  const monday = new Date(now);
  monday.setDate(now.getDate() - (dow === 0 ? 6 : dow - 1));
  const thisMonday = fmt(monday);
  const nextMondayDate = new Date(monday.getTime() + 7 * 86400000);
  const nextMonday = fmt(nextMondayDate);
  const friday = new Date(monday.getTime() + 4 * 86400000);
  const thursday = new Date(monday.getTime() + 3 * 86400000);
  const friDate = md(friday);
  const nextMonDate = md(nextMondayDate);
  const stamp = Date.now();
  const title1 = `E2E产品周会-${stamp}`;
  const title2 = `E2E需求评审会-${stamp}`;

  const results = [];
  const check = (name, ok, detail = '') => {
    results.push({ name, ok });
    console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
  };
  const centerX = (b) => b.x + b.width / 2;
  const centerY = (b) => b.y + b.height / 2;

  try {
    // 自造本周数据：周五 room1 已预约、周四 room3 待审批
    const id1 = sql(`INSERT INTO smt_meeting.meeting
      (title, room_id, organiser_id, start_time, end_time, status)
      VALUES ('${title1}', 1, 4, '${fmt(friday)} 10:00:00', '${fmt(friday)} 11:00:00', 20);
      SELECT LAST_INSERT_ID();`);
    if (id1) createdIds.push(id1);
    const id2 = sql(`INSERT INTO smt_meeting.meeting
      (title, room_id, organiser_id, start_time, end_time, status)
      VALUES ('${title2}', 3, 4, '${fmt(thursday)} 10:00:00', '${fmt(thursday)} 11:00:00', 10);
      SELECT LAST_INSERT_ID();`);
    if (id2) createdIds.push(id2);
    check('测试数据插入成功（2 场）', createdIds.length === 2, `ids=${createdIds.join(',')}`);
    evictCalendarCache(1, thisMonday);
    evictCalendarCache(3, thisMonday);

    // 找一个**下周周一**的空闲整点（必定在未来；T6.1 起过去时段会被正确拦截）
    const userToken = await (
      await fetch(`${API}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'user1', password: 'user123' }),
      })
    ).json();
    const token = userToken.data.accessToken;
    const nextCal = await (
      await fetch(`${API}/rooms/1/calendar?week=${nextMonday}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json();
    const nextMeetings = nextCal.data.days
      .find((d) => d.date === nextMonday)
      ?.meetings ?? [];
    const busy = (h) => {
      const start = new Date(`${nextMonday}T${String(h).padStart(2, '0')}:00:00`);
      const end = new Date(start.getTime() + 3600000);
      return nextMeetings.some((m) => new Date(m.startTime.replace(' ', 'T')) < end && new Date(m.endTime.replace(' ', 'T')) > start);
    };
    const freeHour = [10, 11, 12, 13, 14, 15, 16, 17, 18].find((h) => !busy(h));

    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });

    // 登录（user1）
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('user1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    await page.waitForTimeout(1200); // 等房间列表 + 并发周视图返回

    // ---------- ① 切换周正确加载 ----------
    const body1 = await page.textContent('body');
    check(`默认加载本周（${thisMonday}）`, body1.includes(thisMonday), thisMonday);

    await page.getByRole('button', { name: '下一周' }).click();
    await page.waitForTimeout(1200);
    const body2 = await page.textContent('body');
    check(`点"下一周"切到 ${nextMonday}`, body2.includes(nextMonday), nextMonday);
    check('下一周不再显示本周会议', !body2.includes(title1));

    // antd 会在两个汉字的按钮文字间自动插空格（"本 周"），故用正则匹配
    await page.getByRole('button', { name: /本\s*周/ }).click();
    await page.waitForTimeout(1200);
    const body3 = await page.textContent('body');
    check('点"本周"切回本周', body3.includes(thisMonday), thisMonday);

    // ---------- ② 已创建会议显示在正确格子 ----------
    const weekMeeting = page.getByText(title1).first();
    check('本周网格显示「产品周会」', await weekMeeting.isVisible());
    check('本周网格显示「需求评审会」（待审批也展示）', await page.getByText(title2).first().isVisible());

    // 位置断言：会议块的横向中心落在"本周五"那一列内
    const friHeader = await page.getByText(friDate, { exact: true }).first().boundingBox();
    const blockBox = await weekMeeting.boundingBox();
    check(
      '「产品周会」落在周五那一列',
      !!friHeader && !!blockBox && centerX(blockBox) >= friHeader.x && centerX(blockBox) <= friHeader.x + friHeader.width,
      `块中心x=${blockBox && Math.round(centerX(blockBox))} 列范围=[${friHeader && Math.round(friHeader.x)}, ${friHeader && Math.round(friHeader.x + friHeader.width)}]`,
    );

    // 位置断言：会议块纵向落在"青竹厅"那一行内（且 10:00 场次应在上半部）
    const roomCell = await page.getByText('青竹厅').first().locator('..').boundingBox();
    check(
      '「产品周会」落在"青竹厅"那一行',
      !!roomCell && !!blockBox && centerY(blockBox) >= roomCell.y && centerY(blockBox) <= roomCell.y + roomCell.height,
      `块中心y=${blockBox && Math.round(centerY(blockBox))} 行范围=[${roomCell && Math.round(roomCell.y)}, ${roomCell && Math.round(roomCell.y + roomCell.height)}]`,
    );
    check(
      '10:00 的会议块位于时间轴上半部（08:00–20:00 窗口靠上）',
      !!roomCell && !!blockBox && centerY(blockBox) < roomCell.y + roomCell.height / 2,
    );

    // ---------- ③ 点未来空格带出预填时间 ----------
    check('找到下周一的空闲整点', freeHour !== undefined, `${nextMonday} ${freeHour}:00`);
    if (freeHour !== undefined) {
      await page.getByRole('button', { name: '下一周' }).click();
      await page.waitForTimeout(1200);
      const roomCellNext = await page.getByText('青竹厅').first().locator('..').boundingBox();
      const monHeader = await page.getByText(nextMonDate, { exact: true }).first().boundingBox();
      await page.mouse.click(
        centerX(monHeader),
        roomCellNext.y + roomCellNext.height * ((freeHour - 8) / 12) + 2,
      );
      await page.waitForTimeout(700);
      check('点空格打开预约 Drawer', await page.getByText('预约会议').first().isVisible());
      const startInput = await page.locator('.ant-drawer .ant-picker-input input').nth(0).inputValue().catch(() => '');
      const endInput = await page.locator('.ant-drawer .ant-picker-input input').nth(1).inputValue().catch(() => '');
      check(
        '预填时间符合点击高度',
        (startInput || '').includes(`${String(freeHour).padStart(2, '0')}:00`) &&
          (endInput || '').includes(`${String(freeHour + 1).padStart(2, '0')}:00`),
        `${startInput} ~ ${endInput}`,
      );
      await page.locator('.ant-drawer-close').click();
      await page.waitForTimeout(400);
    }
  } catch (e) {
    check(`验证中断: ${e.message}`, false);
  } finally {
    if (createdIds.length > 0) {
      const ids = createdIds.map(Number).join(',');
      try {
        sql(`DELETE FROM smt_meeting.notification WHERE meeting_id IN (${ids});
             DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id IN (${ids});
             DELETE FROM smt_meeting.meeting_participant WHERE meeting_id IN (${ids});
             DELETE FROM smt_meeting.meeting WHERE id IN (${ids});`);
        check('清理测试数据', sql(`SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN (${ids});`) === '0');
      } catch (e) {
        console.error('自清理失败:', e.message);
      }
    }
    try {
      evictCalendarCache(1, thisMonday);
      evictCalendarCache(3, thisMonday);
    } catch (e) {
      console.error('清缓存失败:', e.message);
    }
    if (browser) await browser.close();
  }

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n结果：${results.length - failed}/${results.length} 通过`);
  process.exit(failed ? 1 : 0);
})();
