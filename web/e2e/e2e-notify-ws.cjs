/**
 * T4.6 验收：通知 WebSocket 实时推送
 *
 * 用**真浏览器**开 WS（而不是 curl/手搓帧）：`new WebSocket()` 的行为、握手被拒的表现、
 * 多标签页各自收到——这些只有真浏览器才作数；而且"token 只能走 query"这条约束本身
 * 就是浏览器 API 决定的（不能自定义请求头），用别的客户端测等于绕开了要验的东西。
 *
 * 覆盖：
 *   ① 带 token 连接成功；不带 token **连不上**（网关 401 拒绝升级）
 *   ② 应用层心跳 PING → PONG
 *   ③ 另一账号产生通知时**本端收不到**（用户隔离）
 *   ④ 本账号产生通知 → 两个标签页**都**收到 NOTIFICATION + UNREAD_COUNT
 *   ⑤ 推送体带 id，且该 id 能在 REST 列表里查到（主键回填 + "落库优先"）
 *   ⑥ 推送的 unreadCount 与 REST 的未读数一致（权威值，前端不必自己 +1）
 *   ⑦ 关掉一个标签页后，推送只到剩下的那个（会话注销生效）
 *
 * 前置：三服务 + notification-service（8083）+ **前端 dev server（5173，用于起页面）**已启动；
 *      网关是含 `?token=` 支持的版本。
 * 运行：cd web && node e2e/e2e-notify-ws.cjs      （需要先 `npm i -D playwright`）
 * 退出码：0 = 全部断言通过。
 */
const { chromium } = require('playwright');
const { disableRateLimit } = require('./lib/ratelimit.cjs');
const { spawn } = require('child_process');
const { ensureNotificationService } = require('./lib/notify-service.cjs');
const { execSync } = require('child_process');

const API = 'http://localhost:8080/api';
const WS_URL = 'ws://localhost:8080/ws/notifications';
const MYSQL = 'docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e';

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};

const sql = (query) =>
  execSync(`${MYSQL} ${JSON.stringify(query)}`, {
    encoding: 'utf8',
    // stderr 丢掉：mysql 客户端的 "Using a password on the command line" 警告会刷屏（不是错误）
    stdio: ['ignore', 'pipe', 'ignore'],
  })
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.includes('Using a password'))
    .join('\n');

const login = async (username, password) => {
  const res = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const body = await res.json();
  if (body.code !== 0) throw new Error(`登录失败 ${username}: ${body.msg}`);
  return body.data.accessToken;
};

