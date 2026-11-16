import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
const wide = await p.evaluate(() => {
  const out = [];
  const vw = document.documentElement.clientWidth;
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect();
    if (r.width > vw + 4) out.push({ tag: el.tagName + '.' + String(el.className).split(' ').slice(0, 2).join('.'), w: Math.round(r.width) });
  }
  return { vw, innerW: window.innerWidth, list: out.slice(0, 12) };
});
console.log(JSON.stringify(wide, null, 1));
await b.close();