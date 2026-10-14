/**
 * 白屏回归测试
 * ---------------------------------------------------------------
 * 「点开是白屏、没有任何提示」是这个项目踩过的最难受的一个坑：
 * 界面加载失败时 WebView 不抛异常、不报错，只是安安静静显示一片空白，
 * 代码里没有任何地方会知道出事了。
 *
 * 这个脚本把「界面起不来」的几种情况真的做出来，逐一确认屏幕上
 * 始终有给人看的文字，而不是一片白。
 *
 * 做法上刻意不用 CDP 的请求拦截 —— 那条路会被 Service Worker 和缓存绕过，
 * 测出来的全是假通过。这里改成两种最可靠的方式：
 *   1. 直接关掉 Chrome 的脚本执行（等价于 WebView 里 app.js 没能运行）
 *   2. 临时复制一份 app/，把 app.js 删掉再起一个静态服务器
 * 两者都是真的加载不到脚本，不存在被缓存糊弄过去的可能。
 *
 * 用法：
 *   node tools/serve.js &                      # 先起正常入口 4180
 *   node tools/blank-screen-test.mjs
 *
 * 可选参数：--url <入口地址>  --out <截图目录>
 */
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, cpSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(__dirname, '..');
const appDir = join(repoRoot, 'app');

const args = process.argv.slice(2);
const flag = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : dflt;
};
const baseUrl = flag('--url', 'http://127.0.0.1:4180/index.html');
const outDir = flag('--out', join(repoRoot, '.dev-shots', 'blank-test'));
mkdirSync(outDir, { recursive: true });

const CHROME = process.env.CHROME_PATH ||
  (existsSync('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe')
    ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
    : 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe');

const PORT = 9950 + Math.floor(Math.random() * 40);
const userDataDir = join(tmpdir(), 'cdp-blank-' + Date.now());
const brokenDir = join(tmpdir(), 'hw-broken-' + Date.now());

const INSPECT = `(function () {
  var visibleText = (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').trim();
  var boot = document.getElementById('boot');
  return {
    visibleTextLength: visibleText.length,
    visibleTextHead: visibleText.slice(0, 80),
    bootVisible: !!(boot && boot.getBoundingClientRect().height > 50),
    bootTip: boot && boot.querySelector('.boot__tip') ? boot.querySelector('.boot__tip').textContent : null,
    readyFlag: !!window.__HWS_READY__,
    screensShown: [].slice.call(document.querySelectorAll('.screen'))
      .filter(function (s) { return !s.hidden; }).map(function (s) { return s.id; })
  };
})()`;

let chrome, serverProc, ws;

function startStaticServer(root, port) {
  return spawn(process.execPath, [join(__dirname, 'serve.js'), String(port), root], { stdio: 'ignore' });
}

async function connect() {
  let ver = null;
  for (let i = 0; i < 60; i++) {
    try { ver = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); break; }
    catch { await sleep(250); }
  }
  if (!ver) throw new Error('Chrome 没起来');
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  let id = 0; const pending = new Map();
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id !== undefined && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id); pending.delete(msg.id);
      msg.error ? rej(new Error(JSON.stringify(msg.error))) : res(msg.result);
    }
  };
  const send = (method, params = {}) => new Promise((res, rej) => {
    const myId = ++id; pending.set(myId, { res, rej });
    ws.send(JSON.stringify({ id: myId, method, params }));
  });
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', {
    width: 412, height: 915, deviceScaleFactor: 2.625, mobile: true
  });
  return { send, browser: ver.Browser };
}

const results = [];

async function scenario(send, name, url, opts = {}) {
  if (opts.noJs !== undefined) {
    await send('Emulation.setScriptExecutionDisabled', { value: !!opts.noJs });
  }
  await send('Page.navigate', { url });
  await sleep(2200);
  const r = await send('Runtime.evaluate', { expression: INSPECT, returnByValue: true });
  const shot = await send('Page.captureScreenshot', { format: 'png' });
  const file = join(outDir, name + '.png');
  writeFileSync(file, Buffer.from(shot.data, 'base64'));
  const v = r.result.value;
  results.push({
    scenario: name,
    simulated: opts.label || '',
    screen: v.screensShown.join(',') || '(无)',
    bootOverlay: v.bootVisible,
    ready: v.readyFlag,
    textLength: v.visibleTextLength,
    textHead: v.visibleTextHead,
    verdict: v.visibleTextLength > 0 ? 'PASS 屏幕上有内容' : 'FAIL 白屏'
  });
}

try {
  // 准备一份「app.js 缺失」的副本
  cpSync(appDir, brokenDir, { recursive: true });
  rmSync(join(brokenDir, 'app.js'), { force: true });
  serverProc = startStaticServer(brokenDir, 4191);
  await sleep(700);

  chrome = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${userDataDir}`,
    '--hide-scrollbars', 'about:blank'
  ], { stdio: 'ignore' });

  const { send, browser } = await connect();

  // A. 正常入口
  await scenario(send, 'A-normal', baseUrl, { label: '一切正常' });

  // B. 脚本执行被禁用 —— 等价于 WebView 里 app.js 跑不起来
  await scenario(send, 'B-script-disabled', baseUrl, {
    noJs: true, label: 'JavaScript 完全没运行'
  });

  // C. 入口页能打开、CSS 也在，但 app.js 真的加载不到（404）
  await scenario(send, 'C-appjs-404', 'http://127.0.0.1:4191/index.html', {
    noJs: false, label: 'app.js 加载失败'
  });

  await send('Emulation.setScriptExecutionDisabled', { value: false });

  console.log(JSON.stringify({ chrome: browser, results }, null, 2));
  const failed = results.filter(r => r.verdict.startsWith('FAIL'));
  console.log('');
  if (failed.length) {
    console.log('有场景白屏：' + failed.map(f => f.scenario).join(', '));
    process.exitCode = 1;
  } else {
    console.log('全部故障场景屏幕上都有内容，没有白屏 ✅');
    console.log('截图目录：' + outDir);
  }
} finally {
  try { ws && ws.close(); } catch {}
  try { chrome && chrome.kill(); } catch {}
  try { serverProc && serverProc.kill(); } catch {}
  try { rmSync(brokenDir, { recursive: true, force: true }); } catch {}
}
