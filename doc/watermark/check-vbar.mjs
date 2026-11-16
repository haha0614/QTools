import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
// 桌面
const ctx1 = await b.newContext({ viewport: { width: 1600, height: 900 } });
const p1 = await ctx1.newPage();
const errs = [];
p1.on('pageerror', (e) => errs.push(String(e.message).slice(0, 90)));
await p1.goto('http://localhost/tool/mrds?focus=97', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p1.waitForTimeout(5000);
await p1.evaluate(() => document.querySelector('[data-post-id="97"]').click());
await p1.waitForTimeout(6000);
const desk = await p1.evaluate(() => {
  const box = document.querySelector('.md-player-box');
  const bar = document.querySelector('.md-vbar');
  const btn = document.querySelector('.md-vdl');
  const overlap = (a, b2) => { const r1 = a.getBoundingClientRect(); const r2 = b2.getBoundingClientRect(); return !(r1.bottom <= r2.top || r2.bottom <= r1.top || r1.right <= r2.left || r2.right <= r1.left); };
  return { barCount: document.querySelectorAll('.md-vbar').length, btnText: btn ? btn.textContent.trim() : null, overlapsVideo: btn && box ? overlap(btn, box) : null, barTop: bar ? Math.round(bar.getBoundingClientRect().top) : null, boxBottom: box ? Math.round(box.getBoundingClientRect().bottom) : null };
});
console.log('桌面:', JSON.stringify(desk));
await p1.screenshot({ path: 'E:/111/project/QTools/doc/shots/vbar-desktop.png' });
await b.close();