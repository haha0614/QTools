import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
for (let i = 0; i < 4; i++) { await p.mouse.wheel(0, 1200); await p.waitForTimeout(400); }
await p.evaluate(() => { document.querySelector('[data-post-id]').click(); });
await p.waitForTimeout(1200);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(2000);
console.log('整页 URL =', p.url());
await p.locator('.mp-back').click();
await p.waitForTimeout(150);
console.log('返回瞬间:', JSON.stringify(await p.evaluate(() => ({ url: location.pathname + location.search, snap: !!sessionStorage.getItem('ms.mrds.listState') }))));
await p.waitForTimeout(2500);
console.log('2.5s 后:', JSON.stringify(await p.evaluate(() => ({ url: location.pathname + location.search, snap: !!sessionStorage.getItem('ms.mrds.listState'), y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length }))));
await b.close();