import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
// 用真实账号登录（Qing / dqq1107.）
const login = await p.evaluate(async () => {
  const r = await fetch('/api/app-auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'Qing', password: 'dqq1107.' }) });
  const t = await r.text();
  return { status: r.status, body: t.slice(0, 200) };
}).catch((e) => ({ err: String(e).slice(0, 100) }));
console.log('login probe:', JSON.stringify(login));
await b.close();