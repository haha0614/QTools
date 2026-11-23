import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, args: ['--mute-audio','--no-sandbox'] });
const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded' });
await p.waitForTimeout(3500);
await p.evaluate(() => { const g = document.querySelector('.mr-grid, .mr-cards, .mr-list'); if (g) g.scrollIntoView({ block: 'start' }); else window.scrollBy(0, 900); });
await p.waitForTimeout(2500);
const stat = await p.evaluate(() => {
  const imgs = [...document.querySelectorAll('.mr-cover img')];
  return { cards: document.querySelectorAll('.mr-card').length, imgs: imgs.length, broken: imgs.filter(i => i.complete && i.naturalWidth === 0).length, ph: document.querySelectorAll('.mr-cover-ph').length };
});
await p.screenshot({ path: 'E:/111/project/QTools/tmp/mrds-covers-grid.png' });
console.log(JSON.stringify(stat));
await b.close();
