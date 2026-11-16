import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate(() => document.querySelector('[data-post-id]').click());
await p.waitForTimeout(3500);
const scrollers = await p.evaluate(() => {
  const out = [];
  document.querySelectorAll('.mr-mask *').forEach((el) => {
    const cs = getComputedStyle(el);
    if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight > el.clientHeight + 5) {
      out.push({ cls: el.className.toString().slice(0, 40), sh: el.scrollHeight, ch: el.clientHeight, ov: cs.overflowY });
    }
  });
  return out.slice(0, 6);
});
console.log('可滚动容器:', JSON.stringify(scrollers));
// 在第一个可滚动容器上滚
await p.mouse.move(1000, 500);
await p.mouse.wheel(0, 800);
await p.waitForTimeout(700);
const tops = await p.evaluate(() => [...document.querySelectorAll('.mr-mask *')].filter((el) => { const cs = getComputedStyle(el); return (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight > el.clientHeight + 5; }).map((el) => Math.round(el.scrollTop)));
console.log('滚动后各容器 scrollTop:', JSON.stringify(tops));
await b.close();