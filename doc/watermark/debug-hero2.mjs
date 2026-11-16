import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
const logs = [];
p.on('console', (m) => logs.push(m.type() + ': ' + m.text().slice(0, 120)));
p.on('pageerror', (e) => logs.push('PAGEERROR: ' + String(e.message).slice(0, 160)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
// 用 DOM 观察：点击后 30ms 内是否有 .mr-hero
const card = p.locator('.mr-grid .mr-card').first();
await card.scrollIntoViewIfNeeded();
await p.waitForTimeout(500);
const cls = await p.evaluate(() => { const c = document.querySelector('.mr-grid .mr-card'); return c ? c.className : null; });
console.log('card class:', cls);
await card.click({ position: { x: 50, y: 30 } });
for (const ms of [30, 120, 250, 500]) {
  await p.waitForTimeout(ms === 30 ? 30 : 90);
  const s = await p.evaluate(() => ({ heroes: document.querySelectorAll('.mr-hero').length, sheetHero: document.querySelectorAll('.mr-sheet-hero').length, mask: document.querySelectorAll('.mr-mask').length }));
  console.log('t~' + ms + 'ms:', JSON.stringify(s));
}
console.log('console:', JSON.stringify(logs.slice(0, 5)));
await b.close();