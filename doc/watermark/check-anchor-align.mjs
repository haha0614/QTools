import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
// 找一条第 N 页的帖子做锚点，模拟“刷新后点回去”
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
const trace = [];
for (let i = 0; i < 16; i++) {
  trace.push(await p.evaluate((x) => { const el=document.querySelector('[data-post-id="'+x+'"]'); return el?Math.round(el.getBoundingClientRect().top):null; }, id));
  await p.waitForTimeout(120);
}
await p.waitForTimeout(800);
const final = await p.evaluate((x) => { const el=document.querySelector('[data-post-id="'+x+'"]'); return el?Math.round(el.getBoundingClientRect().top):null; }, id);
console.log('锚点卡 top 轨迹:', JSON.stringify(trace));
console.log('最终 top =', final, '| 目标 140 →', Math.abs((final ?? -999) - 140) <= 4 ? '对齐 ✓' : '仍然偏 ✗');
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/anchor-aligned.png' });
await b.close();