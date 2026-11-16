import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
p.on('pageerror', (e) => console.log('PAGEERR:', String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
for (let i = 0; i < 6; i++) { await p.mouse.wheel(0, 1500); await p.waitForTimeout(400); }
await p.waitForTimeout(1000);
const before = await p.evaluate(() => ({ y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length }));
await p.locator('[data-post-id]').nth(2).click();
await p.waitForTimeout(1800);
// 弹窗打开时在遮罩上滚轮：背后不应动
await p.mouse.move(200, 500);
await p.mouse.wheel(0, 1200);
await p.waitForTimeout(600);
const during = await p.evaluate(() => Math.round(window.scrollY));
await p.locator('.mr-sheet-head button', { hasText: '关闭' }).click();
await p.waitForTimeout(800);
const after = await p.evaluate(() => ({ y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length }));
console.log('打开前:', JSON.stringify(before));
console.log('弹窗内滚轮后 y:', during, during === before.y ? '（背后没动 ✓）' : '（动了 ✗）');
console.log('关闭后:', JSON.stringify(after), after.y === before.y ? '（位置原样 ✓）' : '（位置变了 ✗）');
await b.close();