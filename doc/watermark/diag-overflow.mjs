import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 360, height: 800 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const res = await p.evaluate(() => {
  const cw = document.documentElement.clientWidth;
  const out = [];
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect();
    if (r.right > cw + 200) {
      // 只有真正撑宽文档的才算：所有祖先都没有 overflow 裁剪
      let clipped = false;
      let a = el.parentElement;
      while (a) { const st = getComputedStyle(a); if (st.overflowX !== 'visible') { clipped = true; break; } a = a.parentElement; }
      if (!clipped) out.push({ cls: el.tagName.toLowerCase() + '.' + String(el.className).split(' ').slice(0, 2).join('.'), right: Math.round(r.right), w: Math.round(r.width) });
    }
  }
  return { cw, docW: document.documentElement.scrollWidth, n: out.length, first: out.slice(0, 6) };
});
console.log(JSON.stringify(res));
await b.close();