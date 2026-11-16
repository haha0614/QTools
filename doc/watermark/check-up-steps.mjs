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
const id0 = await p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].find((e)=>e.getBoundingClientRect().bottom>120); return c.getAttribute('data-post-id'); });
let prev = await p.evaluate((id) => Math.round(document.querySelector('[data-post-id="'+id+'"]').getBoundingClientRect().top), id0);
console.log('起点 top =', prev);
for (let i = 0; i < 6; i++) {
  await p.mouse.wheel(0, -700);
  await p.waitForTimeout(900);
  const t = await p.evaluate((id) => { const e=document.querySelector('[data-post-id="'+id+'"]'); return e?Math.round(e.getBoundingClientRect().top):null; }, id0);
  const n = await p.evaluate(() => document.querySelectorAll('[data-post-id]').length);
  console.log('第' + (i+1) + '次上滑: 参照卡 top=' + t + ' (增加 ' + (t - prev) + ', 期望约 700) 卡片数=' + n);
  prev = t;
}
await b.close();