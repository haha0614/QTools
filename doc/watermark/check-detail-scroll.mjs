import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds/post/5', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(6000);
const info = await p.evaluate(() => {
  const pick = (sel) => { const el = document.querySelector(sel); if (!el) return null; const cs = getComputedStyle(el); return { sel, ovY: cs.overflowY, sh: el.scrollHeight, ch: el.clientHeight, top: Math.round(el.getBoundingClientRect().top), h: Math.round(el.getBoundingClientRect().height) }; };
  return { docScrollable: document.documentElement.scrollHeight > window.innerHeight, innerH: window.innerHeight, els: ['.mr-sheet', '.md', '.md-layout', '.md-side', '.md-main', 'body'].map(pick) };
});
console.log(JSON.stringify(info, null, 1).slice(0, 1200));
// 试着滚
await p.mouse.move(1000, 500);
await p.mouse.wheel(0, 900);
await p.waitForTimeout(600);
console.log('滚动后 window.scrollY =', await p.evaluate(() => Math.round(window.scrollY)));
await b.close();