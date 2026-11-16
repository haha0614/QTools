import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
p.on('pageerror', (e) => console.log('PAGEERR:', String(e.message).slice(0, 120)));
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
const s0 = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY) }));
console.log('落定:', JSON.stringify(s0));
// 1) 在上方还有内容时向上滚一次，应立刻自动补页（阈值 2000px 内）
await p.mouse.wheel(0, -300);
await p.waitForTimeout(2000);
const s1 = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY) }));
console.log('轻滚一下:', JSON.stringify(s1), s1.cards > s0.cards ? '（提前加载 ✓）' : '（还没加载 ✗）');
// 2) 点回到顶部：应停在顶部且不补页
const beforeTop = await p.evaluate(() => document.querySelectorAll('[data-post-id]').length);
await p.evaluate(() => { const btn = document.querySelector('.mr-top'); if (btn) btn.click(); });
await p.waitForTimeout(3000);
const s2 = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY) }));
console.log('回到顶部后:', JSON.stringify(s2), s2.y === 0 ? '（到顶了 ✓）' : '（没到顶 ✗）', s2.cards === beforeTop ? '| 没有额外补页 ✓' : '| 又补页了 ✗');
await b.close();