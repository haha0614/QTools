import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const order = await p.evaluate(() => {
  const y = (sel) => { const el = document.querySelector(sel); return el ? Math.round(el.getBoundingClientRect().top) : null; };
  return { title: y('.qt-page-title, h1'), bar: y('.mr-bar'), featured: y('.mr-featured'), pinned: y('.mr-pinned'), tags: y('.mr-tags'), desc: !!document.querySelector('.qt-page-desc'), resume: y('.mr-resume') };
});
console.log('位置:', JSON.stringify(order));
console.log(order.bar !== null && order.featured !== null && order.bar < order.featured ? '筛选区在标题下方、精选之前 ✓' : '顺序不对 ✗');
console.log('说明文字已移除:', order.desc ? '否 ✗' : '是 ✓', '| errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/filters-top.png' });
await b.close();