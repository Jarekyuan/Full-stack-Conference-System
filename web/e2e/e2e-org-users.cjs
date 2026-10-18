/**
 * T1.20 验收：组织与用户（部门树 + 成员管理）
 *
 * 覆盖：
 *   ① 部门树可见；ADMIN 菜单有「组织与用户」，user1 无入口且直访被路由守卫弹回
 *   ② 建子部门（总公司 → E2E 部门）→ 树中出现
 *   ③ 建用户（绑定该部门 + USER）→ 成员表出现，部门/角色正确
 *   ④ 选「总公司」+ 含子部门 → 能看到子部门成员；取消勾选 → 看不到（子树筛选真的生效）
 *   ⑤ 编辑用户：换到产品部 + 加 APPROVER → 列表与树计数都更新
 *   ⑥ 部门删除保护：产品部有用户 → 删除被拒；空部门 → 删除成功
 *   ⑦ 启停：Switch 关 → 已停用；再开 → 已启用
 *
 * 前置：四服务 + vite dev(5173) 已启动（auth-service 必须是新代码，含 PUT /users/{id}）。
 * 用法：cd web && node e2e/e2e-org-users.cjs
 * 退出码：0 = 全部断言通过。
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');

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

const findByDeptName = (nodes, name) => {
  for (const node of nodes) {
    if (node.name === name) return node;
    const found = findByDeptName(node.children ?? [], name);
    if (found) return found;
  }
  return null;
};

(async () => {
  const stamp = Date.now();
  const deptName = `E2E部门-${stamp}`;
  const username = `e2e_user_${stamp}`;
  const realName = 'E2E组织用户';
  let browser;
  try {
    const adminToken = await login('admin', 'admin123');
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1512, height: 1000 } });

    // ---------- ① 入口与树 ----------
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('admin');
    await page.getByPlaceholder('请输入密码').fill('admin123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });
    await page.getByText('组织与用户', { exact: true }).click();
    await page.waitForURL('**/admin/users', { timeout: 10000 });
    await page.waitForTimeout(1200);

    check('菜单有「组织与用户」且路由进入成功', page.url().endsWith('/admin/users'));
    const tree = page.locator('.ant-tree');
    check('部门树可见（总公司/研发部）', (await tree.getByText('总公司').count()) > 0 && (await tree.getByText('研发部').count()) > 0);
    // R14.11：自己的状态开关必须禁用（后端 20006 兜底，但 UI 不该给入口）
    const selfRow = page.locator('.ant-table-tbody tr.ant-table-row', { hasText: '系统管理员' }).first();
    check('自己的状态 Switch 禁用', (await selfRow.count()) === 1 && (await selfRow.getByRole('switch').isDisabled()));

    // ---------- ② 建子部门 ----------
    const rootNode = page.locator('.ant-tree-treenode', { hasText: '总公司' }).first();
    await rootNode.getByRole('button').click();
    await page.locator('.ant-dropdown:visible').getByText('新增子部门', { exact: true }).click();
    await page.getByPlaceholder('如：研发部').fill(deptName);
    await page.locator('.ant-modal-content').last().getByRole('button', { name: /保\s*存/ }).click();
    await page.waitForTimeout(1200);
    check('新建子部门出现在树中', (await tree.getByText(deptName).count()) > 0);
    check('新建后自动选中该部门（右侧标题切换）',
      (await page.locator('h2', { hasText: deptName }).count()) > 0);

    // ---------- ③ 建用户 ----------
    await page.getByRole('button', { name: '新增用户' }).click();
    const modal = page.locator('.ant-modal-content').last();
    await page.getByPlaceholder('登录名，创建后不可修改').fill(username);
    await page.getByPlaceholder('6-32 位').fill('e2e_pass123');
    await page.getByPlaceholder('真实姓名').fill(realName);
    // 部门默认已预填为当前选中的部门（弹窗里应能看到部门名）
    check('新增用户弹窗预填了当前部门', ((await modal.textContent()) ?? '').includes(deptName));
    await modal.locator('.ant-select-multiple').click();
    await page.locator('.ant-select-dropdown:visible').getByText('普通用户（USER）', { exact: true }).click();
    await page.keyboard.press('Escape');
    await modal.getByRole('button', { name: /保\s*存/ }).click();
    await page.waitForTimeout(1200);

    const row = page.locator('.ant-table-tbody tr').filter({ hasText: username });
    check('用户出现在成员表', (await row.count()) === 1);
    check('成员表显示绑定部门与 USER 角色',
      ((await row.textContent()) ?? '').includes(deptName) && ((await row.textContent()) ?? '').includes('USER'));

    // 树节点直属人数 = 1（用户已绑定到该部门）。
    // ⚠️ 不从节点文本里猜数字：部门名以时间戳结尾，`endsWith('1')` 可能被名字本身满足（假通过）
    const treeAfterCreate = await apiGet('/depts/tree', adminToken);
    check('树节点直属人数更新为 1', findByDeptName(treeAfterCreate.data, deptName).userCount === 1,
      `userCount=${findByDeptName(treeAfterCreate.data, deptName).userCount}`);

    // ---------- ④ 含子部门 ----------
    await tree.locator('.ant-tree-node-content-wrapper', { hasText: '总公司' }).first().click();
    await page.waitForTimeout(800);
    check('选总公司（含子部门）能看到子部门成员', (await row.count()) === 1);
    await page.getByText('含子部门', { exact: true }).click();
    await page.waitForTimeout(800);
    check('取消“含子部门”后看不到子部门成员（子树筛选生效）', (await row.count()) === 0);
    await page.getByText('含子部门', { exact: true }).click();
    await page.waitForTimeout(800);

    // ---------- ⑤ 编辑用户：换部门 + 加角色 ----------
    const beforeTree = await apiGet('/depts/tree', adminToken);
    const productBefore = findByDeptName(beforeTree.data, '产品部').userCount;
    await row.getByRole('button', { name: /编\s*辑/ }).click();
    const editModal = page.locator('.ant-modal-content').last();
    await editModal.locator('.ant-tree-select').click();
    await page.locator('.ant-select-dropdown:visible').getByText('产品部', { exact: true }).click();
    await editModal.locator('.ant-select-multiple').click();
    await page.locator('.ant-select-dropdown:visible').getByText('审批人（APPROVER）', { exact: true }).click();
    await page.keyboard.press('Escape');
    await editModal.getByRole('button', { name: /保\s*存/ }).click();
    await page.waitForTimeout(1200);

    check('编辑后部门变为产品部', ((await row.textContent()) ?? '').includes('产品部'));
    check('编辑后角色包含 APPROVER', ((await row.textContent()) ?? '').includes('APPROVER'));
    const afterTree = await apiGet('/depts/tree', adminToken);
    check('树计数：原部门 0、产品部 +1',
      findByDeptName(afterTree.data, deptName).userCount === 0 &&
        findByDeptName(afterTree.data, '产品部').userCount === productBefore + 1,
      `原部门=${findByDeptName(afterTree.data, deptName).userCount} 产品部 ${productBefore}→${findByDeptName(afterTree.data, '产品部').userCount}`);

    // ---------- ⑦ 启停（先做，方便后面删除部门时用户已不在原部门） ----------
    const switchInRow = row.getByRole('switch');
    await switchInRow.click();
    await page.waitForTimeout(1000);
    check('停用生效', (await page.getByText('已停用', { exact: true }).count()) > 0 && !(await switchInRow.isChecked()));
    await switchInRow.click();
    await page.waitForTimeout(1000);
    check('重新启用生效', (await page.getByText('已启用', { exact: true }).count()) > 0 && (await switchInRow.isChecked()));

    // ---------- ⑥ 删除保护 + 删除空部门 ----------
    // 产品部有用户 → 拒绝
    await tree.locator('.ant-tree-node-content-wrapper', { hasText: '产品部' }).first().click();
    await page.waitForTimeout(600);
    await page.getByRole('button', { name: '删除部门' }).click();
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /删\s*除/ }).click();
    await page.waitForTimeout(1000);
    check('有用户的部门删除被拒', (await page.getByText(/部门下存在用户/).count()) > 0);
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /取\s*消/ }).click();
    await page.waitForTimeout(400);

    // R14.11：有子部门的部门 → 拒绝（总公司下有本次 E2E 子部门）
    await tree.locator('.ant-tree-node-content-wrapper', { hasText: '总公司' }).first().click();
    await page.waitForTimeout(600);
    await page.getByRole('button', { name: '删除部门' }).click();
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /删\s*除/ }).click();
    await page.waitForTimeout(1000);
    check('有子部门的部门删除被拒', (await page.getByText(/存在子部门/).count()) > 0);
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /取\s*消/ }).click();
    await page.waitForTimeout(400);

    // 空部门（用户已移走）→ 删除成功
    await tree.locator('.ant-tree-node-content-wrapper', { hasText: deptName }).first().click();
    await page.waitForTimeout(600);
    await page.getByRole('button', { name: '删除部门' }).click();
    await page.locator('.ant-modal-confirm').getByRole('button', { name: /删\s*除/ }).click();
    await page.waitForTimeout(1200);
    check('空部门删除成功', (await tree.getByText(deptName).count()) === 0);

    // ---------- ① user1 无入口 ----------
    const ctx2 = await browser.newContext();
    const p2 = await ctx2.newPage();
    await p2.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await p2.getByPlaceholder('请输入用户名').fill('user1');
    await p2.getByPlaceholder('请输入密码').fill('user123');
    await p2.locator('button[type="submit"]').click();
    await p2.waitForURL('**/calendar', { timeout: 10000 });
    check('user1 菜单无「组织与用户」', (await p2.getByText('组织与用户', { exact: true }).count()) === 0);
    await p2.goto(`${BASE}/admin/users`, { waitUntil: 'networkidle' });
    await p2.waitForTimeout(800);
    check('user1 直访 /admin/users 被弹回日历', p2.url().endsWith('/calendar'), p2.url());
    await ctx2.close();
    await page.close();
  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    // 自清理：UI 失败时也可能已经建了用户/部门。先删用户角色关联，再删用户与部门。
    try {
      sql(`USE smt_meeting;
           DELETE ur FROM sys_user_role ur
             JOIN sys_user u ON u.id = ur.user_id
            WHERE u.username='${username}';
           DELETE FROM sys_user WHERE username='${username}';
           DELETE FROM sys_dept WHERE name='${deptName}';`);
      check('清理完成（用户与测试部门均无残留）',
        sql(`SELECT COUNT(*) FROM smt_meeting.sys_user WHERE username='${username}';`) === '0' &&
          sql(`SELECT COUNT(*) FROM smt_meeting.sys_dept WHERE name='${deptName}';`) === '0');
    } catch (cleanupErr) {
      console.error('自清理失败:', cleanupErr.message);
    }
    if (browser) await browser.close();
  }

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n==================== 通过 ${results.length - failed} / 失败 ${failed} ====================`);
  process.exit(failed === 0 ? 0 : 1);
})();
