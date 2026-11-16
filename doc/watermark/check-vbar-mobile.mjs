import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds?focus=97', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
await p.evaluate(() => document.querySelector('[data-post-id="97"]').click());
await p.waitForTimeout(6000);
const st = await p.evaluate(() => {
  const btn = document.querySelector('.md-vdl');
  const box = document.querySelector('.md-player-box');
  const r1 = btn.getBoundingClientRect(); const r2 = box.getBoundingClientRect();
  const overlap = !(r1.bottom <= r2.top || r2.bottom <= r1.top || r1.right <= r2.left || r2.right <= r1.left);
  return { btn: btn.textContent.trim(), overlapsVideo: overlap, btnVisible: r1.width > 0 && r1.height > 0 };
});
console.log('移动端:', JSON.stringify(st));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/vbar-mobile.png' });
await b.close();