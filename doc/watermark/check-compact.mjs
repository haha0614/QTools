import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
const info = await p.evaluate(() => {
  const row = document.querySelector('.mr-dates-all');
  const kids = [...row.children].map((k) => ({ t: (k.textContent || '').trim().slice(0, 8), w: Math.round(k.getBoundingClientRect().width), top: Math.round(k.getBoundingClientRect().top) }));
  const tops = [...new Set(kids.map((k) => k.top))];
  return { rowW: Math.round(row.getBoundingClientRect().width), rowH: Math.round(row.getBoundingClientRect().height), lines: tops.length, totalW: kids.reduce((a, k) => a + k.w + 6, 0) };
});
console.log(JSON.stringify(info));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/filter-compact.png' });
await b.close();