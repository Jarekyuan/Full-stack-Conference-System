/**
 * T6.3 验收：暗黑模式（三态切换 / 持久化 / 跟随系统 / 登录页）
 *
 * 覆盖：
 *   ① 默认跟随系统（系统亮 → 亮色）
 *   ② 手动切暗色 → <html class="dark"> + data-theme=dark + 背景变暗
 *   ③ 刷新后仍是暗色（localStorage 持久化 + 防闪烁内联脚本）
 *   ④ 跟随系统：系统暗 → 暗；系统亮 → 亮（监听 matchMedia 变化）
 *   ⑤ 手动亮色覆盖系统暗（手动选择优先）
 *   ⑥ 登录页同样适配，且有切换入口
 *
 * 前置：四服务 + vite dev(5173) 已启动。
 * 用法：cd web && node e2e/e2e-theme.cjs
 */
const { chromium } = require('playwright');

const BASE = 'http://localhost:5173';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function isDark(page) {
  return page.evaluate(() => document.documentElement.classList.contains('dark'));
}
async function useMode(page, label) {
  await page.getByRole('button', { name: '切换主题' }).click();
  await page.locator('.ant-dropdown:visible').getByText(label, { exact: false }).first().click();
  await sleep(600);
}

(async () => {
  let browser;
  try {
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    // 初始系统亮色（浏览器默认），验证“默认跟随系统”
    await page.emulateMedia({ colorScheme: 'light' });
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('user1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    await sleep(1200);

    // ① 默认 system + 系统亮 → 亮色
    const initialMode = await page.evaluate(
      () => JSON.parse(localStorage.getItem('meeting.theme') || '{"state":{"mode":"system"}}').state.mode,
    );
    check('默认模式为跟随系统', initialMode === 'system', initialMode);
    check('系统亮色时页面不是暗色', !(await isDark(page)));

    // ② 手动暗色
    await useMode(page, '暗色');
    check('切暗色后 html.dark', await isDark(page));
    const themeAttr = await page.evaluate(() => document.documentElement.dataset.theme);
    check('data-theme=dark', themeAttr === 'dark', themeAttr);
    const bodyBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    check('body 背景变暗', bodyBg === 'rgb(2, 6, 23)', bodyBg);
    const siderBg = await page.locator('.ant-layout-sider').evaluate((el) => getComputedStyle(el).backgroundColor);
    check('Sider 背景不是白色', siderBg !== 'rgb(255, 255, 255)', siderBg);

    // ③ 刷新持久化（内联脚本在 React 挂载前就应设置 dark）
    await page.reload({ waitUntil: 'networkidle' });
    await sleep(1000);
    check('刷新后仍是暗色（持久化）', await isDark(page));
    const storedMode = await page.evaluate(
      () => JSON.parse(localStorage.getItem('meeting.theme') || '{}').state?.mode,
    );
    check('localStorage 记住 dark 模式', storedMode === 'dark', storedMode);

    // ④ 跟随系统：系统暗 ↔ 系统亮
    await useMode(page, '跟随系统');
    await page.emulateMedia({ colorScheme: 'dark' });
    await sleep(700);
    check('跟随系统：系统暗 → 页面暗', await isDark(page));
    await page.emulateMedia({ colorScheme: 'light' });
    await sleep(700);
    check('跟随系统：系统亮 → 页面亮', !(await isDark(page)));

    // ⑤ 手动亮色覆盖系统暗
    await useMode(page, '亮色');
    await page.emulateMedia({ colorScheme: 'dark' });
    await sleep(700);
    check('手动亮色覆盖系统暗', !(await isDark(page)));

    // ⑥ 登录页适配 + 切换入口（当前已登录，直接访问登录页）
    await page.evaluate(() => localStorage.setItem('meeting.theme', JSON.stringify({ state: { mode: 'dark' }, version: 0 })));
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await sleep(700);
    check('登录页也能是暗色', await isDark(page));
    check('登录页有切换入口', (await page.getByRole('button', { name: '切换主题' }).count()) > 0);

    await page.close();
  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    if (browser) await browser.close();
  }
  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n结果：${results.length - failed}/${results.length} 通过`);
  process.exit(failed ? 1 : 0);
})();
