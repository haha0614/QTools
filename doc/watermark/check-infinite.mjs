import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const start = await p.evaluate(() => document.querySelectorAll('[data-post-id]').length);
console.log('initial cards:', start);
// 连续快速下滑，模拟"一直往下看"
for (let i = 0; i < 8; i++) { await p.mouse.wheel(0, 4000); await p.waitForTimeout(700); }
await p.waitForTimeout(2500);
const after = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, nearBottom: Math.round(document.documentElement.scrollHeight - document.documentElement.scrollTop - document.documentElement.clientHeight) }));
console.log('after scrolling:', JSON.stringify(after));
await b.close();