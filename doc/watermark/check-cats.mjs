import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const info = await p.evaluate(() => {
  const el = document.querySelector('.mr-cats');
  const cs = getComputedStyle(el);
  const kids = [...el.children];
  const tops = Array.from(new Set(kids.map((k) => Math.round(k.getBoundingClientRect().top))));
  return { display: cs.display, flexWrap: cs.flexWrap, overflowX: cs.overflowX, w: Math.round(el.getBoundingClientRect().width), h: Math.round(el.getBoundingClientRect().height), kids: kids.length, tops: tops.length, parent: el.parentElement.className };
});
console.log(JSON.stringify(info));
await b.close();