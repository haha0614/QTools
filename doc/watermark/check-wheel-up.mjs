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
// 记录视口顶部那张卡，模拟真实上滑，观察它是否稳定
const watchId = await p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].find((e)=>e.getBoundingClientRect().bottom>120); return c.getAttribute('data-post-id'); });
const trace = [];
for (let i = 0; i < 10; i++) {
  await p.mouse.wheel(0, -700);
  await p.waitForTimeout(350);
  trace.push(await p.evaluate((id) => { const el=document.querySelector('[data-post-id="'+id+'"]'); return { y: Math.round(window.scrollY), t: el?Math.round(el.getBoundingClientRect().top):null, n: document.querySelectorAll('[data-post-id]').length }; }, watchId));
}
await p.waitForTimeout(2000);
const final = await p.evaluate((id) => { const el=document.querySelector('[data-post-id="'+id+'"]'); return { y: Math.round(window.scrollY), t: el?Math.round(el.getBoundingClientRect().top):null, n: document.querySelectorAll('[data-post-id]').length }; }, watchId);
console.log('轨迹:', JSON.stringify(trace));
console.log('最终:', JSON.stringify(final));
const jumped = trace.some((s, i) => i > 0 && s.t !== null && trace[i-1].t !== null && s.t - trace[i-1].t > 400);
console.log('是否出现内容大幅下跳:', jumped ? '有 ✗' : '无 ✓', '| 最终加载到', final.n, '条');
await b.close();