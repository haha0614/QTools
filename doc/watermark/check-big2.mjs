import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
await p.locator('.mr-view', { hasText: '大卡' }).click();
await p.waitForTimeout(2500);
await p.evaluate(() => window.scrollTo(0, 0));
await p.waitForTimeout(1500);
const st = await p.evaluate(() => {
  const card = document.querySelector('.mr-grid-big .mr-card');
  const body = card.querySelector('.mr-body');
  const title = card.querySelector('.mr-title');
  return { cols: getComputedStyle(document.querySelector('.mr-grid-big')).gridTemplateColumns.split(' ').length,
           cardH: Math.round(card.getBoundingClientRect().height),
           bodyPos: getComputedStyle(body).position,
           titleSize: getComputedStyle(title).fontSize,
           titleColor: getComputedStyle(title).color };
});
console.log('big card:', JSON.stringify(st));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-big2.png', clip: { x: 200, y: 0, width: 1400, height: 900 } });
await b.close();