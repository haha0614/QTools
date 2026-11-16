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
const before = await p.evaluate(() => {
  const cards=[...document.querySelectorAll('[data-post-id]')];
  const vis = cards.find((e)=>{const r=e.getBoundingClientRect();return r.bottom>120;});
  return { anchorId: vis.getAttribute('data-post-id'), top: Math.round(vis.getBoundingClientRect().top), y: Math.round(window.scrollY) };
});
console.log('问题1 离开前锚点:', JSON.stringify(before));
// 点一张卡（故意选视口下方的，验证恢复不会拿它当锚）
await p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].filter((e)=>{const r=e.getBoundingClientRect();return r.top>300&&r.bottom<window.innerHeight;}); c[c.length-1].click(); });
await p.waitForTimeout(1300);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(1800);
await p.locator('.mp-back').click();
await p.waitForTimeout(2500);
const after = await p.evaluate((id) => { const el=document.querySelector('[data-post-id="'+id+'"]'); return { top: el?Math.round(el.getBoundingClientRect().top):null, y: Math.round(window.scrollY) }; }, before.anchorId);
console.log('问题1 返回后同一条卡 top:', after.top, '| 离开前 top:', before.top, Math.abs((after.top??-999)-before.top)<=6 ? '位置对 ✓' : '位置错 ✗');
// 问题2：向上自动加载期间采样，检查是否甩到页面顶部
const samples = [];
for (let i = 0; i < 8; i++) { await p.mouse.wheel(0, -900); await p.waitForTimeout(120); samples.push(await p.evaluate(() => Math.round(window.scrollY))); }
await p.waitForTimeout(2000);
const minY = Math.min(...samples);
console.log('问题2 上滑采样:', JSON.stringify(samples));
console.log('最小 y =', minY, minY > 900 ? '（没有回到页面顶部区域 ✓）' : '（出现了跳到顶部 ✗）');
await b.close();