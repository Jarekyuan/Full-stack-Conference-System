/**
 * T6.2 验收：我的会议（列表筛选 / 时间格式 / 编辑 / 取消 / 状态门槛）
 *
 * 覆盖：
 *   ① 主题列定宽（colgroup 240px，不把表格撑长）
 *   ② 时间显示完整起止日期：YYYY-MM-DD HH:mm 到 YYYY-MM-DD HH:mm
 *   ③ 状态筛选 / 会议室筛选 / 会议日期筛选
 *   ④ 操作列按状态：20 有编辑+取消；10 只有取消；50 只有详情
 *   ⑤ 列表编辑成功（标题更新）
 *   ⑥ 列表取消成功（状态变 50、按钮消失、详情时间线含取消原因）
 *   ⑦ 非组织人看不到编辑/取消
 *
 * 数据用 API 造：D1 room1 已预约；D2 room3 待审批（room3 策略需审批）。
 * 日期固定用“下一周”，避免今天恰好是周一/周日时选到过去时段。
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
  d.setDate(d.getDate() + 7); // 下一周，保证所有时段在未来
  return d;
})();
const dayOf = (o) => new Date(MONDAY.getTime() + o * 86400000);
const D1 = dayOf(1); // 下周二
const D2 = dayOf(2); // 下周三

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};
const sql = (q) =>
  execSync(`${MYSQL} ${JSON.stringify(q)}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    .split('\n').filter((l) => l.trim() && !l.includes('PAGER set to stdout') && !l.includes('Using a password'))
    .join('\n').trim();

(async () => {
  const createdIds = [];
  let browser;
  let restoreRateLimit = async () => {};
  try {
    restoreRateLimit = await disableRateLimit();
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('user1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    const token = await page.evaluate(() => localStorage.getItem('meeting.accessToken'));

    const api = async (path, init = {}) => {
      const res = await fetch(`${API}${path}`, {
        ...init,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...(init.headers || {}) },
      });
      return res.json();
    };
    const freeSlot = async (roomId, date) => {
      for (const h of [10, 11, 12, 13, 14, 15, 16, 17]) {
        const r = await api('/rooms/availability', {
          method: 'POST',
          body: JSON.stringify({
            roomIds: [roomId],
            startTime: `${fmt(date)} ${pad(h)}:00:00`,
            endTime: `${fmt(date)} ${pad(h + 1)}:00:00`,
          }),
        });
        if (r.data?.items?.[0]?.available) return h;
      }
      return undefined;
    };
    const book = async (roomId, date, hour, title) => {
      const r = await api('/meetings', {
        method: 'POST',
        headers: { 'X-Idempotency-Token': crypto.randomUUID() },
        body: JSON.stringify({
          title,
          roomId,
          startTime: `${fmt(date)} ${pad(hour)}:00:00`,
          endTime: `${fmt(date)} ${pad(hour + 1)}:00:00`,
        }),
      });
      if (r.code !== 0) throw new Error(`创建失败 code=${r.code} ${r.msg}`);
      createdIds.push(r.data.meetingId);
      return r.data.meetingId;
    };

    const h1 = await freeSlot(1, D1);
    const h2 = await freeSlot(3, D2);
    check('预检找到 room1/room3 空闲整点', h1 !== undefined && h2 !== undefined, `${fmt(D1)} ${h1}:00 / ${fmt(D2)} ${h2}:00`);
    if (h1 === undefined || h2 === undefined) throw new Error('空闲时段不足');

    const marker = `E2E列表${TS}`;
    const titleA = `${marker}-已预约`;
    const titleA2 = `${marker}-已编辑`;
    const titleB = `${marker}-待审批`;
    const titleC = `${marker}-第二场`;
    const idA = await book(1, D1, h1, titleA);
    const idB = await book(3, D2, h2, titleB);
    const h3 = await freeSlot(1, D1);
    if (h3 === undefined) throw new Error('没有第二个空闲时段给 R10 用例');
    const idC = await book(1, D1, h3, titleC);
    check('前置数据创建成功（20 + 10 + 20）', !!idA && !!idB && !!idC, `A=${idA} B=${idB} C=${idC}`);

    // 列表按开始时间倒序，测试会议在下一周，可能不在第 1 页——用唯一关键字把它们筛出来
    const openList = async () => {
      await page.goto(`${BASE}/meetings`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(800);
      await page.getByPlaceholder('搜索会议主题').fill(marker);
      await page.waitForTimeout(1600);
    };
    await openList();

    const rowByTitle = (title) => page.locator('.ant-table-tbody tr.ant-table-row', { hasText: title });
    const rowA = rowByTitle(titleA);
    const rowB = rowByTitle(titleB);
    const rowC = rowByTitle(titleC);
    check(
      '列表能看到三场测试会议',
      (await rowA.count()) === 1 && (await rowB.count()) === 1 && (await rowC.count()) === 1,
    );

    // ---------- ① 主题列定宽 ----------
    const firstColStyle = await page.locator('.ant-table colgroup col').first().getAttribute('style');
    check('主题列定宽 240px', (firstColStyle || '').includes('240'), firstColStyle || '');

    // ---------- ② 时间格式 ----------
    const rowTextA = (await rowA.textContent()) ?? '';
    check(
      '时间显示完整起止日期',
      new RegExp(`${fmt(D1)} ${pad(h1)}:00 到 ${fmt(D1)} ${pad(h1 + 1)}:00`).test(rowTextA),
      rowTextA.match(/\d{4}-\d{2}-\d{2} \d{2}:\d{2} 到 \d{4}-\d{2}-\d{2} \d{2}:\d{2}/)?.[0] ?? '',
    );

    // ---------- ③ 状态 / 会议室 / 日期筛选 ----------
    // 状态：待审批 → 只剩 B
    await page.locator('.ant-select').nth(0).click();
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: '待审批' }).first().click();
    await page.waitForTimeout(1500);
    await page.keyboard.press('Escape');
    check('状态筛选：待审批只剩 B', (await rowB.count()) === 1 && (await rowA.count()) === 0);

    await openList();
    // 会议室：青竹厅 → A 在、B 不在
    await page.locator('.ant-select').nth(1).click();
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: '青竹厅' }).first().click();
    await page.waitForTimeout(1500);
    check('会议室筛选：青竹厅只剩 A', (await rowA.count()) === 1 && (await rowB.count()) === 0);

    await openList();
    // 日期：D1 → A 在、B（D2）不在
    const dateInputs = page.locator('.ant-picker-input input');
    await dateInputs.nth(0).click();
    await dateInputs.nth(0).fill(fmt(D1));
    await dateInputs.nth(1).fill(fmt(D1));
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1800);
    check('日期筛选：D1 只剩 A', (await rowA.count()) === 1 && (await rowB.count()) === 0,
      `A=${await rowA.count()} B=${await rowB.count()}`);

    await openList();

    // ---------- ④ 操作列状态门槛 ----------
    const rowA1 = rowByTitle(titleA);
    const rowB1 = rowByTitle(titleB);
    check('已预约行有编辑按钮', (await rowA1.getByRole('button', { name: /编\s*辑/ }).count()) === 1);
    check('已预约行有取消按钮', (await rowA1.getByRole('button', { name: /取\s*消/ }).count()) === 1);
    check('待审批行没有编辑按钮', (await rowB1.getByRole('button', { name: /编\s*辑/ }).count()) === 0);
    check('待审批行有取消按钮', (await rowB1.getByRole('button', { name: /取\s*消/ }).count()) === 1);

    // ---------- ⑤ 列表编辑 ----------
    await rowA1.getByRole('button', { name: /编\s*辑/ }).click();
    await page.waitForTimeout(1200);
    check('编辑抽屉打开', (await page.getByText('编辑会议').count()) > 0);
    const drawer = page.locator('.ant-drawer').last();
    await page.getByPlaceholder('如：产品评审会').fill(titleA2);
    await drawer.getByRole('button', { name: /保\s*存/ }).click();
    await page.waitForTimeout(1800);
    check('列表显示编辑后的标题', (await rowByTitle(titleA2).count()) === 1 && (await rowByTitle(titleA).count()) === 0);

    // R10：编辑过 A 后改开 C，抽屉必须加载 C 的详情，不能拿 A 的 detail 提交
    await rowByTitle(titleC).getByRole('button', { name: /编\s*辑/ }).click();
    await page.waitForTimeout(1200);
    const editTitleValue = await page.getByPlaceholder('如：产品评审会').inputValue();
    check('切换编辑对象后表单是 C（不是上一场 A）', editTitleValue === titleC, editTitleValue);
    await page.locator('.ant-drawer-close').click();
    await page.waitForTimeout(500);

    // ---------- ⑥ 列表取消 ----------
    const rowA2 = rowByTitle(titleA2);
    await rowA2.getByRole('button', { name: /取\s*消/ }).click();
    await page.waitForTimeout(600);
    await page.locator('.ant-modal-content textarea').fill('E2E 列表取消：议题变更');
    await page.getByRole('button', { name: '确认取消' }).click();
    await page.waitForTimeout(1800);
    const rowA3 = rowByTitle(titleA2);
    check('取消后列表显示已取消', ((await rowA3.textContent()) ?? '').includes('已取消'));
    check('取消后编辑/取消按钮消失',
      (await rowA3.getByRole('button', { name: /编\s*辑/ }).count()) === 0 &&
        (await rowA3.getByRole('button', { name: /取\s*消/ }).count()) === 0);

    // ---------- 详情时间线 ----------
    await page.goto(`${BASE}/meetings/${idA}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);
    const detailText = (await page.textContent('body')) ?? '';
    check('详情状态为已取消', detailText.includes('已取消'));
    check('详情时间线含取消原因', detailText.includes('E2E 列表取消：议题变更'));

    // ---------- ⑦ 非组织人无操作入口 ----------
    const p2 = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    await p2.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await p2.getByPlaceholder('请输入用户名').fill('user2');
    await p2.getByPlaceholder('请输入密码').fill('user123');
    await p2.locator('button[type="submit"]').click();
    await p2.waitForURL('**/calendar', { timeout: 10000 });
    // R14.10：用一场 **status=20** 的会议验证——status=10 本来就没有编辑入口，
    // 旧断言即使删掉 organiserId 判断也会绿。
    await p2.goto(`${BASE}/meetings/${idC}`, { waitUntil: 'networkidle' });
    await p2.waitForTimeout(1200);
    check('非组织人看不到编辑按钮（20 已预约）', (await p2.getByRole('button', { name: '编辑会议' }).count()) === 0);
    check('非组织人看不到取消按钮（20 已预约）', (await p2.getByRole('button', { name: '取消会议' }).count()) === 0);
    await p2.close();

    // ---------- ⑧ ADMIN 代取消（O15） ----------
    const p3 = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    await p3.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await p3.getByPlaceholder('请输入用户名').fill('admin');
    await p3.getByPlaceholder('请输入密码').fill('admin123');
    await p3.locator('button[type="submit"]').click();
    await p3.waitForURL('**/calendar', { timeout: 10000 });
    await p3.goto(`${BASE}/meetings`, { waitUntil: 'networkidle' });
    await p3.waitForTimeout(800);
    await p3.getByText('全部会议', { exact: true }).click();
    await p3.waitForTimeout(1000);
    await p3.getByPlaceholder('搜索会议主题').fill(marker);
    await p3.waitForTimeout(1600);
    const adminRowC = p3.locator('.ant-table-tbody tr.ant-table-row', { hasText: titleC });
    check('ADMIN 切到全部会议后能看到别人的会议', (await adminRowC.count()) === 1);
    await adminRowC.getByRole('button', { name: /代取消/ }).click();
    await p3.waitForTimeout(600);
    const adminCancelModal = p3.locator('.ant-modal-content').last();
    check('代取消弹窗要求原因（确定按钮初始禁用）',
      await adminCancelModal.getByRole('button', { name: '确认取消' }).isDisabled());
    await adminCancelModal.locator('textarea').fill('E2E 管理员代取消');
    await adminCancelModal.getByRole('button', { name: '确认取消' }).click();
    await p3.waitForTimeout(1800);
    check('ADMIN 代取消后会议变为已取消',
      ((await p3.locator('.ant-table-tbody tr.ant-table-row', { hasText: titleC }).textContent()) ?? '').includes('已取消'));
    await p3.close();
    await page.close();
  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    await restoreRateLimit();
    if (createdIds.length > 0) {
      const ids = createdIds.map(Number).join(',');
      try {
        sql(`DELETE FROM smt_meeting.notification WHERE meeting_id IN (${ids});
             DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id IN (${ids});
             DELETE FROM smt_meeting.meeting_approval WHERE meeting_id IN (${ids});
             DELETE FROM smt_meeting.meeting_participant WHERE meeting_id IN (${ids});
             DELETE FROM smt_meeting.meeting WHERE id IN (${ids});`);
        check('清理完成（测试会议无残留）', sql(`SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN (${ids});`) === '0');
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
