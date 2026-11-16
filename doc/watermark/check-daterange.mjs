import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1905, height: 929 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const controls = await p.evaluate(() => [...document.querySelectorAll('.mr-dates button, .mr-dates input')].map((e) => e.tagName + ':' + (e.textContent || e.placeholder || '').trim().slice(0, 10)));
console.log('controls:', JSON.stringify(controls));
// 直接操作选择器：填入按月范围 2026-11
await p.locator('.mr-dates .mr-chip', { hasText: '按月' }).click();
await p.waitForTimeout(500);
const inputs = p.locator('.mr-datepicker input');
console.log('inputs:', await inputs.count());
await inputs.nth(0).click();
await p.waitForTimeout(600);
// 选择 2026 年 11 月 → 再点 11 月（monthrange 需要点两次）
const cell = p.locator('.el-month-table td').filter({ hasText: '11月' }).first();
if (await cell.count()) { await cell.click(); await p.waitForTimeout(400); await cell.click(); }
await p.waitForTimeout(2500);
const state = await p.evaluate(() => ({ label: (document.querySelector('.mr-date-label') || {}).textContent, cards: document.querySelectorAll('.mr-card').length, dates: [...document.querySelectorAll('.mr-card .mr-meta span')].slice(0, 4).map((s) => s.textContent) }));
console.log('after month range:', JSON.stringify(state));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-daterange.png' });
await b.close();