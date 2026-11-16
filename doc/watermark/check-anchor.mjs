import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
// 往下滑很多（触发自动加载），记录当时的锚点
for (let i = 0; i < 10; i++) { await p.mouse.wheel(0, 4000); await p.waitForTimeout(600); }
await p.waitForTimeout(2500);
const before = await p.evaluate(() => {
  const cards = [...document.querySelectorAll('[data-post-id]')];
  let firstVisible = null;
  for (const c of cards) { if (c.getBoundingClientRect().bottom > 80) { firstVisible = c; break; } }
  const anchor = JSON.parse(window.localStorage.getItem('ms.mrds.scrollAnchor') || 'null');
  return { cards: cards.length, scrollY: Math.round(window.scrollY), visibleId: firstVisible ? firstVisible.getAttribute('data-post-id') : null, anchor };
});
console.log('before reload:', JSON.stringify(before));
await p.reload({ waitUntil: 'networkidle' });
await p.waitForTimeout(4500);
const after = await p.evaluate(() => {
  const cards = [...document.querySelectorAll('[data-post-id]')];
  let firstVisible = null;
  for (const c of cards) { if (c.getBoundingClientRect().bottom > 80) { firstVisible = c; break; } }
  return { cards: cards.length, scrollY: Math.round(window.scrollY), visibleId: firstVisible ? firstVisible.getAttribute('data-post-id') : null, banner: (document.querySelector('.mr-newer-text') || {}).textContent?.trim(), hit: document.querySelectorAll('.mr-hit').length };
});
console.log('after reload:', JSON.stringify(after));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-anchor.png' });
await b.close();