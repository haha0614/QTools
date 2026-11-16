import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 360, height: 800 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
await p.locator('.mr-filter-toggle').click();
await p.waitForTimeout(500);
const m = await p.evaluate(() => {
  const picks = [...document.querySelectorAll('.mr-datepicker')].map((e) => { const r = e.getBoundingClientRect(); return { w: Math.round(r.width), top: Math.round(r.top), left: Math.round(r.left) }; });
  const sep = document.querySelector('.mr-date-sep');
  const sr = sep ? sep.getBoundingClientRect() : null;
  return { picks, sep: sr ? { top: Math.round(sr.top), left: Math.round(sr.left) } : null, sameRow: picks.length === 2 ? Math.abs(picks[0].top - picks[1].top) < 4 : null };
});
console.log('date controls:', JSON.stringify(m));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/m-date2.png' });
await b.close();