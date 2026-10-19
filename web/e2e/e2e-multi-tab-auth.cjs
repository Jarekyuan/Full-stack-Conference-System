/**
 * 多 tab 独立会话验证：同一个浏览器上下文（共享 localStorage）开两个 tab，
 * 分别登录 user1 / admin，互不覆盖；刷新后仍各自保持自己的用户。
 *
 * 前置：四个后端服务 + Vite dev server 已启动。
 */
const { chromium } = require('playwright');

const BASE = 'http://localhost:5173';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};

async function login(page, username, password) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('请输入用户名').fill(username);
  await page.getByPlaceholder('请输入密码').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/calendar', { timeout: 10000 });
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  // 同一个 context：localStorage 共享，sessionStorage 按 tab 隔离
  const context = await browser.newContext();
  const page1 = await context.newPage();
  const page2 = await context.newPage();
  try {
    await login(page1, 'user1', 'user123');
    await login(page2, 'admin', 'admin123');

    const [token1, token2] = await Promise.all([
      page1.evaluate(() => sessionStorage.getItem('meeting.accessToken')),
      page2.evaluate(() => sessionStorage.getItem('meeting.accessToken')),
    ]);
    check('两个 tab 的 accessToken 不同', !!token1 && !!token2 && token1 !== token2);

    await Promise.all([
      page1.reload({ waitUntil: 'networkidle' }),
      page2.reload({ waitUntil: 'networkidle' }),
    ]);
    const body1 = await page1.locator('body').innerText();
    const body2 = await page2.locator('body').innerText();
    check('tab1 刷新后仍是 user1', page1.url().includes('/calendar') && body1.includes('张三'));
    check(
      'tab2 刷新后仍是 admin',
      page2.url().includes('/calendar') && body2.includes('系统管理员'),
    );
  } catch (e) {
    check('脚本执行', false, e.message);
  } finally {
    await browser.close();
    const failed = results.filter((r) => !r.ok);
    console.log(`\n${results.length - failed.length}/${results.length} passed`);
    process.exit(failed.length === 0 ? 0 : 1);
  }
})();
