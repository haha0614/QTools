import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const results = [];
for (const i of [0, 3, 6]) {
  const card = p.locator('.mr-grid .mr-card').nth(i);
  await card.scrollIntoViewIfNeeded();
  await p.waitForTimeout(400);
  await card.click();
  await p.waitForTimeout(200);
  const flying = await p.evaluate(() => ({ heroes: document.querySelectorAll('.mr-hero').length, moving: document.querySelectorAll('.mr-hero.moving').length, hidden: document.querySelectorAll('.mr-sheet-hero').length }));
  await p.waitForTimeout(900);
  const landed = await p.evaluate(() => ({ heroes: document.querySelectorAll('.mr-hero').length, sheetHero: document.querySelectorAll('.mr-sheet-hero').length, stageOpacity: (() => { const s = document.querySelector('.md-hero-target'); return s ? getComputedStyle(s).opacity : null; })(), title: (document.querySelector('.md-hero-title') || {}).textContent?.slice(0, 14) }));
  results.push({ i, flying, landed });
  await p.locator('.mr-sheet-head button', { hasText: '关闭' }).click();
  await p.waitForTimeout(600);
}
console.log(JSON.stringify(results, null, 1));
const final = await p.evaluate(() => ({ heroes: document.querySelectorAll('.mr-hero').length, mask: document.querySelectorAll('.mr-mask').length }));
console.log('after closing all:', JSON.stringify(final));
await b.close();