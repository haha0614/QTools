import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=16&size=24');
  const j = await r.json();
  localStorage.setItem('ms.mrds.scrollAnchor', JSON.stringify({ id: (j.rows||[])[0].id, title: (j.rows||[])[0].title, page: 16, top: 120, at: Date.now() }));
});
await p.reload({ waitUntil: 'domcontentloaded' });
await p.waitForTimeout(4000);
await p.locator('.mr-resume button', { hasText: '回去' }).click();
await p.waitForTimeout(2200);
const y0 = await p.evaluate(() => Math.round(window.scrollY));
const cards0 = await p.evaluate(() => document.querySelectorAll('[data-post-id]').length);
// 记录视口第一张卡
const before = await p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].find((e)=>e.getBoundingClientRect().bottom>120); return { id: c.getAttribute('data-post-id'), top: Math.round(c.getBoundingClientRect().top) }; });
// 点「加载更新的」，采样滚动位置
await p.locator('.mr-newer button', { hasText: '加载更新的' }).click();
const samples = [];
for (let i = 0; i < 14; i++) { samples.push(await p.evaluate(() => Math.round(window.scrollY))); await p.waitForTimeout(60); }
await p.waitForTimeout(800);
const after = await p.evaluate((id) => { const el=document.querySelector('[data-post-id="'+id+'"]'); return { top: el?Math.round(el.getBoundingClientRect().top):null, cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY) }; }, before.id);
console.log('前: y=', y0, 'cards=', cards0, '锚点 top=', before.top);
console.log('采样:', JSON.stringify(samples), '最小=', Math.min(...samples));
console.log('后: 锚点 top=', after.top, 'cards=', after.cards, '| 锚点保持:', Math.abs((after.top??-999)-before.top)<=6 ? '✓' : '✗');
console.log(Math.min(...samples) >= y0 - 10 ? '没有向上跳 ✓' : '向上跳了 ✗');
await b.close();