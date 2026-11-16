import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const cardFav = await p.evaluate(() => { const btn = document.querySelector('.mr-card .mr-fav'); if (!btn) return null; const cs = getComputedStyle(btn); return { text: btn.textContent.trim(), opacity: cs.opacity, visible: cs.display !== 'none' }; });
console.log('card fav button:', JSON.stringify(cardFav));
// 打开详情，点详情页的收藏按钮
await p.locator('.mr-card').first().click();
await p.waitForTimeout(2500);
const before = await p.evaluate(() => { const b = [...document.querySelectorAll('.mr-sheet .md-btn')].find((x) => x.textContent.indexOf('收藏') >= 0); return b ? { text: b.textContent.trim(), disabled: b.disabled } : null; });
console.log('detail fav before:', JSON.stringify(before));
await p.evaluate(() => { const b = [...document.querySelectorAll('.mr-sheet .md-btn')].find((x) => x.textContent.indexOf('收藏') >= 0); if (b) b.click(); });
await p.waitForTimeout(1500);
const after = await p.evaluate(() => { const b = [...document.querySelectorAll('.mr-sheet .md-btn')].find((x) => x.textContent.indexOf('收藏') >= 0); return b ? b.textContent.trim() : null; });
console.log('detail fav after click:', after);
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-fav-detail.png' });
await b.close();