import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const st = await p.evaluate(() => ({
  head: !!document.querySelector('.tl-head-actions .mr-search'),
  viewsInHead: !!document.querySelector('.tl-head-actions .mr-views'),
  barSearch: !!document.querySelector('.mr-bar .mr-search'),
  cats: (() => { const el = document.querySelector('.mr-cats'); const cs = getComputedStyle(el); return { wrap: cs.flexWrap, h: Math.round(el.getBoundingClientRect().height) }; })()
}));
console.log('布局:', JSON.stringify(st), '| errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/head-actions.png' });
await b.close();