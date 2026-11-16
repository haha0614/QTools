import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1905, height: 929 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
const f = await p.evaluate(() => { const s = document.querySelector('.mr-featured'); return s ? { cards: s.querySelectorAll('.mr-fcard').length, imgs: [...s.querySelectorAll('img')].filter((i) => i.naturalWidth > 0).length, first: (s.querySelector('.mr-ftitle') || {}).textContent } : null; });
console.log('featured strip:', JSON.stringify(f));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-featured.png' });
// 选中「每日大赛」分类 → 应出现置顶块
await p.locator('.mr-bar .mr-chip', { hasText: '每日大赛' }).first().click();
await p.waitForTimeout(2500);
const pin = await p.evaluate(() => { const s = document.querySelector('.mr-pinned'); return s ? { cards: s.querySelectorAll('.mr-pcard').length, imgs: [...s.querySelectorAll('img')].filter((i) => i.naturalWidth > 0).length, titles: [...s.querySelectorAll('.mr-ptitle')].map((t) => t.textContent.slice(0, 16)) } : null; });
console.log('pinned block:', JSON.stringify(pin));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-pinned.png' });
await b.close();