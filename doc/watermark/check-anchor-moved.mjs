import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
// 造一个“上次浏览停在”的状态
await p.addInitScript(() => {
  localStorage.setItem('ms.mrds.scrollAnchor', JSON.stringify({ id: 999, title: '测试锚点', page: 5, top: 120, at: Date.now() - 780000 }));
});
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const pos = await p.evaluate(() => {
  const y = (sel, text) => { const els = [...document.querySelectorAll(sel)]; const el = text ? els.find((e) => e.textContent.includes(text)) : els[0]; return el ? Math.round(el.getBoundingClientRect().top) : null; };
  return { anchorBar: y('.mr-resume', '上次浏览停在'), featured: y('.mr-featured'), pinned: y('.mr-pinned'), firstCard: y('[data-post-id]') };
});
console.log('位置:', JSON.stringify(pos));
console.log(pos.anchorBar !== null && pos.anchorBar < pos.featured ? '「上次浏览停在」已在顶部区域 ✓' : '位置不对 ✗');
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/anchor-moved.png' });
await b.close();