import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
// 打开月历
const btn = p.locator('.mr-cal-btn');
console.log('日期按钮:', await btn.count(), await btn.first().textContent());
await btn.first().click();
await p.waitForTimeout(1500);
const cal = await p.evaluate(() => {
  const cells = [...document.querySelectorAll('.mc-cell')].filter((c) => !c.classList.contains('empty'));
  const withCount = cells.filter((c) => /\d+/.test((c.querySelector('.mc-count') || {}).textContent || ''));
  return { cells: cells.length, withCount: withCount.length, title: (document.querySelector('.mc-title') || {}).textContent, sample: cells.slice(0, 3).map((c) => c.textContent.replace(/\s+/g, ' ')) };
});
console.log('月历:', JSON.stringify(cal));
// 点某一天 → 应跳转并出现提示条
await p.evaluate(() => { const cells=[...document.querySelectorAll('.mc-cell')].filter((c)=>!c.classList.contains('empty')); cells[9].click(); });
await p.waitForTimeout(3000);
const after = await p.evaluate(() => ({ jump: (document.querySelector('.mr-datejump') || {}).textContent?.replace(/\s+/g, ' ').trim().slice(0, 40), cards: document.querySelectorAll('[data-post-id]').length, filled: document.querySelectorAll('.mr-card').length }));
console.log('点击后:', JSON.stringify(after));
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/custom-calendar.png' });
await b.close();