/**
 * T2.F2 验收：预约表单 Drawer
 * ① 正常创建成功并刷新日历 ② 冲突时段 → 实时红字 + 提交弹冲突详情 ③ 幂等只落一场
 * 前置：后端三服务 + vite dev(5173) 已启动，登录账号 user1
 *
 * 可重复运行：每轮先用 availability 预检，挑一个空闲整点做用例（与 concurrent-*.sh 同一套路），
 * 否则第二次跑会撞上一轮自己创建的会议。
 */
const { chromium } = require('playwright');
const { disableRateLimit } = require('./lib/ratelimit.cjs');

const BASE = 'http://localhost:5173';
const TS = Date.now();
const pad = (v) => String(v).padStart(2, '0');

// 用本周六/周日（未来的日子），避免"今天已过去的钟点"被后端拒（30003）
const MONDAY = (() => {
  const n = new Date();
  const d = new Date(n);
  d.setDate(n.getDate() - (n.getDay() === 0 ? 6 : n.getDay() - 1));
  return d;
})();
const dayOf = (offset) => new Date(MONDAY.getTime() + offset * 86400000);
const md = (d) => `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const iso = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const SAT = dayOf(5);
const SUN = dayOf(6);

/** 时间轴窗口（与 CalendarGrid 的常量保持一致）：08:00–20:00 */
const DAY_START_MIN = 480;
const DAY_TOTAL_MIN = 720;
/** 某整点在格子里的高度占比（用于换算点击 Y） */
const hourRatio = (hour) => (hour * 60 - DAY_START_MIN) / DAY_TOTAL_MIN;

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
  const results = [];
  /** 本轮造出来的会议标题：finally 里按它逐一取消（try 里的 const 在 finally 中不可见） */
  const cleanupTitles = [];
  const check = (name, ok, detail = '') => {
    results.push({ name, ok });
    console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
  };
  const centerX = (b) => b.x + b.width / 2;

  /** 直连后端做机制级断言（复用页面登录态拿到的 token） */
  const api = async (path, init = {}) =>
    page.evaluate(
      async ([p, i]) => {
        const token = localStorage.getItem('meeting.accessToken') || ''; // key 见 web/src/api/token.ts
        const res = await fetch(`/api${p}`, {
          ...i,
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...(i.headers || {}) },
        });
        return res.json();
      },
      [path, init],
    );

  /** 按主题关键字数我创建的会议数（幂等断言用） */
  const countByTitle = async (title) => {
    const r = await api(`/meetings?scope=mine&keyword=${encodeURIComponent(title)}&page=1&size=20`);
    return r.data ? r.data.total : -1;
  };

  const isFree = async (date, hour) => {
    const r = await api('/rooms/availability', {
      method: 'POST',
      body: JSON.stringify({
        roomIds: [1],
        startTime: `${iso(date)} ${pad(hour)}:00:00`,
        endTime: `${iso(date)} ${pad(hour + 1)}:00:00`,
      }),
    });
    return r.data?.items?.[0]?.available === true;
  };

  let restoreRateLimit = async () => {};
  try {
    restoreRateLimit = await disableRateLimit();
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('user1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar');
    await page.waitForTimeout(1500);

    const roomCell = await page.getByText('青竹厅').first().locator('..').boundingBox();
    const satHeader = await page.getByText(new RegExp(`^${md(SAT)}$`)).first().boundingBox();
    const clickX = centerX(satHeader);
    const yAtRatio = (r) => roomCell.y + roomCell.height * r;

    // ---------- 预检：挑一个空闲整点（周六 10:00 起逐小时试）----------
    // 要求 h 与 h-1 **都空闲**：②要在"h 前 30 分钟"处点击开抽屉（预填 [h-0.5h, h+0.5h) 与 ① 的会议冲突），
    // 若 h-1 被占，那个高度会落在会议块上——点到块只会弹"详情见 T2.F3"，抽屉不会开
    let hour = -1;
    for (let h = 10; h <= 16; h++) {
      if ((await isFree(SAT, h - 1)) && (await isFree(SAT, h))) {
        hour = h;
        break;
      }
    }
    check('预检找到周六的空闲整点', hour > 0, hour > 0 ? `${iso(SAT)} ${hour}:00` : '无（周六 10-16 点均被占）');
    if (hour < 0) throw new Error('无可用时段，请先清理周六的测试数据');

    // ---------- ① 正常创建成功并刷新日历 ----------
    const title1 = `E2E预约A-${TS}`;
    cleanupTitles.push(title1);
    await page.mouse.click(clickX, yAtRatio(hourRatio(hour) + 0.02)); // 该整点格内、略微内缩避开相邻块边界
    await page.waitForTimeout(600);
    check('点空格打开预约 Drawer', await page.getByText('预约会议').first().isVisible());
    const roomText = await page.locator('.ant-drawer .ant-select-selection-item').first().textContent().catch(() => '');
    check('Drawer 预填了会议室', (roomText || '').includes('青竹厅'), roomText || '（空）');
    const rangeText = await page.locator('.ant-drawer .ant-picker-input input').first().inputValue().catch(() => '');
    check(`Drawer 预填了时间（${hour}:00 起点）`, (rangeText || '').includes(`${pad(hour)}:00`), rangeText || '（空）');

    await page.getByPlaceholder('如：产品评审会').fill(title1);
    await page.getByRole('button', { name: '提交预约' }).click();
    await page.waitForTimeout(2000);
    check('提交后 Drawer 关闭', !(await page.locator('.ant-drawer-content').isVisible().catch(() => false)));
    check('日历刷新后出现新会议', await page.getByText(title1).first().isVisible());
    check('会议真的落库（按标题查到 1 场）', (await countByTitle(title1)) === 1);

    // ---------- ② 冲突：实时红字提示 + 提交弹冲突详情 ----------
    // 点"目标整点前 30 分钟"的高度 → 预填 [h-0.5, h+0.5)，跨到刚创建的 [h, h+1) 上 → 必冲突；
    // 且这点在会议块之外（块从 h:00 开始），所以能正常打开抽屉而不是点到块
    const title2 = `E2E预约B-${TS}`;
    await page.mouse.click(clickX, yAtRatio(hourRatio(hour) - 0.5 / 12));
    await page.waitForTimeout(1800); // 防抖 500ms + 请求
    check('抽屉被打开（点的是空格而非会议块）', await page.getByText('预约会议').first().isVisible());
    check('实时提示冲突（红字）', await page.getByText('该时段与已有会议冲突').first().isVisible());
    check('冲突提示里列出了占用会议', (await page.locator('.ant-alert-error').first().textContent()).includes(title1));

    await page.getByPlaceholder('如：产品评审会').fill(title2);
    await page.getByRole('button', { name: '提交预约' }).click();
    await page.waitForTimeout(1800);
    const modalText = await page.locator('.ant-modal-content').first().textContent().catch(() => '');
    check('提交冲突 → 弹"时段已被占用"详情弹窗', (modalText || '').includes('时段已被占用'), (modalText || '').slice(0, 30));
    check('弹窗内含冲突会议标题', (modalText || '').includes(title1));
    await page.getByRole('button', { name: '知道了' }).click();
    await page.waitForTimeout(400);
    check('冲突提交未落下会议', (await countByTitle(title2)) === 0);

    // 关闭抽屉（提交失败后它有意留在原地让用户改时间），否则后面的点击会被遮罩挡掉
    await page.locator('.ant-drawer-close').click();
    await page.waitForTimeout(600);
    check('冲突后关闭抽屉可继续操作', !(await page.locator('.ant-drawer-content').isVisible().catch(() => false)));

    // ---------- ③ 幂等：同一 token 提交两次只落一场 ----------
    // 周日也动态挑空闲整点（否则重复运行会撞上一轮自己创建的会议，得到 40001 而不是幂等结果）
    const freeHours = [];
    for (let h = 10; h <= 17; h++) {
      if (await isFree(SUN, h)) freeHours.push(h);
    }
    check('周日找到两个空闲整点（幂等 + 双击各用一个）', freeHours.length >= 2, freeHours.join(', '));
    if (freeHours.length < 2) throw new Error('周日空闲整点不足，请先清理周日的测试数据');
    const [idemHour, dblHour] = freeHours;

    // 机制级：用同一个 X-Idempotency-Token 直打两次创建接口
    const title3 = `E2E幂等-${TS}`;
    cleanupTitles.push(title3);
    const sameToken = `e2e-${TS}-fixed`;
    const body = JSON.stringify({
      title: title3,
      roomId: 1,
      startTime: `${iso(SUN)} ${pad(idemHour)}:00:00`,
      endTime: `${iso(SUN)} ${pad(idemHour + 1)}:00:00`,
    });
    const first = await api('/meetings', { method: 'POST', body, headers: { 'X-Idempotency-Token': sameToken } });
    const second = await api('/meetings', { method: 'POST', body, headers: { 'X-Idempotency-Token': sameToken } });
    check('同 token 第一次 code=0', first.code === 0, `code=${first.code} ${first.msg || ''}`);
    check('同 token 第二次 code=10006（幂等拦截）', second.code === 10006, `code=${second.code} ${second.msg || ''}`);
    check('同 token 两次只落一场会议', (await countByTitle(title3)) === 1, `落库 ${await countByTitle(title3)} 场`);

    // UI 级：打开表单后双击提交按钮
    const sunHeader = await page.getByText(new RegExp(`^${md(SUN)}$`)).first().boundingBox();
    const title4 = `E2E双击-${TS}`;
    cleanupTitles.push(title4);
    await page.mouse.click(centerX(sunHeader), yAtRatio(hourRatio(dblHour) + 0.02)); // 周日动态空闲整点
    await page.waitForTimeout(600);
    await page.getByPlaceholder('如：产品评审会').fill(title4);
    await page.getByRole('button', { name: '提交预约' }).dblclick();
    await page.waitForTimeout(2200);
    check('双击提交只创建一场', (await countByTitle(title4)) === 1, `落库 ${await countByTitle(title4)} 场`);

  } catch (e) {
    console.error('验证中断:', e.message);
    results.push({ name: '执行异常', ok: false });
  } finally {
    await restoreRateLimit();
    // ---------- 收尾自清理：取消本轮造出来的会议，释放时段 ----------
    // 否则每跑一轮就往库里堆 3-4 场，几轮之后预检就找不到空闲时段了（已踩过）。
    // ⚠️ 必须放 finally（2026-10-16 评审 O8）：原来只在成功路径清——
    // playwright 最常见的失败就是某步超时抛异常，那样本轮造的会全留在库里，
    // 多失败两次就把下一轮自己的"预检找空闲时段"堵死（e2e-notify-center 已经这么改过）。
    for (const t of cleanupTitles) {
      try {
        const found = await api(`/meetings?scope=mine&keyword=${encodeURIComponent(t)}&page=1&size=20`);
        for (const m of found.data?.list ?? []) {
          if (m.status === 10 || m.status === 20) {
            await api(`/meetings/${m.id}/cancel`, { method: 'POST', body: JSON.stringify({ reason: 'E2E 自清理' }) });
          }
        }
      } catch (cleanupErr) {
        console.error('自清理失败:', cleanupErr.message);
      }
    }
    console.log('（本轮产生的会议已取消，时段已释放）');
    await browser.close();
  }
  const failed = results.filter((r) => !r.ok);
  console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`);
  process.exit(failed.length ? 1 : 0);
})();
