import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
for (let i = 0; i < 6; i++) { await p.mouse.wheel(0, 1500); await p.waitForTimeout(400); }
await p.waitForTimeout(1000);
const before = await p.evaluate(() => Math.round(window.scrollY));
// 用 JS 直接点，避免 Playwright 点击时的自动滚动干扰
await p.evaluate(() => { const cards = [...document.querySelectorAll('[data-post-id]')]; const vis = cards.find((e) => { const r = e.getBoundingClientRect(); return r.top > 100 && r.bottom < window.innerHeight; }) || cards[0]; vis.click(); });
await p.waitForTimeout(1800);
const during = await p.evaluate(() => Math.round(window.scrollY));
await p.mouse.move(200, 500);
await p.mouse.wheel(0, 1200);
await p.waitForTimeout(600);
const afterWheel = await p.evaluate(() => Math.round(window.scrollY));
await p.evaluate(() => { const btn = [...document.querySelectorAll('.mr-sheet-head button')].find((x) => x.textContent.includes('关闭')); btn && btn.click(); });
await p.waitForTimeout(700);
const afterClose = await p.evaluate(() => Math.round(window.scrollY));
console.log('打开前:', before, '| 打开后:', during, before === during ? '（没动 ✓）' : '（动了 ✗）');
console.log('弹窗内滚轮后:', afterWheel, afterWheel === during ? '（背后没动 ✓）' : '（背后动了 ✗）');
console.log('关闭后:', afterClose, afterClose === before ? '（原位 ✓）' : '（变了 ✗）');
await b.close();