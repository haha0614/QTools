import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=20&size=24');
  const j = await r.json();
  const row = (j.rows || [])[0];
  localStorage.setItem('ms.mrds.scrollAnchor', JSON.stringify({ id: row.id, title: row.title, page: 20, top: 120, at: Date.now() }));
});
await p.reload({ waitUntil: 'domcontentloaded' });
await p.waitForTimeout(4000);
await p.locator('.mr-resume button', { hasText: '回去' }).click();
await p.waitForTimeout(2500);
// 记录当前视口最上面那张卡（这就是用户看到的“第一条”）
const before = await p.evaluate(() => {
  const cards = [...document.querySelectorAll('[data-post-id]')];
  const vis = cards.find((e) => { const r = e.getBoundingClientRect(); return r.bottom > 120; });
  const box = vis.getBoundingClientRect();
  return { id: vis.getAttribute('data-post-id'), top: Math.round(box.top), y: Math.round(window.scrollY), cards: cards.length };
});
await p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].find((e)=>{const r=e.getBoundingClientRect();return r.top>100&&r.bottom<window.innerHeight;}); c.click(); });
await p.waitForTimeout(1200);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(1800);
const t0 = Date.now();
await p.locator('.mp-back').click();
let visibleAt = null;
for (let i = 0; i < 40; i++) {
  const st = await p.evaluate(() => { const skel = !!document.querySelector('.mr-skel-wrap'); const el = document.querySelector('.mrds'); return { skel, op: el ? Number(getComputedStyle(el).opacity) : 0 }; });
  if (!st.skel && st.op > 0.9 && visibleAt === null) { visibleAt = Date.now() - t0; break; }
  await p.waitForTimeout(50);
}
await p.waitForTimeout(500);
const after = await p.evaluate((id) => {
  const el = document.querySelector('[data-post-id="' + id + '"]');
  return { sameCardTop: el ? Math.round(el.getBoundingClientRect().top) : null, y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length, newer: !!document.querySelector('.mr-newer') };
}, before.id);
console.log('离开前: 首条', before.id, 'top=', before.top, '| y=', before.y, '| cards=', before.cards);
console.log('可见耗时:', visibleAt, 'ms');
console.log('返回后: 同一条卡的 top=', after.sameCardTop, '| y=', after.y, '| cards=', after.cards, '| 有“加载更新的”:', after.newer);
console.log(Math.abs((after.sameCardTop ?? -999) - before.top) <= 6 ? '画面对得上 ✓' : '画面对不上 ✗');
await b.close();