import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
// 直接调用跳转：2026-01-12
await p.locator('.mr-cal-btn').first().click();
await p.waitForTimeout(1000);
// 切到 2026-01（点两次 ‹）
await p.evaluate(() => { document.querySelectorAll('.mc-nav')[0].click(); });
await p.waitForTimeout(400);
await p.evaluate(() => { document.querySelectorAll('.mc-nav')[0].click(); });
await p.waitForTimeout(1200);
// 找 12 号那格
const clicked = await p.evaluate(() => {
  const cells = [...document.querySelectorAll('.mc-cell')];
  const target = cells.find((c) => { const d = c.querySelector('.mc-day'); return d && d.textContent.trim() === '12'; });
  if (!target) return null;
  target.click();
  return true;
});
console.log('点 12 号:', clicked);
await p.waitForTimeout(4000);
const st = await p.evaluate(() => {
  const cards = [...document.querySelectorAll('[data-post-id]')];
  const dates = cards.slice(0, 8).map((c) => (c.querySelector('.mr-meta') || {}).textContent?.trim());
  const hit = document.querySelector('.mr-hit');
  return { jump: (document.querySelector('.mr-datejump') || {}).textContent?.replace(/\s+/g,' ').trim().slice(0, 30), first8: dates, highlighted: hit ? (hit.querySelector('.mr-meta') || {}).textContent?.trim() : null, start: (document.querySelector('.mr-newer-text') || {}).textContent?.trim().slice(0, 20) };
});
console.log('跳转后:', JSON.stringify(st));
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await b.close();