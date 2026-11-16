import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const st = await p.evaluate(() => {
  const row = document.querySelector('.mr-dates-all');
  const kids = row ? [...row.children] : [];
  const tops = new Set(kids.map((k) => Math.round(k.getBoundingClientRect().top)));
  return {
    rows: tops.size,
    height: row ? Math.round(row.getBoundingClientRect().height) : null,
    hasAdv: !!row && row.textContent.includes('内容类型'),
    hasFav: !!row && row.textContent.includes('收藏'),
    hasDate: !!row && row.textContent.includes('全部时间')
  };
});
console.log('合并后的筛选行:', JSON.stringify(st), '| errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/filter-onerow.png' });
await b.close();