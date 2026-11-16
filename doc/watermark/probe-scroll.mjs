import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
const calls = [];
p.on('request', (r) => {
  const u = r.url();
  if (/\.(json|m3u8)/.test(u) || /ajax|api|load|page|archive/i.test(u)) calls.push(r.method() + ' ' + u.slice(0, 150));
});
await p.goto('https://able.heermtat.cc/category/mrds/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2000);
const before = await p.evaluate(() => document.querySelectorAll('a[href*="/archives/"]').length);
for (let i = 0; i < 20; i++) { await p.mouse.wheel(0, 2500); await p.waitForTimeout(400); }
await p.waitForTimeout(2500);
const after = await p.evaluate(() => document.querySelectorAll('a[href*="/archives/"]').length);
console.log('cards before', before, 'after', after);
console.log('requests:', JSON.stringify([...new Set(calls)].slice(0, 14), null, 1));
await b.close();