import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const res = await p.evaluate(() => {
  const cw = document.documentElement.clientWidth;
  const kids = [...document.body.children].map((el) => ({ cls: el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + '.' + String(el.className).split(' ').slice(0, 2).join('.'), w: Math.round(el.getBoundingClientRect().width), right: Math.round(el.getBoundingClientRect().right), pos: getComputedStyle(el).position }));
  const wideFixed = [];
  for (const el of document.querySelectorAll('body *')) {
    const st = getComputedStyle(el);
    if (st.position !== 'fixed' && st.position !== 'absolute') continue;
    const r = el.getBoundingClientRect();
    if (r.right > cw + 2) wideFixed.push({ cls: el.tagName.toLowerCase() + '.' + String(el.className).split(' ').slice(0, 2).join('.'), right: Math.round(r.right), w: Math.round(r.width) });
  }
  return { cw, docW: document.documentElement.scrollWidth, bodyKids: kids, wideFixed: wideFixed.slice(0, 8) };
});
console.log(JSON.stringify(res, null, 1).slice(0, 1800));
await b.close();