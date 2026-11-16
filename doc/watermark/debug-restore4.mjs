import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
p.on('console', (m) => { if (m.text().startsWith('[restore]')) console.log(m.text()); });
await p.addInitScript(() => { window.__mrDebugRestore = true; });
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
for (let i = 0; i < 4; i++) { await p.mouse.wheel(0, 1200); await p.waitForTimeout(400); }
await p.evaluate(() => { document.querySelector('[data-post-id]').click(); });
await p.waitForTimeout(1200);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(2000);
await p.locator('.mp-back').click();
await p.waitForTimeout(3000);
console.log('最终 y =', await p.evaluate(() => Math.round(window.scrollY)));
await b.close();