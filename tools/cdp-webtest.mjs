/**
 * 模拟「安卓 WebView」加载 app/ 资源：真实手机视口 + 预置的已登录数据和作业。
 * 用法: node tools/cdp-webtest.mjs [url] [--out dir]
 */
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';

const CHROME = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const url = (process.argv[2] && !process.argv[2].startsWith('--')) ? process.argv[2] : 'http://127.0.0.1:4180/index.html';
const outIdx = process.argv.indexOf('--out');
const outDir = outIdx >= 0 ? process.argv[outIdx + 1] : join(process.cwd(), '.dev-shots', 'webtest');
mkdirSync(outDir, { recursive: true });

const DAY = 86400000, HOUR = 3600000;
const now = Date.now();
const seed = {
  v: 1,
  user: { name: '20231001234', hash: 'abc-def', createdAt: now - 10 * DAY },
  settings: { offsets: ['d1', 'h2'], dailyDigest: true, digestTime: '07:30', quietHours: true, quietFrom: '23:00', quietTo: '07:00' },
  homework: [
    { id: 'hw_a', title: '第五章 定积分 课后习题', course: '高等数学', due: now + 5 * HOUR, note: '写在作业本上', done: false, createdAt: now },
    { id: 'hw_b', title: '实验报告：霍尔效应', course: '大学物理', due: now + 2 * DAY, note: '', done: false, createdAt: now },
    { id: 'hw_c', title: '读书笔记《乡土中国》', course: '社会学概论', due: now + 5 * DAY, note: '', done: false, createdAt: now },
    { id: 'hw_d', title: '课程设计选题申请表', course: '数据结构', due: now - 6 * HOUR, note: '已错过', done: false, createdAt: now },
    { id: 'hw_e', title: '第一章 绪论 思考题', course: '马克思主义基本原理', due: now - 2 * DAY, note: '', done: true, createdAt: now }
  ]
};

const PORT = 9700 + Math.floor(Math.random() * 90);
const userDataDir = join(tmpdir(), 'cdp-webtest-' + Date.now());

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${userDataDir}`,
  '--hide-scrollbars', 'about:blank'
], { stdio: 'ignore' });

let ws;
try {
  let ver = null;
  for (let i = 0; i < 60; i++) {
    try { ver = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); break; }
    catch { await sleep(250); }
  }
  const t = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
  ws = new WebSocket(t.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  let id = 0; const pending = new Map(); const events = [];
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id !== undefined && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id); pending.delete(msg.id);
      msg.error ? rej(new Error(JSON.stringify(msg.error))) : res(msg.result);
    } else if (msg.method) events.push(msg);
  };
  const send = (method, params = {}) => new Promise((res, rej) => {
    const myId = ++id; pending.set(myId, { res, rej });
    ws.send(JSON.stringify({ id: myId, method, params }));
  });

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');

  // 真实手机：Pixel 7 尺寸 + 安卓 WebView 的 UA
  await send('Emulation.setDeviceMetricsOverride', {
    width: 412, height: 915, deviceScaleFactor: 2.625, mobile: true
  });
  await send('Emulation.setUserAgentOverride', {
    userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36'
  });

  // 先到同源页面写入 localStorage
  await send('Page.navigate', { url });
  await sleep(1200);

  // 关键一步：拦住 app.js，先注入数据再放行，模拟「已登录用户再次打开 App」
  await send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try { localStorage.setItem('hw_sentinel_v1', ${JSON.stringify(JSON.stringify(seed))}); } catch (e) {}`
  });

  events.length = 0;
  await send('Page.reload', { ignoreCache: true });
  await sleep(2500);

  const dump = `(function () {
    function info(sel) {
      var n = document.querySelector(sel);
      if (!n) return { sel: sel, missing: true };
      var cs = getComputedStyle(n);
      var r = n.getBoundingClientRect();
      return {
        sel: sel, hidden: !!n.hidden,
        display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
        w: Math.round(r.width), h: Math.round(r.height),
        top: Math.round(r.top), left: Math.round(r.left),
        text: (n.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 60)
      };
    }
    return {
      screenAuth: info('#screen-auth'),
      screenApp: info('#screen-app'),
      content: info('#content'),
      viewHome: info('#view-home'),
      hero: info('#hero-countdown'),
      heroName: info('#hero-name'),
      heroTimer: info('#hero-timer'),
      pendingList: info('#list-pending'),
      doneList: info('#list-done'),
      tabbar: info('#tabbar'),
      fab: info('#btn-add'),
      statUrgent: info('#stat-urgent'),
      statDone: info('#stat-done'),
      greeting: info('#greeting'),
      meName: info('#me-name'),
      cards: document.querySelectorAll('.hw').length,
      pendingCards: document.querySelectorAll('#list-pending .hw').length,
      doneCards: document.querySelectorAll('#list-done .hw').length,
      docHeight: document.documentElement.scrollHeight,
      bodyBg: getComputedStyle(document.body).backgroundColor,
      topbarBg: (function(){ var t = document.querySelector('.topbar'); return t ? getComputedStyle(t).backgroundImage.slice(0,60) : 'MISSING'; })(),
      hwSentinelWeb: typeof window.HWSentinelWeb,
      jsErrors: window.__errors || []
    };
  })()`;

  const r = await send('Runtime.evaluate', { expression: dump, returnByValue: true });

  const shots = {};
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  shots.home = join(outDir, 'logged-in-home.png');
  writeFileSync(shots.home, Buffer.from(shot.data, 'base64'));

  const errs = events.filter(e => e.method === 'Runtime.exceptionThrown')
    .map(e => (e.params.exceptionDetails.exception && e.params.exceptionDetails.exception.description) || e.params.exceptionDetails.text);
  const logs = events.filter(e => e.method === 'Log.entryAdded')
    .map(e => '[' + e.params.entry.level + '] ' + e.params.entry.text);

  console.log(JSON.stringify({
    url, chrome: ver.Browser,
    result: (r.result && r.result.value) || null,
    evalError: r.exceptionDetails ? ((r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text) : null,
    exceptions: errs, log: logs, screenshots: shots
  }, null, 2));
} finally {
  try { ws && ws.close(); } catch {}
  try { chrome.kill(); } catch {}
}
