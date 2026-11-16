import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
// 先滚到中段并加载几页
await p.evaluate(() => window.scrollTo(0, 4000));
await p.waitForTimeout(1500);
const before = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY) }));
// 打开一张卡 → 展开整页 → 返回列表
await p.locator('[data-post-id]').nth(3).click();
await p.waitForTimeout(2000);
const openedId = await p.evaluate(() => (document.querySelector('.mr-sheet .md-title') || {}).textContent?.slice(0, 10));
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(3000);
console.log('整页 URL:', p.url());
await p.locator('.mp-back').click();
await p.waitForTimeout(3500);
const after = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY), url: location.pathname + location.search, hit: document.querySelectorAll('.mr-hit').length }));
console.log('返回前:', JSON.stringify(before));
console.log('返回后:', JSON.stringify(after));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/restore-after.png' });
await b.close();