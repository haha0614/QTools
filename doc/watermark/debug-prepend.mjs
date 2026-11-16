import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
p.on('console', (m) => { if (m.text().startsWith('[prepend]')) console.log(m.text()); });
await p.addInitScript(() => { window.__mrDebugPrepend = true; });
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
const watch = await p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].find((e)=>e.getBoundingClientRect().bottom>120); return c.getAttribute('data-post-id'); });
for (let i = 0; i < 4; i++) {
  await p.mouse.wheel(0, -700);
  await p.waitForTimeout(400);
  console.log('sample', JSON.stringify(await p.evaluate((id) => { const el=document.querySelector('[data-post-id="'+id+'"]'); return { y: Math.round(window.scrollY), t: el?Math.round(el.getBoundingClientRect().top):null, n: document.querySelectorAll('[data-post-id]').length }; }, watch)));
}
await b.close();