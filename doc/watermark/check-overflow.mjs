import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
const info = await p.evaluate(() => {
  const row = document.querySelector('.mr-dates-all');
  const rr = row.getBoundingClientRect();
  const kids = [...row.children].map((k) => { const r = k.getBoundingClientRect(); return { t: (k.textContent || '').trim().slice(0, 6) || k.tagName, w: Math.round(r.width), x: Math.round(r.x - rr.x), w2: Math.round(r.right - rr.x) }; });
  const maxRight = Math.max(...kids.map((k) => k.w2));
  return { rowW: Math.round(rr.width), maxRight, over: kids.filter((k) => k.w2 > rr.width + 1).map((k) => k.t) };
});
console.log(JSON.stringify(info));
await b.close();