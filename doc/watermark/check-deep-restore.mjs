import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
// 直接跳到很深的页（模拟翻到第 20 页）
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
const deep = await p.evaluate(() => ({ y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length }));
console.log('跳到第 20 页后:', JSON.stringify(deep));
// 打开一张卡 → 展开整页 → 返回，计时
await p.evaluate(() => { const cards=[...document.querySelectorAll('[data-post-id]')]; const vis=cards.find((e)=>{const r=e.getBoundingClientRect();return r.top>100&&r.bottom<window.innerHeight;})||cards[0]; vis.click(); });
await p.waitForTimeout(1200);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(1800);
const t0 = Date.now();
await p.locator('.mp-back').click();
let visibleAt = null;
for (let i = 0; i < 40; i++) {
  const st = await p.evaluate(() => { const skel = !!document.querySelector('.mr-skel-wrap'); const el = document.querySelector('.mrds'); return { skel, op: el ? Number(getComputedStyle(el).opacity) : 0, y: Math.round(window.scrollY) }; });
  if (!st.skel && st.op > 0.9 && visibleAt === null) visibleAt = Date.now() - t0;
  if (visibleAt !== null) break;
  await p.waitForTimeout(50);
}
await p.waitForTimeout(400);
const after = await p.evaluate(() => ({ y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length }));
console.log('返回可见耗时:', visibleAt, 'ms | 回来后:', JSON.stringify(after));
await b.close();