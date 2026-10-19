/**
 * 会议纪要 E2E：SQL 造 status=40 会议 → 上传 wav + 转写 → 转写/点击 seek →
 * 会议洞察 → mock AI 摘要 → 导出 Word 下载。
 *
 * 前置：meeting-service 以 STORAGE_PROVIDER=local + MINUTES_AI_PROVIDER=mock
 *       MINUTES_LOCAL_DIR=/tmp/smt-minutes-test 启动；Vite dev server 在 :5173。
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const fs = require('fs');

const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080/api';
const MYSQL = 'docker exec smt-mysql mysql --default-character-set=utf8mb4 -uroot -proot123 -N -e';
const LOCAL_DIR = process.env.MINUTES_LOCAL_DIR || '/tmp/smt-minutes-test';
const TS = Date.now();
const TITLE = `E2E纪要-${TS}`;
const WAV = `/tmp/smt-minutes-e2e-${TS}.wav`;

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  → ' + detail : ''}`);
};
const sql = (q) =>
  execSync(`${MYSQL} ${JSON.stringify(q)}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    .split('\n').filter((l) => l.trim() && !l.includes('PAGER set to stdout') && !l.includes('Using a password'))
    .join('\n').trim();

/** 1 秒 8kHz 单声道 16bit 静音 WAV（local provider 不校验内容） */
function writeWav(file) {
  const sampleRate = 8000;
  const dataSize = sampleRate * 2;
  const buffer = Buffer.alloc(44 + dataSize);
  buffer.write('RIFF', 0);
  buffer.writeUInt32LE(36 + dataSize, 4);
  buffer.write('WAVE', 8);
  buffer.write('fmt ', 12);
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);
  buffer.writeUInt16LE(1, 22);
  buffer.writeUInt32LE(sampleRate, 24);
  buffer.writeUInt32LE(sampleRate * 2, 28);
  buffer.writeUInt16LE(2, 32);
  buffer.writeUInt16LE(16, 34);
  buffer.write('data', 36);
  buffer.writeUInt32LE(dataSize, 40);
  fs.writeFileSync(file, buffer);
}

(async () => {
  let browser;
  let meetingId = null;
  try {
    writeWav(WAV);
    meetingId = Number(sql(`USE smt_meeting;
      INSERT INTO meeting (title, room_id, organiser_id, start_time, end_time, status, remark)
      VALUES ('${TITLE}', 1, 4, NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 1 HOUR, 40, '');
      SELECT LAST_INSERT_ID();`).split('\n').pop());

    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1512, height: 1100 } });
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.getByPlaceholder('请输入用户名').fill('user1');
    await page.getByPlaceholder('请输入密码').fill('user123');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/calendar', { timeout: 10000 });

    await page.goto(`${BASE}/meetings/${meetingId}`, { waitUntil: 'networkidle' });
    await page.getByRole('tab', { name: '会议纪要' }).click();

    // 上传音频 + 转写
    await page.locator('input[type=file]').nth(0).setInputFiles(WAV);
    await page.getByPlaceholder(/恩格斯论述/).fill(
      '[0:0.100,0:0.500,0]  你好\n[0:0.600,0:0.900,1]  世界',
    );
    await page.getByRole('button', { name: /开始解析/ }).click();
    await page.getByText('你好', { exact: false }).first().waitFor({ timeout: 30000 });
    check('导入后转写可见', true);

    // 等音频元数据就绪
    await page.waitForFunction(() => {
      const audio = document.querySelector('audio');
      return audio !== null && audio.readyState >= 1;
    }, { timeout: 15000 });

    // 点击第二段，音频跳到 0.6s
    await page.getByRole('button', { name: /世界/ }).first().click();
    const currentTime = await page.locator('audio').evaluate((el) => el.currentTime);
    check('点击转写跳转音频', currentTime >= 0.5, `currentTime=${currentTime}`);
    check('发言人 2 人', (await page.getByText('说话人 2', { exact: false }).count()) > 0);

    // 会议洞察
    await page.getByRole('tab', { name: '会议洞察' }).click();
    await page.getByText('发言覆盖率', { exact: false }).waitFor({ timeout: 5000 });
    check('会议洞察可见', true);

    // AI 纪要（mock worker 每 5s 扫描）
    await page.getByRole('tab', { name: 'AI 纪要' }).click();
    await page.getByRole('button', { name: /生成纪要/ }).click();
    await page.getByText('本次会议围绕产品排期与压测计划展开', { exact: false })
      .waitFor({ timeout: 40000 });
    check('AI 摘要可见', true);

    // 导出 Word：下载事件 + 文件名
    const downloadPromise = page.waitForEvent('download', { timeout: 15000 });
    await page.getByRole('button', { name: /导出 Word/ }).click();
    const download = await downloadPromise;
    check('导出文件名含会议纪要', download.suggestedFilename().includes('会议纪要'),
      download.suggestedFilename());
    const downloadPath = await download.path();
    check('导出文件非空', !!downloadPath && fs.statSync(downloadPath).size > 0);

    // 重新导入：覆盖转写并刷新工作台（R-17 覆盖规则的 UI 入口）
    await page.getByRole('button', { name: /重新导入/ }).click();
    await page.getByRole('button', { name: /继续导入/ }).click();
    const modal = page.locator('.ant-modal').last();
    await modal.locator('input[type=file]').nth(0).setInputFiles(WAV);
    await modal.getByPlaceholder(/恩格斯论述/).fill('[0:0.100,0:0.500,0]  重新导入成功');
    await modal.getByRole('button', { name: /开始解析/ }).click();
    await page.getByText('重新导入成功', { exact: false }).first().waitFor({ timeout: 30000 });
    check('重新导入覆盖转写', true);
  } catch (e) {
    check('脚本执行', false, e.message);
  } finally {
    if (browser) await browser.close();
    if (meetingId) {
      try {
        const uploadIds = sql(`USE smt_meeting; SELECT upload_id FROM file_upload_session
          WHERE biz_type='MINUTES_AUDIO' AND biz_id=${meetingId};`).split('\n').filter(Boolean);
        sql(`USE smt_meeting;
          DELETE s FROM meeting_transcript_segment s
            JOIN meeting_minutes m ON m.id = s.minutes_id WHERE m.meeting_id = ${meetingId};
          DELETE sp FROM meeting_minutes_speaker sp
            JOIN meeting_minutes m ON m.id = sp.minutes_id WHERE m.meeting_id = ${meetingId};
          DELETE FROM meeting_minutes WHERE meeting_id = ${meetingId};
          DELETE FROM file_object WHERE biz_type = 'MINUTES_AUDIO' AND biz_id = ${meetingId};
          DELETE FROM file_upload_session WHERE biz_type = 'MINUTES_AUDIO' AND biz_id = ${meetingId};
          DELETE FROM meeting WHERE id = ${meetingId};`);
        for (const id of uploadIds) {
          fs.rmSync(`${LOCAL_DIR}/${id}`, { recursive: true, force: true });
        }
      } catch (e) {
        console.error('清理失败（请手工检查）', e.message);
      }
    }
    try {
      fs.unlinkSync(WAV);
    } catch {
      // ignore
    }
    const failed = results.filter((r) => !r.ok);
    console.log(`\n${results.length - failed.length}/${results.length} passed`);
    process.exit(failed.length === 0 ? 0 : 1);
  }
})();
