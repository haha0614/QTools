import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
p.on('pageerror', (e) => console.log('PAGEERR:', String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
// 跳到第 12 页，制造“上方还有内容”的场景
await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=12&size=24');
  const j = await r.json();
  localStorage.setItem('ms.mrds.scrollAnchor', JSON.stringify({ id: (j.rows||[])[0].id, title: (j.rows||[])[0].title, page: 12, top: 120, at: Date.now() }));
});
await p.reload({ waitUntil: 'domcontentloaded' });
await p.waitForTimeout(4000);
await p.locator('.mr-resume button', { hasText: '回去' }).click();
await p.waitForTimeout(2200);
const s0 = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY), start: document.querySelector('.mr-newer') ? document.querySelector('.mr-newer').innerText.replace(/\s+/g,' ').slice(0,30) : '-' }));
console.log('落定:', JSON.stringify(s0));
// 用真实滚轮往上滚到顶
for (let i = 0; i < 12; i++) { await p.mouse.wheel(0, -1200); await p.waitForTimeout(250); }
await p.waitForTimeout(2500);
const s1 = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY) }));
console.log('滚到顶后自动加载:', JSON.stringify(s1), s1.cards > s0.cards ? '（已自动补齐更上面的内容 ✓）' : '（没有自动加载 ✗）');
// 再往上滚一次，应继续加
for (let i = 0; i < 8; i++) { await p.mouse.wheel(0, -1200); await p.waitForTimeout(250); }
await p.waitForTimeout(2500);
const s2 = await p.evaluate(() => ({ cards: document.querySelectorAll('[data-post-id]').length, y: Math.round(window.scrollY) }));
console.log('继续上滚:', JSON.stringify(s2), s2.cards > s1.cards ? '（继续自动加载 ✓）' : '（停止了）');
await b.close();