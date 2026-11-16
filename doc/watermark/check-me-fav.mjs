import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1500, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
// 收藏第一张卡
const card = p.locator('.mr-card').first();
await card.hover();
await card.locator('.mr-fav').click({ force: true });
await p.waitForTimeout(1500);
const fav = await p.evaluate(() => document.querySelectorAll('.mr-fav.on').length);
console.log('tool fav count:', fav);
// 去个人中心
await p.goto('http://localhost/me', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
const me = await p.evaluate(() => { const s = document.querySelector('#me-tool-fav'); if (!s) return { found: false, bodyHas: document.body.innerText.slice(0, 80) }; return { found: true, cards: s.querySelectorAll('.me-tool-card').length, title: (s.querySelector('.me-sec-title') || {}).textContent }; });
console.log('me page:', JSON.stringify(me));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/me-tool-fav.png' });
await b.close();