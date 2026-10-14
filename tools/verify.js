/**
 * 用 Playwright 驱动本机 Chrome 做一次真实的手机端验证：
 *   1. 以 390x844 的真实视口打开应用
 *   2. 走一遍「创建账号 → 添加作业 → 勾选完成 → 切换提醒设置」的完整流程
 *   3. 拦下通知 API，验证提醒真的会在正确时间点触发
 *   4. 每一步截图，顺便检查有没有横向溢出
 *
 *   node tools/verify.js [输出目录]
 */
const path = require('path');
const fs = require('fs');

const PW = 'D:/hw-tools/node_modules/playwright-core';
const { chromium } = require(PW);

const OUT = process.argv[2] || path.join(__dirname, '..', '.dev-shots');
const BASE = 'http://127.0.0.1:4180';

function log(...a) { console.log(...a); }

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  fs.readdirSync(OUT).filter((f) => f.endsWith('.png')).forEach((f) => fs.unlinkSync(path.join(OUT, f)));

  const browser = await chromium.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true,
    args: ['--disable-gpu', '--no-first-run', '--no-default-browser-check']
  });

  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  });

  const page = await context.newPage();

  // 记录所有通知调用（网页版走的是 Notification 构造函数）
  await page.addInitScript(() => {
    window.__notifications = [];
    class FakeNotification {
      constructor(title, opts) {
        window.__notifications.push({ title, body: (opts && opts.body) || '', tag: (opts && opts.tag) || '' });
      }
      close() {}
    }
    FakeNotification.permission = 'granted';
    FakeNotification.requestPermission = () => Promise.resolve('granted');
    window.Notification = FakeNotification;
  });

  const problems = [];
  const overflow = async (label) => {
    const r = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
      body: document.body.scrollWidth
    }));
    const over = Math.max(r.scroll, r.body) - r.client;
    if (over > 1) problems.push(label + ': 横向溢出 ' + over + 'px');
    return over;
  };

  const shot = async (name) => {
    const over = await overflow(name);
    await page.screenshot({ path: path.join(OUT, name + '.png') });
    log('  截图 ' + name + '.png' + (over > 1 ? '  [!] 溢出 ' + over + 'px' : ''));
  };

  log('\n=== 1. 打开应用（未登录）===');
  await page.goto(BASE + '/index.html', { waitUntil: 'load' });
  await page.waitForSelector('#screen-auth', { state: 'visible' });
  log('  视口宽度 = ' + (await page.evaluate(() => window.innerWidth)) + 'px');
  await shot('v01-login');

  log('\n=== 2. 表单校验 ===');
  await page.fill('#input-user', '20231001234');
  await page.fill('#input-pass', '123');
  await page.fill('#input-pass2', '123');
  await page.click('#btn-login');
  const err1 = await page.textContent('#login-error');
  log('  密码过短提示: "' + err1 + '"');
  if (err1.indexOf('6') < 0) problems.push('密码长度校验未生效');

  await page.fill('#input-pass', 'hwbj2024');
  await page.fill('#input-pass2', 'hwbj9999');
  await page.click('#btn-login');
  const err2 = await page.textContent('#login-error');
  log('  两次不一致提示: "' + err2 + '"');
  if (err2.indexOf('不一致') < 0) problems.push('确认密码校验未生效');

  log('\n=== 3. 创建账号并进入 ===');
  await page.fill('#input-pass', 'hwbj2024');
  await page.fill('#input-pass2', 'hwbj2024');
  await page.click('#btn-login');
  await page.waitForSelector('#screen-app', { state: 'visible' });
  log('  已进入主界面，标题 = ' + (await page.textContent('.topbar__title')));
  await shot('v02-home-empty');

  log('\n=== 4. 添加作业 ===');
  await page.click('#btn-add');
  await page.waitForSelector('#sheet', { state: 'visible' });
  await shot('v03-sheet-add');

  const due = await page.evaluate(() => {
    const d = new Date(Date.now() + 90 * 60 * 1000);
    const p = (n) => (n < 10 ? '0' : '') + n;
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + 'T' + p(d.getHours()) + ':' + p(d.getMinutes());
  });
  await page.fill('#hw-title', '第三章 课后习题 1-15 题');
  await page.fill('#hw-course', '线性代数');
  await page.fill('#hw-due', due);
  await page.fill('#hw-note', '写作业本上，明天上课交');
  await page.click('#btn-hw-save');
  await page.waitForSelector('#sheet', { state: 'hidden' });
  await page.waitForTimeout(400);

  const cardCount = await page.locator('#list-pending .hw').count();
  log('  待完成卡片数 = ' + cardCount);
  if (cardCount !== 1) problems.push('添加作业后待完成列表数量不对');
  log('  首页倒计时 = "' + (await page.textContent('#hero-timer')).trim() + '"');
  await shot('v04-home-one');

  log('\n=== 5. 载入示例数据，覆盖五种紧急状态 ===');
  await page.click('.tab[data-tab="me"]');
  page.once('dialog', (d) => d.accept());
  await page.click('#btn-load-demo');
  await page.waitForTimeout(500);
  await page.click('.tab[data-tab="home"]');
  await page.waitForTimeout(300);

  const stats = await page.evaluate(() => ({
    urgent: document.querySelector('#stat-urgent').textContent,
    week: document.querySelector('#stat-week').textContent,
    done: document.querySelector('#stat-done').textContent,
    pending: document.querySelectorAll('#list-pending .hw').length,
    doneCards: document.querySelectorAll('#list-done .hw').length,
    overdue: document.querySelectorAll('.hw--overdue').length,
    soon: document.querySelectorAll('.hw--soon').length
  }));
  log('  统计: ' + JSON.stringify(stats));
  if (Number(stats.urgent) < 2) problems.push('48 小时内统计不对');
  if (stats.doneCards < 1) problems.push('已完成列表为空');
  await shot('v05-home-full');

  await page.click('.tab[data-tab="all"]');
  await page.waitForTimeout(300);
  log('  课程筛选按钮数 = ' + (await page.locator('.chipbtn').count()));
  await shot('v06-all');

  log('\n=== 6. 勾选完成 ===');
  await page.click('.tab[data-tab="home"]');
  await page.waitForTimeout(250);
  const beforePending = await page.locator('#list-pending .hw').count();
  await page.click('#list-pending .hw__check');
  await page.waitForTimeout(400);
  const afterPending = await page.locator('#list-pending .hw').count();
  const afterDone = await page.locator('#list-done .hw').count();
  log('  待完成 ' + beforePending + ' -> ' + afterPending + '，已完成 ' + afterDone);
  if (afterPending !== beforePending - 1) problems.push('勾选完成后列表没有更新');
  await shot('v07-done-toggle');

  log('\n=== 7. 提醒设置 ===');
  await page.click('.tab[data-tab="remind"]');
  await page.waitForTimeout(300);
  await shot('v08-remind');

  // 档位顺序：1=3天前 2=1天前 3=6小时前 4=2小时前 5=30分钟前
  await page.click('.offset:nth-child(4) .switch');   // 关掉「2 小时前」
  await page.waitForTimeout(200);
  await page.click('.offset:nth-child(3) .switch');   // 打开「6 小时前」
  await page.waitForTimeout(200);

  const settings = await page.evaluate(() => JSON.parse(localStorage.getItem('hw_sentinel_v1')).settings);
  log('  档位调整后: ' + JSON.stringify(settings.offsets));
  if (settings.offsets.indexOf('h2') >= 0) problems.push('关闭「2 小时前」没有生效');
  if (settings.offsets.indexOf('h6') < 0) problems.push('开启「6 小时前」没有生效');

  // 每日早报与免打扰（用 dispatch 触发，避免被其他元素遮挡点不到）
  await page.evaluate(() => {
    const d = document.getElementById('switch-digest');
    d.checked = true; d.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await page.waitForTimeout(250);
  await page.evaluate(() => {
    const q = document.getElementById('switch-quiet');
    q.checked = true; q.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await page.waitForTimeout(400);

  const settings2 = await page.evaluate(() => JSON.parse(localStorage.getItem('hw_sentinel_v1')).settings);
  log('  完整设置: ' + JSON.stringify(settings2));
  if (!settings2.dailyDigest) problems.push('每日早报开关没有生效');
  if (!settings2.quietHours) problems.push('免打扰开关没有生效');
  await shot('v09-remind-adjusted');

  log('\n=== 8. 提醒计划计算 ===');
  const plan = await page.evaluate(() => window.HWSentinelWeb.plan());
  log('  生成了 ' + plan.length + ' 条提醒计划，前 6 条：');
  plan.slice(0, 6).forEach((p) => log('    - ' + new Date(p.at).toLocaleString('zh-CN') + '  ' + p.title));
  if (plan.length === 0) problems.push('提醒计划为空');
  if (!plan.every((p) => p.at > Date.now())) problems.push('提醒计划里存在已过去的时间点');

  log('\n=== 9. 免打扰顺延 ===');
  const shifted = await page.evaluate(() => window.HWSentinelWeb.plan()
    .filter((p) => { const h = new Date(p.at).getHours(); return h >= 23 || h < 7; }).length);
  log('  落在 23:00-07:00 的提醒条数 = ' + shifted + '（应为 0）');
  if (shifted > 0) problems.push('免打扰顺延没有生效');

  log('\n=== 10. 测试通知 ===');
  await page.click('#btn-test-notify');
  await page.waitForTimeout(400);
  const notes = await page.evaluate(() => window.__notifications);
  log('  已发出的通知: ' + JSON.stringify(notes));
  if (!notes.length) problems.push('测试通知没有发出');

  log('\n=== 11. 退出登录与数据保留 ===');
  await page.click('.tab[data-tab="me"]');
  await page.waitForTimeout(250);
  await shot('v10-me');
  page.once('dialog', (d) => d.accept());
  await page.click('#btn-logout');
  await page.waitForSelector('#screen-auth', { state: 'visible' });
  const kept = await page.evaluate(() => {
    const d = JSON.parse(localStorage.getItem('hw_sentinel_v1'));
    return { user: !!d.user, homework: d.homework.length };
  });
  log('  退出后本地数据: ' + JSON.stringify(kept));
  if (!kept.user || kept.homework < 1) problems.push('退出登录后数据丢失');

  log('\n=== 12. 重新登录 ===');
  await page.fill('#input-user', '20231001234');
  await page.fill('#input-pass', 'hwbj2024');
  await page.click('#btn-login');
  await page.waitForSelector('#screen-app', { state: 'visible' });
  const backCount = await page.locator('#list-pending .hw, #list-done .hw').count();
  log('  重新登录后卡片数 = ' + backCount);
  if (backCount < 1) problems.push('重新登录后作业没有恢复');

  log('\n=== 13. 密码错误 ===');
  await page.click('.tab[data-tab="me"]');
  page.once('dialog', (d) => d.accept());
  await page.click('#btn-logout');
  await page.waitForSelector('#screen-auth', { state: 'visible' });
  await page.fill('#input-user', '20231001234');
  await page.fill('#input-pass', 'wrongpass');
  await page.click('#btn-login');
  const err3 = await page.textContent('#login-error');
  log('  错误密码提示: "' + err3 + '"');
  if (err3.indexOf('密码') < 0) problems.push('密码错误没有正确提示');
  await shot('v11-login-error');

  await browser.close();

  log('\n================ 验证结果 ================');
  if (problems.length === 0) {
    log('全部通过');
  } else {
    log('发现 ' + problems.length + ' 个问题：');
    problems.forEach((p) => log('  x ' + p));
  }
  process.exit(problems.length ? 1 : 0);
})().catch((e) => {
  console.error('验证脚本出错:', e);
  process.exit(2);
});