const api = async (path, token, options = {}) => {
  const res = await fetch(`${API}${path}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-Idempotency-Token': crypto.randomUUID(),
      ...(options.headers || {}),
    },
  });
  return res.json();
};

// 造一场"当前空闲"的会议：从 room 6（云杉厅，免审批）往后找第一个整点空闲时段
const freeSlot = async (token, roomId) => {
  const pad = (n) => String(n).padStart(2, '0');
  for (let day = 1; day <= 10; day++) {
    const d = new Date(Date.now() + day * 86400000);
    const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const startTime = `${date} 09:00:00`;
    const endTime = `${date} 10:00:00`;
    const res = await api('/rooms/availability', token, {
      method: 'POST',
      body: JSON.stringify({ roomIds: [roomId], startTime, endTime }),
    });
    if (res?.data?.items?.[0]?.available) return { startTime, endTime };
  }
  throw new Error('找不到空闲时段（先清理测试数据）');
};

(async () => {
  const created = []; // 本次造的会议 id，收尾按 id 清干净（含通知）
  let browser;
  let restoreRateLimit = async () => {};
  try {
    restoreRateLimit = await disableRateLimit();
    // notification-service 必须在跑（它由其它脚本按需起停，这里不假设环境）
    await ensureNotificationService();
    const token1 = await login('user1', 'user123');
    const token2 = await login('user2', 'user123');
    const user1Id = Number(sql("SELECT id FROM smt_meeting.sys_user WHERE username='user1';"));

    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage();
    // ⚠️ 必须从一个**真实的前端源**起页面，不能用 about:blank：
    // Chrome 的本地网络访问保护会拦掉"非本地源 → localhost"的连接，
    // 报 `net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS`——那样**所有**断言都会因同一个
    // 与业务无关的原因失败（第一次跑就是这么全军覆没的，而且"不带 token 连不上"还因此假阳性）。
    await page.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded' });

    // 在页面里准备一个"WS 收集器"：开连接、把收到的消息攒起来，Node 侧轮询取
    await page.evaluate((url) => {
      window.__ws = { socks: {}, msgs: [], closes: [] };
      window.__open = (token, key) =>
        new Promise((resolve) => {
          const sock = new WebSocket(token ? `${url}?token=${encodeURIComponent(token)}` : url);
          sock.onopen = () => {
            window.__ws.socks[key] = sock;
            resolve('open');
          };
          sock.onerror = () => resolve('error');
          sock.onclose = (e) => window.__ws.closes.push({ key, code: e.code });
          sock.onmessage = (e) => window.__ws.msgs.push({ key, msg: JSON.parse(e.data) });
        });
      window.__send = (key, text) => window.__ws.socks[key].send(text);
      window.__close = (key) => window.__ws.socks[key].close();
      window.__msgs = (key) => window.__ws.msgs.filter((m) => m.key === key).map((m) => m.msg);
    }, WS_URL);

    // ---------- ① 连接：带 token 成功 / 不带 token 被拒 ----------
    const r1 = await page.evaluate((t) => window.__open(t, 'tab1'), token1);
    check('带 token 连接成功（经网关 ws 路由）', r1 === 'open', r1);
    const rBad = await page.evaluate(() => window.__open(null, 'noauth'));
    check('不带 token 连不上（网关拒绝升级）', rBad === 'error', rBad);
    const rBadToken = await page.evaluate(() => window.__open('not-a-jwt', 'badtoken'));
    check('token 非法也连不上', rBadToken === 'error', rBadToken);

    // ---------- ② 心跳 ----------
    await page.evaluate(() => window.__send('tab1', JSON.stringify({ type: 'PING' })));
    let pong = false;
    for (let i = 0; i < 20 && !pong; i++) {
      await page.waitForTimeout(200);
      pong = (await page.evaluate(() => window.__msgs('tab1'))).some((m) => m.type === 'PONG');
    }
    check('PING → PONG', pong);

    // ---------- ③ 用户隔离：user2 的通知不推给 user1 ----------
    const slot2 = await freeSlot(token2, 6);
    const m2 = await api('/meetings', token2, {
      method: 'POST',
      body: JSON.stringify({ title: 'E2E-WS-他人会议', roomId: 6, ...slot2 }),
    });
    if (m2.code !== 0) throw new Error(`user2 造会失败: ${m2.msg}`);
    created.push(m2.data.meetingId);
    await page.waitForTimeout(3000);
    const leaked = (await page.evaluate(() => window.__msgs('tab1'))).filter((m) => m.type === 'NOTIFICATION');
    check('别人（user2）的通知不会推给 user1', leaked.length === 0, `收到 ${leaked.length} 条`);

    // ---------- ④ 多标签页：同一账号两个连接都要收到 ----------
    const r2 = await page.evaluate((t) => window.__open(t, 'tab2'), token1);
    check('同一账号第二个标签页也能连上', r2 === 'open', r2);

    const unreadBefore = (await api('/notifications/unread-count', token1, { method: 'GET' })).data.unreadCount;
    const slot1 = await freeSlot(token1, 6);
    const m1 = await api('/meetings', token1, {
      method: 'POST',
      body: JSON.stringify({ title: 'E2E-WS-我的会议', roomId: 6, ...slot1 }),
    });
    if (m1.code !== 0) throw new Error(`user1 造会失败: ${m1.msg}`);
    created.push(m1.data.meetingId);

    // 等推送（正常是秒级）
    let msgs1 = [];
    for (let i = 0; i < 30; i++) {
      await page.waitForTimeout(500);
      msgs1 = await page.evaluate(() => window.__msgs('tab1'));
      if (msgs1.some((m) => m.type === 'NOTIFICATION')) break;
    }
    const note1 = msgs1.find((m) => m.type === 'NOTIFICATION');
    check('tab1 收到 NOTIFICATION（秒级推送）', !!note1, note1 ? note1.data.title : '未收到');
    const msgs2 = await page.evaluate(() => window.__msgs('tab2'));
    const note2 = msgs2.find((m) => m.type === 'NOTIFICATION');
    check('tab2 也收到（多标签页各自推送）', !!note2, note2 ? note2.data.title : '未收到');
    check('推送体指向本次新建的会议', note1?.data?.meetingId === m1.data.meetingId,
      String(note1?.data?.meetingId));

    // ---------- ⑤ 推送体带 id，且该 id 在 REST 列表里查得到 ----------
    check('推送体带 id（主键回填成功）', Number.isInteger(note1?.data?.id) && note1.data.id > 0,
      String(note1?.data?.id));
    const list = await api('/notifications?page=1&size=50', token1, { method: 'GET' });
    const inList = (list.data.list || []).some((n) => n.id === note1?.data?.id);
    check('推送的这条能在 REST 列表里查到（落库优先，不是幽灵通知）', inList);
    // ⚠️ 位置很重要：这条断言必须在**收尾自清理之前**（第一次把它写到了清理之后——
    //    执行到这里时本脚本自己已经把测试数据删了，于是查出来是空的、报“库里没有”，
    //    白排查很久还开了 general log 追“谁在删”。凡“回查库”的断言，先确认自己在清理之前）
    const ownerRaw = sql(`SELECT user_id FROM smt_meeting.notification WHERE id=${note1?.data?.id};`);
    // 现场取证：断言失败时能一眼看出"是这条不存在，还是这个用户压根没有新通知"
    const recentRows = sql(`SELECT id,user_id,type,meeting_id FROM smt_meeting.notification WHERE user_id=${user1Id} ORDER BY id DESC LIMIT 3;`);
    // 这场会议在库里的行（用 meeting_id 查——它一定存在；对比它的 id 与推送体的 id，
    // 就能区分"推送 id 错"还是"我查 id 的方式不对"）
    const byMeeting = sql(`SELECT id,user_id,type FROM smt_meeting.notification WHERE meeting_id=${m1.data.meetingId};`);
    check('推送的那条通知在库里确实属于 user1（回查库，不信接口自述）',
      Number(ownerRaw) === user1Id,
      // 诊断信息要带**原始值**：只写"属主"的话失败了也不知道是空、是 0 还是别的
      `通知 ${note1?.data?.id} 的 user_id=[${ownerRaw}] / user1Id=${user1Id} / 该用户最近三行=[${recentRows.replace(/\n/g, ' | ')}] / 本场会议的行=[${byMeeting.replace(/\n/g, ' | ')}] / push.meetingId=${m1.data.meetingId}`);

    // ---------- ⑥ UNREAD_COUNT 是权威值 ----------
    const unreadPush = msgs1.filter((m) => m.type === 'UNREAD_COUNT').pop();
    check('收到 UNREAD_COUNT', !!unreadPush, JSON.stringify(unreadPush?.data));
    const unreadRest = await api('/notifications/unread-count', token1, { method: 'GET' });
    check('推送的未读数与 REST 一致', unreadPush?.data?.unreadCount === unreadRest.data.unreadCount,
      `推送 ${unreadPush?.data?.unreadCount} / REST ${unreadRest.data.unreadCount}`);
    // ⚠️ 不用 `>= 1`（评审 O4）：库里任何历史未读都能满足它，等于没验"这次通知把它抬高了 1"。
    // 建会前取一次基线（就在前面 ⑤ 之后、本条推送之前的那次 REST 读），比对"恰好 +1"。
    check('未读数相对建会前**恰好 +1**（不是被历史数据凑数）',
      unreadPush?.data?.unreadCount === unreadBefore + 1,
      `建会前 ${unreadBefore} → 现在 ${unreadPush?.data?.unreadCount}`);

    // ---------- ⑦ 关掉一个标签页：推送只到剩下的 ----------
    await page.evaluate(() => window.__close('tab2'));
    await page.waitForTimeout(1000);
    const before2 = (await page.evaluate(() => window.__msgs('tab2'))).length;
    const slot3 = await freeSlot(token1, 6);
    const m3 = await api('/meetings', token1, {
      method: 'POST',
      body: JSON.stringify({ title: 'E2E-WS-会话注销验证', roomId: 6, ...slot3 }),
    });
    if (m3.code !== 0) throw new Error(`第三场造会失败: ${m3.msg}`);
    created.push(m3.data.meetingId);
    let got3 = false;
    for (let i = 0; i < 30 && !got3; i++) {
      await page.waitForTimeout(500);
      got3 = (await page.evaluate(() => window.__msgs('tab1'))).some(
        (m) => m.type === 'NOTIFICATION' && m.data.meetingId === m3.data.meetingId);
    }
    check('剩余会话（tab1）仍能收到新推送', got3);
    const after2 = (await page.evaluate(() => window.__msgs('tab2'))).length;
    check('已关闭的会话不再收到（会话表注销生效）', after2 === before2, `${before2} → ${after2}`);

    // ---------- 收尾自清理 ----------
    for (const id of created) {
      sql(`DELETE FROM smt_meeting.notification WHERE meeting_id=${id};
           DELETE FROM smt_meeting.meeting_status_log WHERE meeting_id=${id};
           DELETE FROM smt_meeting.meeting_participant WHERE meeting_id=${id};
           DELETE FROM smt_meeting.meeting WHERE id=${id};`);
    }
    const left = Number(sql(`SELECT COUNT(*) FROM smt_meeting.meeting WHERE id IN (${created.join(',')});`));
    check('库里已无本次数据', left === 0, String(left));
    const leftNote = Number(sql(`SELECT COUNT(*) FROM smt_meeting.notification WHERE meeting_id IN (${created.join(',')});`));
    check('通知无残留', leftNote === 0, String(leftNote));
    // ⚠️ 原来是 `user1Id > 0`——**恒真**（登录成功就保证了），兑现不了"对得上号"（评审 O4）。
    // 改成回查库：推送里那条通知的 user_id 必须就是 user1。
  } catch (e) {
    check(`执行异常: ${e.message}`, false);
  } finally {
    await restoreRateLimit();
    if (browser) await browser.close();
  }

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n==================== 通过 ${results.length - failed} / 失败 ${failed} ====================`);
  process.exit(failed === 0 ? 0 : 1);
})();
