import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
await p.locator('.mr-cal-btn').first().click();
await p.waitForTimeout(1200);
// 选中一天
await p.evaluate(() => { const c=[...document.querySelectorAll('.mc-cell')].filter((x)=>!x.classList.contains('empty')); c[9].click(); });
await p.waitForTimeout(2500);
await p.locator('.mr-cal-btn').first().click();
await p.waitForTimeout(1000);
// hover 选中的那天
const box = await p.locator('.mc-cell.on').first().boundingBox();
if (box) { await p.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await p.waitForTimeout(300); }
const st = await p.evaluate(() => { const el = document.querySelector('.mc-cell.on'); if (!el) return null; const cs = getComputedStyle(el); return { bg: cs.backgroundColor, color: cs.color }; });
console.log('选中日 hover 样式:', JSON.stringify(st), st && st.color.includes('255') ? '（白字 ✓）' : '（文字不是白色 ✗）');
await b.close();