import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
// 移动端
const m = await b.newContext({ viewport: { width: 360, height: 800 } });
const p = await m.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const before = await p.evaluate(() => ({
  toggle: !!document.querySelector('.mr-filter-toggle'),
  toggleText: (document.querySelector('.mr-filter-toggle') || {}).textContent?.trim(),
  moreFilters: (() => { const e = document.querySelector('.mr-more-filters'); return e ? getComputedStyle(e).display : null; })(),
  catsH: Math.round(document.querySelector('.mr-cats').getBoundingClientRect().height),
  searchW: Math.round(document.querySelector('.mr-search').getBoundingClientRect().width),
  barH: Math.round(document.querySelector('.mr-bar').getBoundingClientRect().height),
  docW: document.documentElement.scrollWidth
}));
console.log('mobile collapsed:', JSON.stringify(before));
await p.locator('.mr-filter-toggle').click();
await p.waitForTimeout(500);
const after = await p.evaluate(() => ({
  moreFilters: (() => { const e = document.querySelector('.mr-more-filters'); return e ? getComputedStyle(e).display : null; })(),
  barH: Math.round(document.querySelector('.mr-bar').getBoundingClientRect().height),
  dates: document.querySelectorAll('.mr-more-filters .mr-dates').length,
  views: !!document.querySelector('.mr-more-filters .mr-views')
}));
console.log('mobile expanded:', JSON.stringify(after));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/m-filter1.png' });
await m.close();
// 桌面：确认布局没被破坏
const d = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const dp = await d.newPage();
await dp.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await dp.waitForTimeout(4000);
const desk = await dp.evaluate(() => ({
  toggleVisible: (() => { const e = document.querySelector('.mr-filter-toggle'); return e ? getComputedStyle(e).display : null; })(),
  moreFilters: getComputedStyle(document.querySelector('.mr-more-filters')).display,
  barH: Math.round(document.querySelector('.mr-bar').getBoundingClientRect().height)
}));
console.log('desktop:', JSON.stringify(desk));
await d.close();
await b.close();