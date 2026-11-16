import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
await p.evaluate(() => window.scrollTo(0, 800));
await p.waitForTimeout(400);
const before = await p.evaluate(() => Math.round(window.scrollY));
// 打开详情弹窗
await p.locator('.mr-card').first().click();
await p.waitForTimeout(2500);
const locked = await p.evaluate(() => ({ htmlOverflow: document.documentElement.style.overflow, bodyOverflow: document.body.style.overflow, lock: window.__mrScrollLock }));
// 在弹窗外的遮罩上滚轮，检查背后是否跟着滚
await p.mouse.move(200, 500);
await p.mouse.wheel(0, 1200);
await p.waitForTimeout(700);
const afterWheel = await p.evaluate(() => Math.round(window.scrollY));
console.log('locked:', JSON.stringify(locked), '| scrollY', before, '→', afterWheel, before === afterWheel ? '（背后没动 ✓）' : '（背后滚了 ✗）');
// 关闭后应恢复
await p.locator('.mr-sheet-head button', { hasText: '关闭' }).click();
await p.waitForTimeout(600);
const unlocked = await p.evaluate(() => ({ htmlOverflow: document.documentElement.style.overflow, lock: window.__mrScrollLock }));
console.log('after close:', JSON.stringify(unlocked));
await p.mouse.wheel(0, 600);
await p.waitForTimeout(600);
console.log('滚动恢复:', await p.evaluate(() => Math.round(window.scrollY)));
await b.close();