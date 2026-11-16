import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=12&size=24');
  const j = await r.json();
  localStorage.setItem('ms.mrds.scrollAnchor', JSON.stringify({ id: (j.rows||[])[0].id, title: (j.rows||[])[0].title, page: 12, top: 120, at: Date.now() }));
});
await p.reload({ waitUntil: 'domcontentloaded' });
await p.waitForTimeout(4000);
await p.locator('.mr-resume button', { hasText: '回去' }).click();
await p.waitForTimeout(2200);
const s0 = await p.evaluate(() => { const g = document.querySelector('.mr-grid'); const gt = g ? Math.round(g.getBoundingClientRect().top + window.scrollY) : -1; return { y: Math.round(window.scrollY), listTop: gt, n: document.querySelectorAll('[data-post-id]').length }; });
console.log('落定:', JSON.stringify(s0), '| 距列表顶', s0.y - s0.listTop, 'px（触发阈值 ≈', Math.round(950 * 1.6), 'px）');
// 小步上滑，记录在何时开始加载
let loadedAt = null;
for (let i = 0; i < 12; i++) {
  await p.mouse.wheel(0, -400);
  await p.waitForTimeout(500);
  const st = await p.evaluate(() => ({ y: Math.round(window.scrollY), n: document.querySelectorAll('[data-post-id]').length }));
  if (st.n > s0.n && loadedAt === null) { loadedAt = st.y - s0.listTop; console.log('在第', i+1, '次小步上滑后开始加载，此时 y =', st.y, '（距列表顶', loadedAt, 'px）卡片', st.n); }
}
console.log(loadedAt !== null ? '结论：还在列表顶部上方 ' + loadedAt + 'px 时就已经补内容 ✓' : '结论：12 次上滑仍未触发 ✗');
await b.close();