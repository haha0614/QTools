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
const snap = () => p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].find((e)=>e.getBoundingClientRect().bottom>120); return { id: c.getAttribute('data-post-id'), top: Math.round(c.getBoundingClientRect().top), n: document.querySelectorAll('[data-post-id]').length }; });
const before = await snap();
// 一次向上滚动：既触发加载，又改变位置
await p.mouse.wheel(0, -700);
await p.waitForTimeout(1400);
const el = await p.evaluate((id) => { const e=document.querySelector('[data-post-id="'+id+'"]'); return e?Math.round(e.getBoundingClientRect().top):null; }, before.id);
console.log('加载前视口首卡:', JSON.stringify(before));
console.log('同一张卡在加载+滚动后 top =', el, '（预期约 ' + (before.top - 700) + ' 再加上前插补偿；不该出现「比预期更靠下」的大跳）');
const now = await snap();
console.log('此时视口首卡:', JSON.stringify(now));
await b.close();