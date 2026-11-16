import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
// 造一个深层位置：写入锚点（第 12 页），刷新后点「回去」
const deep = await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=12&size=24');
  const j = await r.json();
  const row = (j.rows || [])[0];
  localStorage.setItem('ms.mrds.scrollAnchor', JSON.stringify({ id: row.id, title: row.title, page: 12, top: 120, at: Date.now() }));
  return row.id;
});
await p.reload({ waitUntil: 'domcontentloaded' });
await p.waitForTimeout(4500);
const label = await p.evaluate(() => (document.querySelector('.mr-resume-text') || {}).textContent?.trim());
console.log('提示文案:', label);
await p.locator('.mr-resume button', { hasText: '回去' }).click();
await p.waitForTimeout(2500);
const pos = await p.evaluate((id) => { const el = document.querySelector('[data-post-id="' + id + '"]'); return el ? Math.round(el.getBoundingClientRect().top) : null; }, deep);
console.log('目标卡在视口的位置 top =', pos, '（保存时是 120）');
// 加载更新的：检查跳动
const before = await p.evaluate(() => Math.round(document.querySelector('[data-post-id]').getBoundingClientRect().top));
await p.locator('.mr-newer button', { hasText: '加载更新的' }).click();
await p.waitForTimeout(2500);
const after = await p.evaluate(() => Math.round(document.querySelector('[data-post-id]').getBoundingClientRect().top));
console.log('加载更新的：首卡 top', before, '→', after, Math.abs(before - after) <= 3 ? '（没跳 ✓）' : '（跳了 ✗）');
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/newer-stable.png' });
await b.close();