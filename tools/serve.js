/**
 * 极简静态文件服务器 —— 只用于本地预览与界面自测。
 *
 *   node tools/serve.js [端口] [根目录]
 *
 * 默认：端口 4180，根目录 app/
 * 打开 http://127.0.0.1:4180/selftest.html 可载入示例数据并逐页查看界面。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const port = Number(process.argv[2] || 4180);
const root = path.resolve(process.argv[3] || path.join(__dirname, '..', 'app'));

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const server = http.createServer((req, res) => {
  let rel = decodeURIComponent(req.url.split('?')[0]);
  if (rel === '/' || rel === '') rel = '/index.html';

  const target = path.join(root, path.normalize(rel).replace(/^([/\\])+/, ''));
  if (!target.startsWith(root)) {
    res.writeHead(403).end('forbidden');
    return;
  }

  fs.readFile(target, (err, buf) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('404 找不到 ' + rel);
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(target).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-store'
    });
    res.end(buf);
  });
});

server.listen(port, '127.0.0.1', () => {
  console.log(`作业哨兵 本地预览: http://127.0.0.1:${port}/`);
  console.log(`界面自测页:        http://127.0.0.1:${port}/selftest.html`);
  console.log(`根目录: ${root}`);
});
