import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1500, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
// 收藏一条 + 打开一条（产生浏览记录）
const card = p.locator('.mr-card').first();
await card.hover();
await card.locator('.mr-fav').click({ force: true });
await p.waitForTimeout(800);
await p.locator('.mr-card').nth(3).click();
await p.waitForTimeout(2500);
await p.locator('.mr-sheet button', { hasText: '关闭' }).click();
await p.waitForTimeout(800);
await p.goto('http://localhost/me', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
const me = await p.evaluate(() => {
  const secs = [...document.querySelectorAll('.me-sec')].map((s) => ({ id: s.id, title: (s.querySelector('.me-sec__h') || {}).textContent, count: (s.querySelector('.me-sec__cnt') || {}).textContent, groups: [...s.querySelectorAll('.me-g__h')].map((g) => g.textContent.trim()) }));
  return { sections: secs, toolCards: document.querySelectorAll('.me-tool-card').length, items: document.querySelectorAll('.mm-item').length };
});
console.log(JSON.stringify(me, null, 1).slice(0, 900));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/me-merged.png', fullPage: false });
await b.close();