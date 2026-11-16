import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const views = await p.evaluate(() => [...document.querySelectorAll('.mr-view')].map((v) => v.textContent.trim()));
console.log('views:', JSON.stringify(views));
await p.locator('.mr-view', { hasText: '大卡' }).click();
await p.waitForTimeout(2500);
const big = await p.evaluate(() => {
  const g = document.querySelector('.mr-grid-big');
  const card = document.querySelector('.mr-grid-big .mr-card');
  const title = document.querySelector('.mr-grid-big .mr-title');
  const cols = g ? getComputedStyle(g).gridTemplateColumns.split(' ').length : 0;
  return { cls: g ? g.className : null, cols, cardW: card ? Math.round(card.getBoundingClientRect().width) : 0, titleSize: title ? getComputedStyle(title).fontSize : null, cards: document.querySelectorAll('.mr-grid-big .mr-card').length };
});
console.log('big view:', JSON.stringify(big));
const topBefore = await p.evaluate(() => !!document.querySelector('.mr-top'));
for (let i = 0; i < 4; i++) { await p.mouse.wheel(0, 3000); await p.waitForTimeout(500); }
await p.waitForTimeout(900);
const topAfter = await p.evaluate(() => ({ visible: !!document.querySelector('.mr-top'), scrollY: Math.round(window.scrollY) }));
console.log('top button before/after scroll:', topBefore, JSON.stringify(topAfter));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-big.png' });
await p.locator('.mr-top').click();
await p.waitForTimeout(1500);
const afterClick = await p.evaluate(() => ({ scrollY: Math.round(window.scrollY), visible: !!document.querySelector('.mr-top') }));
console.log('after click top:', JSON.stringify(afterClick));
await b.close();