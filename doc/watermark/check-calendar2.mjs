import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
await p.locator('.mr-cal-btn').first().click();
await p.waitForTimeout(1500);
const stat = () => p.evaluate(() => {
  const cells = [...document.querySelectorAll('.mc-cell')].filter((c) => !c.classList.contains('empty'));
  return { month: (document.querySelector('.mc-title') || {}).textContent, withCount: cells.filter((c) => (c.querySelector('.mc-count') || {}).textContent).length, cells: cells.length };
});
console.log('当月:', JSON.stringify(await stat()));
// 切到上个月
await p.evaluate(() => document.querySelectorAll('.mc-nav')[0].click());
await p.waitForTimeout(1500);
console.log('上个月:', JSON.stringify(await stat()));
// 切回来，数字应该还在
await p.evaluate(() => document.querySelectorAll('.mc-nav')[1].click());
await p.waitForTimeout(1200);
console.log('切回当月:', JSON.stringify(await stat()));
// 选一天 → 清除 → 选中态应消失
await p.evaluate(() => { const c=[...document.querySelectorAll('.mc-cell')].filter((x)=>!x.classList.contains('empty')); c[9].click(); });
await p.waitForTimeout(2500);
const picked = await p.evaluate(() => document.querySelectorAll('.mc-cell.on').length);
await p.locator('.mr-cal-btn').first().click();
await p.waitForTimeout(800);
await p.evaluate(() => { const btn=[...document.querySelectorAll('.mr-cal-foot .mr-chip')].find((x)=>x.textContent.includes('清除')); btn.click(); });
await p.waitForTimeout(2500);
await p.locator('.mr-cal-btn').first().click();
await p.waitForTimeout(1000);
const cleared = await p.evaluate(() => ({ on: document.querySelectorAll('.mc-cell.on').length, jumpBar: !!document.querySelector('.mr-datejump'), btnText: (document.querySelector('.mr-cal-btn') || {}).textContent?.trim() }));
console.log('选中过:', picked, '| 清除后:', JSON.stringify(cleared));
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await b.close();