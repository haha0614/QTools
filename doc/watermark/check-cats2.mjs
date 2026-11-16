import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
console.log(JSON.stringify(await p.evaluate(() => {
  const el = document.querySelector('.mr-cats');
  const kids = [...el.children].map((k) => { const r = k.getBoundingClientRect(); return { h: Math.round(r.height), w: Math.round(r.width), top: Math.round(r.top) }; });
  const r = el.getBoundingClientRect();
  return { box: { top: Math.round(r.top), h: Math.round(r.height) }, first: kids[0], last: kids[kids.length - 1], sumW: kids.reduce((a, k) => a + k.w + 6, 0) };
})));
await b.close();