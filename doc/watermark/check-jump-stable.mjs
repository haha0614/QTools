import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
const id = await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=18&size=24');
  const j = await r.json();
  const row = (j.rows || [])[3];
  localStorage.setItem('ms.mrds.scrollAnchor', JSON.stringify({ id: row.id, title: row.title, page: 18, top: 140, at: Date.now() }));
  return row.id;
});
await p.reload({ waitUntil: 'domcontentloaded' });
await p.waitForTimeout(4000);
await p.locator('.mr-resume button', { hasText: '回去' }).click();
// 观察 4 秒：位置是否被后来涌入的内容顶走
const trace = [];
for (let i = 0; i < 20; i++) {
  trace.push(await p.evaluate((x) => { const el=document.querySelector('[data-post-id="'+x+'"]'); return el ? Math.round(el.getBoundingClientRect().top) + '/' + document.querySelectorAll('[data-post-id]').length : 'gone'; }, id));
  await p.waitForTimeout(250);
}
console.log('问题1 锚点 top/条数 轨迹:', JSON.stringify(trace));
const stable = trace.slice(3).every((t) => String(t).startsWith('140/'));
console.log(stable ? '4 秒内没有被顶走 ✓' : '位置/条数发生变化 ✗');
// 问题2：回到顶部 → 第一条应是最新内容，且下滑不触发加载
await p.evaluate(() => { const btn = document.querySelector('.mr-top'); if (btn) btn.click(); });
await p.waitForTimeout(2500);
const top = await p.evaluate(() => ({ y: Math.round(window.scrollY), first: (document.querySelector('[data-post-id]') || {}).getAttribute && document.querySelector('[data-post-id]').getAttribute('data-post-id'), date: (document.querySelector('.mr-card .mr-date') || {}).textContent?.trim(), start: document.querySelector('.mr-newer') ? document.querySelector('.mr-newer-text').textContent.trim().slice(0, 26) : '(无提示条)' }));
console.log('回到顶部后:', JSON.stringify(top));
const n0 = await p.evaluate(() => document.querySelectorAll('[data-post-id]').length);
await p.mouse.wheel(0, 300);
await p.waitForTimeout(1800);
const n1 = await p.evaluate(() => document.querySelectorAll('[data-post-id]').length);
console.log('下滑 300 后条数:', n0, '→', n1, n0 === n1 ? '（没有触发加载 ✓）' : '（又加载了 ✗）');
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await b.close();