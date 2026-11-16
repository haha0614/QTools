import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate(() => document.querySelector('[data-post-id]').click());
await p.waitForTimeout(3500);
// 弹窗内滚动
const sheet = await p.evaluate(() => { const s = document.querySelector('.mr-sheet'); return { scrollable: s ? s.scrollHeight > s.clientHeight : false, top0: s ? Math.round(s.scrollTop) : null }; });
await p.mouse.move(1000, 500);
await p.mouse.wheel(0, 800);
await p.waitForTimeout(600);
const inSheet = await p.evaluate(() => Math.round(document.querySelector('.mr-sheet').scrollTop));
console.log('弹窗内滚轮:', JSON.stringify(sheet), '→ scrollTop =', inSheet, inSheet > 0 ? '（能滚动 ✓）' : '（滚不动 ✗）');
// 遮罩上滚动（弹窗外的空白处）不应带动背景
const bgY0 = await p.evaluate(() => Math.round(window.scrollY));
await p.mouse.move(20, 500);
await p.mouse.wheel(0, 900);
await p.waitForTimeout(500);
const bgY1 = await p.evaluate(() => Math.round(window.scrollY));
console.log('遮罩滚轮: 背景 y', bgY0, '→', bgY1, bgY0 === bgY1 ? '（背后没动 ✓）' : '（背后动了 ✗）');
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await b.close();