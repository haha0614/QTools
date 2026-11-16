import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const before = await p.evaluate(() => ({ featured: !!document.querySelector('.mr-featured'), pinned: !!document.querySelector('.mr-pinned'), adv: !!document.querySelector('.mr-adv'), advToggle: !!document.querySelector('.mr-adv-toggle'), catsWrap: getComputedStyle(document.querySelector('.mr-cats')).flexWrap, catRows: new Set([...document.querySelector('.mr-cats').children].map((k) => Math.round(k.getBoundingClientRect().top))).size }));
console.log('未筛选:', JSON.stringify(before));
// 选一个分类 → 精选/置顶应消失
await p.evaluate(() => { const c=[...document.querySelectorAll('.mr-cats .mr-chip')].find((x)=>x.textContent.includes('AI剧场')); c.click(); });
await p.waitForTimeout(2500);
const after = await p.evaluate(() => ({ featured: !!document.querySelector('.mr-featured'), pinned: !!document.querySelector('.mr-pinned'), cards: document.querySelectorAll('[data-post-id]').length }));
console.log('筛选后:', JSON.stringify(after));
console.log(after.featured === false && after.pinned === false ? '筛选时精选/置顶已隐藏 ✓' : '仍然显示 ✗');
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/filter-hidden.png' });
await b.close();