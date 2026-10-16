/**
 * T3.F1 / T3.F2 验收：审批中心 + 详情页延长/提前结束
 * ① user1 约红枫厅（需审批）→ approver1 在审批中心看到待办 → 抽屉里通过 → 待办消失、已办出现
 * ② 拒绝路径：无理由时前端先拦（有理由才提交）
 * ③ 进行中会议：详情页出现「延长会议 / 提前结束」→ 延长弹窗展示服务端算的上限 → 延长成功 → 提前结束
 *
 * 前置：后端三服务 + vite dev(5173) 已启动；**定时任务开着**（③ 需要会议自己走到"进行中"）
 * 可重复运行：跑完把造出来的会议取消/结束掉。
 */
const { chromium } = require('playwright');
const { disableRateLimit } = require('./lib/ratelimit.cjs');

const BASE = 'http://localhost:5173';
const ROOM_ID = 5;          // 红枫厅：need_approval=1
const APPROVAL_ROOM = 5;
const pad = (v) => String(v).padStart(2, '0');
const iso = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const at = (d, h, m = 0) => `${iso(d)} ${pad(h)}:${pad(m)}:00`;
const dayAfter = (n) => new Date(Date.now() + n * 86400000);

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const results = [];
  const check = (name, ok, detail = '') => {
    results.push({ name, ok });
    console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
  };

  const ctx = await browser.newContext({ viewport: { width: 1512, height: 1100 } });
  const page = await ctx.newPage();
  const page2 = await ctx.newPage();

  const login = async (p, username, password) => {
    await p.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await p.getByPlaceholder('请输入用户名').fill(username);
    await p.getByPlaceholder('请输入密码').fill(password);
    await p.locator('button[type="submit"]').click();
    await p.waitForURL('**/calendar');
    await p.waitForTimeout(800);
  };
  /** 直连后端（复用当前页面的登录态） */
  const api = async (p, path, init = {}) =>
    p.evaluate(
      async ([pa, i]) => {
        const token = localStorage.getItem('meeting.accessToken') || '';
        const res = await fetch(`/api${pa}`, {
          ...i,
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...(i.headers || {}) },
        });
        return res.json();
      },
      [path, init],
    );
  const booking = (title, start, end, roomId = ROOM_ID) => ({
    method: 'POST',
    headers: { 'X-Idempotency-Token': `e2e-approval-${Date.now()}-${Math.random()}` },
    body: JSON.stringify({ title, roomId, startTime: start, endTime: end, remark: 'E2E 审批验收用' }),
  });
  const created = [];
  const cleanup = async () => {
    for (const id of created) {
      await api(page, `/meetings/${id}/cancel`, { method: 'POST', body: JSON.stringify({ reason: 'E2E 清理' }) });
      await api(page, `/meetings/${id}/finish`, { method: 'POST' });
    }
  };

  let restoreRateLimit = async () => {};
  try {
    restoreRateLimit = await disableRateLimit();
    await login(page, 'user1', 'user123');
    await login(page2, 'approver1', 'user123');

    // ---------- 找一个空闲时段 ----------
    const d = dayAfter(2);
    let slot = null;
    for (let h = 9; h <= 17 && !slot; h++) {
      const s = at(d, h);
      const e = at(d, h + 1);
      const r = await api(page, '/rooms/availability', {
        method: 'POST',
        body: JSON.stringify({ roomIds: [APPROVAL_ROOM], startTime: s, endTime: e }),
      });
      if (r.data?.items?.[0]?.available) slot = { s, e };
    }
    check('预检找到红枫厅的空闲时段', !!slot, slot ? `${slot.s} ~ ${slot.e}` : '无');
    if (!slot) throw new Error('无可用时段');

    // ---------- ① 提交待审批预约 ----------
    const pendingTitle = `E2E审批-待办-${Date.now()}`;
    const created1 = await api(page, '/meetings', booking(pendingTitle, slot.s, slot.e));
    const mid = created1.data?.meetingId;
    if (mid) created.push(mid);
    check('需审批会议室 → 提交后状态 10', created1.data?.status === 10, `status=${created1.data?.status}`);

    // ---------- ② 审批人在审批中心看到待办 ----------
    await page2.goto(`${BASE}/approvals`, { waitUntil: 'networkidle' });
    await page2.waitForTimeout(1200);
    check('审批中心默认停在「待审批」页签', await page2.getByRole('tab', { name: /待审批/ }).first().isVisible());
    const row = page2.locator('tr', { hasText: pendingTitle }).first();
    check('待办列表里能看到这场申请', await row.isVisible());
    check('待办行展示用途备注（审批人的判断依据）', (await row.textContent()).includes('E2E 审批验收用'));

    // ---------- ③ 抽屉里拒绝：无理由先被拦 ----------
    await row.getByRole('button', { name: /审\s*批/ }).click();
    await page2.waitForTimeout(600);
    check('抽屉打开并展示会议信息', await page2.getByText('用途', { exact: false }).first().isVisible());
    await page2.getByRole('button', { name: /拒\s*绝/ }).click();
    await page2.waitForTimeout(300);
    check('无理由拒绝 → 前端先拦下（不发请求）', await page2.getByText('拒绝必须填写理由').isVisible());

    // ---------- ④ 通过（带意见） ----------
    await page2.locator('.ant-drawer textarea').fill('E2E：同意');
    await page2.getByRole('button', { name: /通\s*过/ }).click();
    await page2.waitForTimeout(2000);
    check('通过后抽屉关闭', !(await page2.locator('.ant-drawer-content').isVisible().catch(() => false)));
    check('通过后状态变 20（接口复查）', (await api(page2, `/meetings/${mid}`)).data?.status === 20);
    check('待办列表里不再有它', !(await page2.locator('tr', { hasText: pendingTitle }).first().isVisible().catch(() => false)));

    // ---------- ⑤ 已处理页签 ----------
    await page2.getByRole('tab', { name: '已处理' }).click();
    await page2.waitForTimeout(1200);
    const doneRow = page2.locator('tr', { hasText: pendingTitle }).first();
    check('已处理列表里能看到这场', await doneRow.isVisible());
    const doneText = (await doneRow.textContent()) || '';
    check('已处理行标出「已通过」与我的意见', doneText.includes('已通过') && doneText.includes('E2E：同意'));
    check('已处理行展示会议现状（不是审批时的状态截图）', doneText.includes('已预约'));

    // ---------- ⑥ T3.F2：进行中会议 → 延长 / 提前结束 ----------
    // 造一场 2 分钟后开始的会议（后端拒"过去时间"，所以要等它自己走到 30）
    const startAt = new Date(Date.now() + 2 * 60000);
    const endAt = new Date(Date.now() + 32 * 60000);
    const fmt = (x) => `${iso(x)} ${pad(x.getHours())}:${pad(x.getMinutes())}:00`;
    const liveTitle = `E2E-进行中-${Date.now()}`;
    // 红枫厅需审批，走不到 30；换成免审批的云杉厅（roomId=6）
    const created2 = await api(page, '/meetings', booking(liveTitle, fmt(startAt), fmt(endAt), 6));
    const mid2 = created2.data?.meetingId;
    if (mid2) created.push(mid2);
    check('第二场（免审会议室）创建成功', !!mid2);

    process.stdout.write('  等待会议到点进入「进行中」（最多 3 分钟，看定时任务）');
    let ongoing = false;
    for (let i = 0; i < 36; i++) {
      await page.waitForTimeout(5000);
      process.stdout.write('.');
      if ((await api(page, `/meetings/${mid2}`)).data?.status === 30) { ongoing = true; break; }
    }
    process.stdout.write('\n');
    check('定时任务把会议推进到进行中(30)', ongoing);

    await page.goto(`${BASE}/meetings/${mid2}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);
    check('详情页出现「延长会议」按钮', await page.getByRole('button', { name: '延长会议' }).isVisible());
    check('详情页出现「提前结束」按钮', await page.getByRole('button', { name: '提前结束' }).isVisible());
    check('进行中仍**不能**取消（状态机禁止 30 → 50）',
      !(await page.getByRole('button', { name: /取消会议/ }).isVisible().catch(() => false)));

    await page.getByRole('button', { name: '延长会议' }).click();
    await page.waitForTimeout(1200);
    const modalText = (await page.locator('.ant-modal-content').first().textContent()) || '';
    check('延长弹窗展示服务端算的上限（R-15）', /最多可延长\s*\d+\s*分钟/.test(modalText), modalText.replace(/\s+/g, ' ').slice(0, 90));

    const beforeEnd = (await api(page, `/meetings/${mid2}`)).data?.endTime;
    await page.locator('.ant-modal-content .ant-input-number-input').fill('15');
    await page.getByRole('button', { name: '确认延长' }).click();
    await page.waitForTimeout(2000);
    const afterEnd = (await api(page, `/meetings/${mid2}`)).data?.endTime;
    check('延长 15 分钟后 end_time 真的变了', beforeEnd !== afterEnd, `${beforeEnd} → ${afterEnd}`);
    // 断言必须限定在时间线容器内：此时页面上还挂着「延长会议」**按钮**，
    // 用整页文本包含会恒真（假保险），等于这条断言从来没验过东西
    const timelineText = (await page.locator('.ant-timeline').first().textContent().catch(() => '')) || '';
    check('时间线里出现「延长会议」事件', timelineText.includes('延长会议'));

    await page.getByRole('button', { name: '提前结束' }).click();
    await page.waitForTimeout(600);
    await page.getByRole('button', { name: '确认结束' }).click();
    await page.waitForTimeout(2000);
    check('提前结束后状态变 40', (await api(page, `/meetings/${mid2}`)).data?.status === 40);
    check('结束后「延长/提前结束」按钮消失',
      !(await page.getByRole('button', { name: '延长会议' }).isVisible().catch(() => false)));
  } catch (e) {
    check(`脚本异常：${e.message}`, false);
  } finally {
    await restoreRateLimit();
    await cleanup();
    await browser.close();
    const failed = results.filter((r) => !r.ok);
    console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`);
    process.exit(failed.length ? 1 : 0);
  }
})();
