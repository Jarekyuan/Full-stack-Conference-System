/**
 * T2.F1 验收：日历页（周切换 / 会议落格 / 点空格带出预填时间）
 * 前置：后端三服务 + vite dev(5173) 已启动；10-16 造有「产品周会」(room1, 已预约) 与「需求评审会」(room3, 待审批)
 */
const { chromium } = require('playwright');

const BASE = 'http://localhost:5173';

const fmt = (d) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

(async () => {
  const now = new Date();
  const dow = now.getDay(); // 0=周日
  const monday = new Date(now);
  monday.setDate(now.getDate() - (dow === 0 ? 6 : dow - 1));
  const thisMonday = fmt(monday);
  const nextMonday = fmt(new Date(monday.getTime() + 7 * 86400000));
  const md = (d) => `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const friDate = md(new Date(monday.getTime() + 4 * 86400000)); // 本周五（表头按 MM-DD 渲染）
  const thuDate = md(new Date(monday.getTime() + 3 * 86400000)); // 本周四（今天，空格）

  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
  const results = [];
  const check = (name, ok, detail = '') => {
    results.push({ name, ok });
    console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
  };
  const centerX = (b) => b.x + b.width / 2;
  const centerY = (b) => b.y + b.height / 2;

  try {
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
    check('下一周不再显示本周会议（产品周会）', !body2.includes('产品周会'));

    // 注意：antd 会在两个汉字的按钮文字间自动插空格（"本 周"），故用正则匹配
    await page.getByRole('button', { name: /本\s*周/ }).click();
    await page.waitForTimeout(1200);
    const body3 = await page.textContent('body');
    check('点"本周"切回本周', body3.includes(thisMonday), thisMonday);

    // ---------- ② 已创建会议显示在正确格子 ----------
    const weekMeeting = page.getByText('产品周会').first();
    check('本周网格显示「产品周会」', await weekMeeting.isVisible());
    check('本周网格显示「需求评审会」', await page.getByText('需求评审会').first().isVisible());

    // 位置断言：会议块的横向中心落在"本周五"那一列内
    const friHeader = await page.getByText(friDate, { exact: true }).first().boundingBox();
    const blockBox = await weekMeeting.boundingBox();
    check(
      '「产品周会」落在周五那一列',
      !!friHeader && !!blockBox && centerX(blockBox) >= friHeader.x && centerX(blockBox) <= friHeader.x + friHeader.width,
      `块中心x=${blockBox && Math.round(centerX(blockBox))} 列范围=[${friHeader && Math.round(friHeader.x)}, ${friHeader && Math.round(friHeader.x + friHeader.width)}]`,
    );

    // 位置断言：会议块纵向落在"青竹厅"那一行内（且 10:00 场次应在上半部）
    // 注意：getByText 命中的是姓名文本行（约 20px），要取父节点才是被拉伸到 240px 的整行单元格
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

    // ---------- ③ 点空格带出预填时间（T2.F2 起：直接开预约 Drawer）----------
    // 点"青竹厅 × 本周四"的空格，高度取 25% → 08:00 + 12h*25% = 11:00（吸附后 11:00~12:00）
    const thuHeader = await page.getByText(thuDate, { exact: true }).first().boundingBox();
    await page.mouse.click(centerX(thuHeader), roomCell.y + roomCell.height * 0.25);
    await page.waitForTimeout(700);
    check('点空格打开预约 Drawer', await page.getByText('预约会议').first().isVisible());
    const startInput = await page.locator('.ant-drawer .ant-picker-input input').nth(0).inputValue().catch(() => '');
    const endInput = await page.locator('.ant-drawer .ant-picker-input input').nth(1).inputValue().catch(() => '');
    check(
      '预填时间符合点击高度（25% → 11:00~12:00）',
      (startInput || '').includes('11:00') && (endInput || '').includes('12:00'),
      `${startInput} ~ ${endInput}`,
    );
    await page.locator('.ant-drawer-close').click();
    await page.waitForTimeout(400);
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
