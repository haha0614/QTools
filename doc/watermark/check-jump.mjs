import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(2800);
// 用一个深层条目模拟"上次看到很后面"：直接写入 localStorage 再刷新
const deep = await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=12&size=24');
  const j = await r.json();
  const row = (j.rows || [])[0];
  window.localStorage.setItem('ms.mrds.lastSeen', JSON.stringify({ id: row.id, title: row.title, at: Date.now() - 9 * 24 * 3600 * 1000 }));
  return { id: row.id, title: (row.title || '').slice(0, 16) };
});
console.log('simulated last seen (12 页, 9 天前):', JSON.stringify(deep));
await p.reload({ waitUntil: 'networkidle' });
await p.waitForTimeout(3000);
const bar = await p.evaluate(() => (document.querySelector('.mr-resume-text') || {}).textContent?.trim());
console.log('resume bar:', bar);
const t0 = Date.now();
await p.locator('.mr-resume button').first().click();
await p.waitForTimeout(2500);
const res = await p.evaluate((id) => ({ hit: document.querySelectorAll('.mr-hit').length, targetVisible: !!document.querySelector('[data-post-id="' + id + '"]'), cards: document.querySelectorAll('[data-post-id]').length, bar: !!document.querySelector('.mr-resume') }), deep.id);
console.log('after locate (12 页深):', JSON.stringify(res), 'elapsed', Date.now() - t0, 'ms');
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-jump.png' });
await b.close();