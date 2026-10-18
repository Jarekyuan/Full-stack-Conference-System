/**
 * T6.1 评审 C3 回归：当天在 RangePicker 里改时间必须真正生效。
 *
 * 背景：`disabledTime` 曾返回 `disabledSeconds: [0..59]`，rc-picker 会校验秒，
 * 导致“今天”的任何时刻都 invalid；界面短暂显示新值，点确定后回退，提交仍是旧时段。
 *
 * 做法：用 Playwright clock 把浏览器时间固定在“今天 10:00”，
 * 打开预约 Drawer 后把时间改成今天 14:00–15:00，断言：
 *   ① 输入框保留新值（没有回退）；
 *   ② 防抖后真的发出 `/rooms/availability`，且参数是今天 14:00。
 * 只发只读请求，不提交、不落库；退出码 0 = 通过。
 *
 * 用法：cd web && node e2e/e2e-booking-today.cjs
 */
const { chromium } = require('playwright');

const BASE = 'http://localhost:5173';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pad = (v) => String(v).padStart(2, '0');

(async () => {
  let browser;
  try {
    const now = new Date();
    now.setHours(10, 0, 0, 0); // 固定“今天 10:00”，保证 14:00 在未来
    const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;

    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    await page.clock.install({ time: now });

    const availability = [];
    page.on('request', (req) => {
      if (req.url().includes('/api/rooms/availability')) availability.push(req.postData());
    });

    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('user1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    await page.waitForTimeout(1200);

    // 从下一周的空格打开 Drawer（预填一个未来时段），再把它改成“今天”
    await page.getByRole('button', { name: '下一周' }).click();
    await page.waitForTimeout(1000);
    const nextMonday = new Date(now);
    nextMonday.setDate(now.getDate() + 7);
    const mondayOffset = (nextMonday.getDay() === 0 ? 6 : nextMonday.getDay() - 1);
    nextMonday.setDate(nextMonday.getDate() - mondayOffset);
    const nextMondayStr = `${nextMonday.getFullYear()}-${pad(nextMonday.getMonth() + 1)}-${pad(nextMonday.getDate())}`;
    const cell = page.locator(`[data-testid="day-cell-1-${nextMondayStr}"]`);
    const box = await cell.boundingBox();
    await page.mouse.click(box.x + box.width / 2, box.y + box.height * ((18 - 8) / 12) + 4);
    await page.waitForTimeout(800);
    check('预约 Drawer 已打开', (await page.getByText('预约会议').count()) > 0);

    const inputs = page.locator('.ant-drawer .ant-picker-range input');
    check('RangePicker 有起止两个输入框', (await inputs.count()) === 2);

    // 依次把起止改成今天 14:00 / 15:00；每次 Enter 后点面板“确定”
    const setInput = async (index, value) => {
      await inputs.nth(index).click();
      await inputs.nth(index).fill(value);
      await page.keyboard.press('Enter');
      await page.waitForTimeout(300);
      const ok = page.locator('.ant-picker-dropdown:visible').getByRole('button', { name: /确\s*定|OK/ });
      if (await ok.count()) await ok.first().click();
      await page.waitForTimeout(300);
    };
    await setInput(0, `${today} 14:00`);
    await setInput(1, `${today} 15:00`);
    await page.clock.runFor(800); // 让 availability 的 500ms 防抖真正触发
    await page.waitForTimeout(500);

    const displayed = await inputs.evaluateAll((els) => els.map((e) => e.value));
    check(
      '今天的新时间被保留（没有静默回退）',
      displayed[0].startsWith(`${today} 14:00`) && displayed[1].startsWith(`${today} 15:00`),
      displayed.join(' / '),
    );
    const hit = availability.some(
      (body) => body && body.includes(`${today} 14:00:00`) && body.includes(`${today} 15:00:00`),
    );
    check('发出可用性检查，且参数是今天 14:00–15:00', hit, availability.join(' | '));

    // 不提交：关闭 Drawer，避免落库
    await page.locator('.ant-drawer-close').click().catch(() => {});
  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    if (browser) await browser.close();
  }
  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n结果：${results.length - failed}/${results.length} 通过`);
  process.exit(failed ? 1 : 0);
})();
