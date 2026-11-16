import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1905, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3200);
// 打开第 2 张卡的详情，点收藏，再关闭
await p.locator('.mr-card').nth(1).click();
await p.waitForTimeout(2500);
await p.evaluate(() => { const b = [...document.querySelectorAll('.mr-sheet .md-btn')].find((x) => x.textContent.indexOf('收藏') >= 0); if (b) b.click(); });
await p.waitForTimeout(1200);
const detailState = await p.evaluate(() => { const b = [...document.querySelectorAll('.mr-sheet .md-btn')].find((x) => x.textContent.indexOf('收藏') >= 0); return b ? b.textContent.trim() : null; });
console.log('detail after fav:', detailState);
await p.locator('.mr-sheet button', { hasText: '关闭' }).click();
await p.waitForTimeout(2000);
const cardState = await p.evaluate(() => ({ favOn: document.querySelectorAll('.mr-fav.on').length, chip: [...document.querySelectorAll('.mr-dates .mr-chip')].map((c) => c.textContent.trim()).find((t) => t.indexOf('收藏') >= 0) }));
console.log('after closing modal:', JSON.stringify(cardState));
// 切到「收藏」列表
await p.locator('.mr-dates .mr-chip', { hasText: '收藏' }).click();
await p.waitForTimeout(2200);
const favList = await p.evaluate(() => ({ cards: document.querySelectorAll('.mr-card').length, titles: [...document.querySelectorAll('.mr-card .mr-title')].slice(0, 3).map((t) => t.textContent.slice(0, 14)) }));
console.log('favorites list:', JSON.stringify(favList));
// 切到浏览记录
await p.locator('.mr-dates .mr-chip', { hasText: '浏览记录' }).click();
await p.waitForTimeout(2200);
const seenList = await p.evaluate(() => ({ cards: document.querySelectorAll('.mr-card').length, seenBadges: document.querySelectorAll('.mr-seen').length }));
console.log('history list:', JSON.stringify(seenList));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-favlist.png' });
await b.close();