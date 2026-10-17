/**
 * T6.1 验收：日历预约体验优化
 *
 * 覆盖：
 *   ① 四色图例可见（待审批 / 我预订 / 邀请我的 / 其他含已过期）
 *   ② 会议室关键字过滤（命中 / 无匹配空状态 / 清空恢复）
 *   ③ 会议室悬浮卡（容量/位置/设备/审批策略）
 *   ④ 过去时段置灰 + 点击不打开预约 Drawer
 *   ⑤ 会议块：按关系四色（mine/invited/other/pending）、显示预订人、待审批提示
 *   ⑥ 重叠会议分泳道（同格两块的 x 坐标不同）
 *   ⑦ 点击会议块进入详情
 *   ⑧ 参会人按部门树选择（研发部 → 张三 → 表单出现标签）
 *
 * 数据用 SQL 直接插：不经 API，避免冲突/限流干扰；插完删日历缓存（脚本绕开 API 的已知缓存缺口）。
 * 前置：四服务 + vite dev(5173) 已启动。
 * 用法：cd web && node e2e/e2e-calendar-enhanced.cjs
 * 退出码：0 = 全部断言通过。
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const dayjs = require('dayjs');

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
    .filter((l) => l.trim() && !l.includes('PAGER set to stdout') && !l.includes('Using a password'))
    .join('\n')
    .trim();
const evictCalendarCache = (roomId, weekStart) =>
  execSync(
    `docker exec -e REDISCLI_AUTH=${process.env.REDIS_PASSWORD || 'redis123'} smt-redis redis-cli DEL cache:calendar:${roomId}:${weekStart}`,
    { stdio: 'ignore' },
  );

const fmt = (d) => dayjs(d).format('YYYY-MM-DD');
const mondayOf = (d) => {
  const day = d.day();
  return d.startOf('day').subtract(day === 0 ? 6 : day - 1, 'day');
};

const login = async (username, password) => {
  const res = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  return (await res.json()).data.accessToken;
};
const apiGet = async (path, token) =>
  (await fetch(`${API}${path}`, { headers: { Authorization: `Bearer ${token}` } })).json();

(async () => {
  const createdIds = [];
  let browser;
  /** 清理阶段也要用：不能在 finally 里引用 try 块内的 const（块级作用域） */
  let cacheWeekForCleanup = '';
  try {
    const userToken = await login('user1', 'user123');
    const today = dayjs();
    const currentMonday = mondayOf(today);
    const prevMonday = currentMonday.subtract(7, 'day');
    const nextMonday = currentMonday.add(7, 'day');
    const nextTuesday = nextMonday.add(1, 'day');
    const tue = fmt(nextTuesday);
    cacheWeekForCleanup = fmt(nextMonday);

    // ---------- 造数据：next week 周二，4 场（含一场待审批与一场重叠） ----------
    const idA = sql(`INSERT INTO smt_meeting.meeting
      (title, room_id, organiser_id, start_time, end_time, status)
      VALUES ('E2E日历-我预订', 1, 4, '${tue} 10:00:00', '${tue} 11:00:00', 20);
      SELECT LAST_INSERT_ID();`);
    if (idA) createdIds.push(idA);
    const idB = sql(`INSERT INTO smt_meeting.meeting
      (title, room_id, organiser_id, start_time, end_time, status)
      VALUES ('E2E日历-邀请我的', 1, 5, '${tue} 11:00:00', '${tue} 12:00:00', 20);
      SELECT LAST_INSERT_ID();`);
    if (idB) {
      createdIds.push(idB);
      sql(`INSERT INTO smt_meeting.meeting_participant (meeting_id, user_id) VALUES (${idB}, 4);`);
    }
    const idC = sql(`INSERT INTO smt_meeting.meeting
      (title, room_id, organiser_id, start_time, end_time, status)
      VALUES ('E2E日历-其他', 1, 6, '${tue} 14:00:00', '${tue} 15:00:00', 20);
      SELECT LAST_INSERT_ID();`);
    if (idC) createdIds.push(idC);
    const idD = sql(`INSERT INTO smt_meeting.meeting
      (title, room_id, organiser_id, start_time, end_time, status)
      VALUES ('E2E日历-待审批', 1, 5, '${tue} 10:00:00', '${tue} 11:00:00', 10);
      SELECT LAST_INSERT_ID();`);
    if (idD) createdIds.push(idD);
    check('测试数据插入成功（4 场）', createdIds.length === 4, `ids=${createdIds.join(',')}`);
    evictCalendarCache(1, fmt(nextMonday));

    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    // T6.3：日历页应只发批量请求（/rooms/calendar），不再逐房间 /rooms/{id}/calendar
    const batchCalls = [];
    const singleCalls = [];
    page.on('request', (req) => {
      const url = req.url();
      if (/\/api\/rooms\/calendar\?/.test(url)) batchCalls.push(url);
      else if (/\/api\/rooms\/\d+\/calendar/.test(url)) singleCalls.push(url);
    });
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('user1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    await page.waitForTimeout(2000);

    // ---------- ⓪ 批量请求 ----------
    check('日历页不再逐房间请求', singleCalls.length === 0, `single=${singleCalls.length}`);
    check(
      '日历页发批量请求（StrictMode 下同 URL 最多 2 次）',
      batchCalls.length > 0 && new Set(batchCalls).size === 1,
      `batch=${batchCalls.length} unique=${new Set(batchCalls).size}`,
    );

    // ---------- ① 图例 ----------
    const legendText = (await page.textContent('body')) ?? '';
    check(
      '四色图例可见',
      ['待审批', '我预订', '邀请我的', '其他（含已过期）'].every((t) => legendText.includes(t)),
    );

    // ---------- ② 会议室过滤 ----------
    const filter = page.getByPlaceholder('搜索会议室名称 / 位置 / 设备');
    await filter.fill('青竹');
    await page.waitForTimeout(400);
    check(
      '关键字过滤：只剩青竹厅',
      (await page.locator('[data-testid="room-row-1"]').count()) === 1 &&
        (await page.locator('[data-testid="room-row-2"]').count()) === 0,
    );
    await filter.fill('不存在的会议室');
    await page.waitForTimeout(400);
    check('无匹配时显示空状态', (await page.getByText('没有匹配的会议室').count()) > 0);
    await filter.fill('');
    await page.waitForTimeout(400);

    // ---------- ③ 会议室悬浮卡 ----------
    await page.locator('[data-testid="room-row-1"]').getByText('青竹厅').first().hover();
    await page.waitForTimeout(600);
    const popoverText = (await page.locator('.ant-popover:visible').textContent()) ?? '';
    check(
      '会议室悬浮卡展示全部信息（容量/位置/设备/审批策略）',
      popoverText.includes('容量：') &&
        popoverText.includes('位置：') &&
        popoverText.includes('设备：') &&
        (popoverText.includes('需审批') || popoverText.includes('免审批')),
    );
    await page.mouse.move(10, 10);
    await page.waitForTimeout(300);

    // ---------- ④ 过去时段：置灰 + 点击不打开 Drawer ----------
    await page.getByRole('button', { name: '上一周' }).click();
    await page.waitForTimeout(1000);
    const prevStr = fmt(prevMonday);
    check(
      '过去日期整天灰罩存在',
      (await page.locator(`[data-testid="past-overlay-1-${prevStr}"]`).count()) === 1,
    );
    // 从接口挑一个没有会议的小时，保证点到的确实是空格
    const prevCal = await apiGet(`/rooms/1/calendar?week=${prevStr}`, userToken);
    const prevMeetings = prevCal.data.days.flatMap((d) => d.meetings);
    const busy = (h) => {
      const slotStart = dayjs(`${prevStr} ${String(h).padStart(2, '0')}:00:00`);
      const slotEnd = slotStart.add(1, 'hour');
      return prevMeetings.some((m) => dayjs(m.startTime).isBefore(slotEnd) && dayjs(m.endTime).isAfter(slotStart));
    };
    const freeHour = [9, 10, 11, 12, 13, 14, 15, 16, 17, 18].find((h) => !busy(h));
    check('上一周找到空闲整点用于点击', freeHour !== undefined, `${prevStr} ${freeHour}:00`);
    if (freeHour !== undefined) {
      const cell = page.locator(`[data-testid="day-cell-1-${prevStr}"]`);
      await cell.scrollIntoViewIfNeeded();
      const box = await cell.boundingBox();
      await page.mouse.click(box.x + box.width / 2, box.y + box.height * ((freeHour - 8) / 12) + 4);
      await page.waitForTimeout(700);
      check('点击过去时段不会打开预约 Drawer', (await page.getByText('预约会议').count()) === 0);
    }

    // 回到“下一周”（上一周 → 本周 → 下一周）
    await page.getByRole('button', { name: '下一周' }).click();
    await page.waitForTimeout(600);
    await page.getByRole('button', { name: '下一周' }).click();
    await page.waitForTimeout(1200);

    // ---------- ⑤ 会议块四色 / 预订人 / 待审批 ----------
    const blockA = page.locator(`[data-testid="meeting-block-${idA}"]`);
    const blockB = page.locator(`[data-testid="meeting-block-${idB}"]`);
    const blockC = page.locator(`[data-testid="meeting-block-${idC}"]`);
    const blockD = page.locator(`[data-testid="meeting-block-${idD}"]`);
    check(
      '四场测试会议都渲染到日历',
      (await blockA.count()) === 1 &&
        (await blockB.count()) === 1 &&
        (await blockC.count()) === 1 &&
        (await blockD.count()) === 1,
    );
    check('我预订块分类=mine', (await blockA.getAttribute('data-category')) === 'mine');
    check('邀请我的块分类=invited', (await blockB.getAttribute('data-category')) === 'invited');
    check('其他块分类=other', (await blockC.getAttribute('data-category')) === 'other');
    check('待审批块分类=pending', (await blockD.getAttribute('data-category')) === 'pending');
    check(
      '会议块显示预订人',
      ((await blockA.textContent()) ?? '').includes('预订人：张三') &&
        ((await blockB.textContent()) ?? '').includes('预订人：李四'),
    );
    check('待审批块有审批提示', ((await blockD.textContent()) ?? '').includes('待审批'));

    // ---------- ⑥ 重叠会议分泳道 ----------
    const [boxA, boxD] = [await blockA.boundingBox(), await blockD.boundingBox()];
    check(
      '重叠会议并排（两块 x 坐标不同）',
      Math.abs(boxA.x - boxD.x) > 2,
      `x=${Math.round(boxA.x)} vs ${Math.round(boxD.x)}`,
    );

    // ---------- ⑧ 参会人按部门树选择 ----------
    // 选下一周周三的一个空闲整点开 Drawer（避开测试数据）
    const nextWed = nextMonday.add(2, 'day');
    const wed = fmt(nextWed);
    let slotHour;
    for (const h of [16, 15, 17, 13, 12]) {
      const res = await fetch(`${API}/rooms/availability`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${userToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          roomIds: [1],
          startTime: `${wed} ${String(h).padStart(2, '0')}:00:00`,
          endTime: `${wed} ${String(h + 1).padStart(2, '0')}:00:00`,
        }),
      });
      if ((await res.json())?.data?.items?.[0]?.available) {
        slotHour = h;
        break;
      }
    }
    check('找到下周空闲整点用于打开预约', slotHour !== undefined, `${wed} ${slotHour}:00`);
    if (slotHour !== undefined) {
      const cell = page.locator(`[data-testid="day-cell-1-${wed}"]`);
      await cell.scrollIntoViewIfNeeded();
      const box = await cell.boundingBox();
      await page.mouse.click(box.x + box.width / 2, box.y + box.height * ((slotHour - 8) / 12) + 4);
      await page.waitForTimeout(700);
      check('预约 Drawer 已打开', (await page.getByText('预约会议').count()) > 0);
      await page.getByRole('button', { name: '选择参会人' }).click();
      await page.waitForTimeout(800);
      check(
        '参会人弹窗打开',
        (await page.locator('.ant-modal-title').filter({ hasText: '选择参会人' }).count()) > 0,
      );
      const modal = page.locator('.ant-modal-content').last();
      await modal.locator('.ant-tree').getByText(/研发部/).first().click();
      await page.waitForTimeout(800);
      const userRow = modal.locator('.ant-list-item', { hasText: '张三' }).first();
      check('研发部列表出现张三', (await userRow.count()) === 1);
      // antd Checkbox 的原生 input 是透明的，用 label 点击（更接近真实用户操作）
      await userRow.locator('label.ant-checkbox-wrapper').click();
      await page.getByRole('button', { name: /确\s*定（1 人）/ }).click();
      await page.waitForTimeout(600);
      check('表单出现已选参会人标签', (await page.locator('.ant-drawer').getByText('张三').count()) > 0);
      await page.locator('.ant-drawer-close').click();
      await page.waitForTimeout(400);
    }

    // ---------- ⑦ 点击会议块进入详情 ----------
    await blockA.click();
    await page.waitForTimeout(800);
    check('点击会议块进入详情', page.url().includes(`/meetings/${idA}`), page.url());
    await page.close();
  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    // 自清理：会议 + 参会人 + 状态日志 + 通知；再删日历缓存
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
    try {
      if (cacheWeekForCleanup) evictCalendarCache(1, cacheWeekForCleanup);
    } catch (e) {
      console.error('清缓存失败:', e.message);
    }
    if (browser) await browser.close();
  }

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n==================== 通过 ${results.length - failed} / 失败 ${failed} ====================`);
  process.exit(failed === 0 ? 0 : 1);
})();
