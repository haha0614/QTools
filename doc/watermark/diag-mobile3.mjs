import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const res = await p.evaluate(() => {
  const cw = document.documentElement.clientWidth;
  const out = [];
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect();
    if (r.right > cw + 2 && r.width > 40) {
      const st = getComputedStyle(el);
      out.push({ cls: el.tagName.toLowerCase() + '.' + String(el.className).split(' ').slice(0, 2).join('.'), w: Math.round(r.width), right: Math.round(r.right), ox: st.overflowX });
    }
  }
  return { cw, count: out.length, first: out.slice(0, 14) };
});
console.log(JSON.stringify(res, null, 1));
await b.close();