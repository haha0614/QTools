import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const card = p.locator('.mr-grid .mr-card').nth(2);
await card.scrollIntoViewIfNeeded();
await p.waitForTimeout(500);
// 用帧级截图抓中间态：先停住动画时钟不可行，改用连续截图取最早的一帧
await card.click();
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/zoom-1.png' });
await p.waitForTimeout(120);
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/zoom-2.png' });
await p.waitForTimeout(600);
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/zoom-3.png' });
await b.close();