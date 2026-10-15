/**
 * T2.F3 验收：我的会议列表 + 详情
 * ① 列表筛选分页正常 ② 详情时间线显示状态流转 ③ 取消后状态即变 50（+ 取消按钮仅组织人可见）
 * 前置：后端三服务 + vite dev(5173) 已启动
 *
 * 可重复运行：每次用唯一标题建一场"待取消"的会议（时刻由 availability 预检挑空闲），
 * 验收完就地取消掉，不堆积数据。
 */
const { chromium } = require('playwright');

const BASE = 'http://localhost:5173';
const TS = Date.now();
const pad = (v) => String(v).padStart(2, '0');

const MONDAY = (() => {
  const n = new Date();
  const d = new Date(n);
  d.setDate(n.getDate() - (n.getDay() === 0 ? 6 : n.getDay() - 1));
  return d;
})();
const dayOf = (o) => new Date(MONDAY.getTime() + o * 86400000);
const iso = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const SUN = dayOf(6); // 周日（未来），避免"今天已过去的钟点"被拒

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
  const results = [];
  const check = (name, ok, detail = '') => {
    results.push({ name, ok });
    console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
  };

  const api = async (path, init = {}) =>
    page.evaluate(
      async ([p, i]) => {
        const token = localStorage.getItem('meeting.accessToken') || '';
        const res = await fetch(`/api${p}`, {
          ...i,
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...(i.headers || {}) },
        });
        return res.json();
      },
      [path, init],
    );

  const login = async (p, username, password) => {
    await p.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await p.getByPlaceholder('请输入用户名').fill(username);
    await p.getByPlaceholder('请输入密码').fill(password);
    await p.locator('button[type="submit"]').click();
    await p.waitForURL('**/calendar', { timeout: 10000 });
  };

  const firstRowTitle = () => page.locator('.ant-table-tbody tr').first().locator('td').first().textContent();

  try {
    await login(page, 'user1', 'user123');

    // 准备一场本次专用的会议（②③ 的目标；挑周日空闲整点）
    let hour = -1;
    for (let h = 10; h <= 17; h++) {
      const r = await api('/rooms/availability', {
        method: 'POST',
        body: JSON.stringify({
          roomIds: [1],
          startTime: `${iso(SUN)} ${pad(h)}:00:00`,
          endTime: `${iso(SUN)} ${pad(h + 1)}:00:00`,
        }),
      });
      if (r.data?.items?.[0]?.available) {
        hour = h;
        break;
      }
    }
    check('预检找到周日空闲整点', hour > 0, hour > 0 ? `${iso(SUN)} ${hour}:00` : '无');
    if (hour < 0) throw new Error('周日无空闲时段，请先清理测试数据');

    const title = `E2E详情-${TS}`;
    const created = await api('/meetings', {
      method: 'POST',
      body: JSON.stringify({
        title,
        roomId: 1,
        startTime: `${iso(SUN)} ${pad(hour)}:00:00`,
        endTime: `${iso(SUN)} ${pad(hour + 1)}:00:00`,
      }),
      headers: { 'X-Idempotency-Token': `e2e-detail-${TS}` },
    });
    check('前置：创建待验收会议', created.code === 0, `id=${created.data?.meetingId}`);
    const meetingId = created.data?.meetingId;

    // ---------- ① 列表筛选 + 分页 ----------
    await page.goto(`${BASE}/meetings`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);
    const rowCount = await page.locator('.ant-table-tbody tr.ant-table-row').count();
    check('列表渲染出数据行', rowCount > 0, `${rowCount} 行`);
    const totalText = await page.locator('.ant-pagination-total-text').textContent().catch(() => '');
    check('显示总条数', /共\s*\d+\s*条/.test(totalText || ''), totalText || '');

    const page1First = (await firstRowTitle()) || '';
    await page.locator('.ant-pagination-item-2').click();
    await page.waitForTimeout(1000);
    const page2First = (await firstRowTitle()) || '';
    check('翻页后数据变化', page1First !== page2First && !!page2First, `第1页「${page1First.slice(0, 14)}」→ 第2页「${page2First.slice(0, 14)}」`);
    await page.locator('.ant-pagination-item-1').click();
    await page.waitForTimeout(800);

    // 状态筛选：选"待审批" → 表内所有标签都应是待审批，且条数与接口一致
    await page.locator('.ant-select').first().click();
    await page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: '待审批' }).first().click();
    await page.waitForTimeout(1200);
    await page.keyboard.press('Escape'); // 收起下拉，避免挡住表格
    const tags = await page.locator('.ant-table-tbody .ant-tag').allTextContents();
    check('状态筛选后全部为「待审批」', tags.length > 0 && tags.every((t) => t.includes('待审批')), tags.slice(0, 3).join(',') + `（共 ${tags.length} 个）`);
    const pendingTotal = await api('/meetings?scope=mine&status=10&page=1&size=10');
    const filterTotalText = await page.locator('.ant-pagination-total-text').textContent().catch(() => '');
    check('筛选条数与接口一致', (filterTotalText || '').includes(String(pendingTotal.data.total)), `页面「${filterTotalText}」接口 total=${pendingTotal.data.total}`);

    // ---------- ② 详情时间线 ----------
    await page.goto(`${BASE}/meetings/${meetingId}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    const detailText = await page.textContent('body');
    check('详情展示会议主题与会议室', detailText.includes(title) && detailText.includes('青竹厅'));
    check('详情展示参会人区块', detailText.includes('参会人'));
    check('时间线含「创建预约」', detailText.includes('创建预约'));
    check('时间线含状态流转「（无） → 已预约」', detailText.includes('（无） → 已预约'));
    check('组织人可见取消按钮（本场由 user1 创建）', await page.getByRole('button', { name: '取消会议' }).isVisible());

    // ---------- ③ 取消 → 状态即变 50 ----------
    await page.getByRole('button', { name: '取消会议' }).click();
    await page.waitForTimeout(500);
    await page.locator('.ant-modal-content textarea').fill('E2E 验收：议题变更');
    await page.getByRole('button', { name: '确认取消' }).click();
    await page.waitForTimeout(1500);

    const afterText = await page.textContent('body');
    check('取消后页面状态标签变「已取消」', afterText.includes('已取消'));
    check('时间线新增「取消会议」', afterText.includes('取消会议'));
    check('时间线显示「已预约 → 已取消」', afterText.includes('已预约 → 已取消'));
    const afterDetail = await api(`/meetings/${meetingId}`);
    check('接口复查状态 = 50', afterDetail.data?.status === 50, `status=${afterDetail.data?.status}`);
    check('取消原因已记录', (afterDetail.data?.cancelReason || '').includes('议题变更'), afterDetail.data?.cancelReason || '');

    // ---------- 权限：非组织人看不到取消按钮 ----------
    const page2 = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    await login(page2, 'user2', 'user123');
    await page2.goto(`${BASE}/meetings/${meetingId}`, { waitUntil: 'networkidle' });
    await page2.waitForTimeout(1000);
    const otherText = await page2.textContent('body');
    check('user2 能看到该会议详情（公开可查）', otherText.includes(title));
    // 注意：不能用整页文本判断——时间线里的"取消会议"是事件文案，会误伤；要精确断言"按钮不存在"
    check(
      'user2 看不到「取消会议」按钮（仅组织人）',
      (await page2.getByRole('button', { name: '取消会议' }).count()) === 0,
    );
    await page2.close();
  } catch (e) {
    console.error('验证中断:', e.message);
    await browser.close();
    process.exit(1);
  }

  await browser.close();
  const failed = results.filter((r) => !r.ok);
  console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`);
  process.exit(failed.length ? 1 : 0);
})();
