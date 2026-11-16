import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 }, locale: 'zh-CN' });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const cats = await p.evaluate(() => { const el = document.querySelector('.mr-cats'); if (!el) return null; const r = el.getBoundingClientRect(); return { h: Math.round(r.height), rows: Math.round(r.height / 34), sw: el.scrollWidth, cw: el.clientWidth }; });
console.log('分类行:', JSON.stringify(cats));
// 日期控件语言
await p.locator('.mr-date-row .el-date-editor input').first().click().catch(() => {});
await p.waitForTimeout(900);
const panel = await p.evaluate(() => { const el = document.querySelector('.el-picker-panel__content .el-date-table th') ; const head = [...document.querySelectorAll('.el-date-range-picker__header, .el-date-picker__header')].map((x) => x.textContent.trim()).slice(0, 2); const weekday = [...document.querySelectorAll('.el-date-table th')].map((x) => x.textContent.trim()).slice(0, 7); return { head, weekday }; });
console.log('日期控件:', JSON.stringify(panel));
await b.close();