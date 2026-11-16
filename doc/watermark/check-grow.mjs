import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const card = p.locator('.mr-grid .mr-card').nth(2);
await card.scrollIntoViewIfNeeded();
await p.waitForTimeout(500);
const fromRect = await p.evaluate(() => { const c = document.querySelectorAll('.mr-grid .mr-card')[2].querySelector('.mr-cover'); const r = c.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; });
await card.click();
const frames = [];
for (const w of [80, 90, 90, 120]) {
  await p.waitForTimeout(w);
  frames.push(await p.evaluate(() => { const g = document.querySelector('.mr-grow'); const s = document.querySelector('.mr-sheet'); return { grow: !!g, moving: !!document.querySelector('.mr-grow.moving'), t: g ? getComputedStyle(g).transform.slice(0, 30) : null, sheetGrow: !!document.querySelector('.mr-sheet-grow'), landed: !!document.querySelector('.mr-sheet-landed') }; }));
}
await p.waitForTimeout(600);
const end = await p.evaluate(() => ({ grow: document.querySelectorAll('.mr-grow').length, sheetGrow: document.querySelectorAll('.mr-sheet-grow').length, stageOpacity: (() => { const s = document.querySelector('.md-hero-target'); return s ? getComputedStyle(s).opacity : null; })(), bodyOpacity: (() => { const s = document.querySelector('.mr-sheet-body'); return s ? getComputedStyle(s).opacity : null; })() }));
console.log('from card cover:', JSON.stringify(fromRect));
console.log('frames:', JSON.stringify(frames));
console.log('end:', JSON.stringify(end), 'errors:', JSON.stringify(errs.slice(0, 2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/grow-end.png' });
await b.close();