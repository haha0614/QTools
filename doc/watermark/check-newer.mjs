import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(2800);
const deep = await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=12&size=24');
  const j = await r.json();
  const row = (j.rows || [])[0];
  window.localStorage.setItem('ms.mrds.lastSeen', JSON.stringify({ id: row.id, title: row.title, at: Date.now() - 20 * 86400000 }));
  return { id: row.id, title: (row.title || '').slice(0, 14) };
});
console.log('simulate last seen (page12, 20 天前):', JSON.stringify(deep));
await p.reload({ waitUntil: 'networkidle' });
await p.waitForTimeout(3000);
await p.locator('.mr-resume button').first().click();
await p.waitForTimeout(2600);
const jumped = await p.evaluate((id) => ({ hit: document.querySelectorAll('.mr-hit').length, bar: !!document.querySelector('.mr-newer'), text: (document.querySelector('.mr-newer-text') || {}).textContent?.trim(), cards: document.querySelectorAll('[data-post-id]').length, firstId: (document.querySelector('[data-post-id]') || {}).getAttribute?.('data-post-id') }), deep.id);
console.log('after jump:', JSON.stringify(jumped));
// 往上加载更新的内容
await p.locator('.mr-newer button', { hasText: '加载更新的' }).click();
await p.waitForTimeout(2500);
const up = await p.evaluate(() => ({ bar: !!document.querySelector('.mr-newer'), text: (document.querySelector('.mr-newer-text') || {}).textContent?.trim(), cards: document.querySelectorAll('[data-post-id]').length }));
console.log('after load newer:', JSON.stringify(up));
// 回到最新
await p.locator('.mr-newer button', { hasText: '回到最新' }).click();
await p.waitForTimeout(2500);
const latest = await p.evaluate(() => ({ bar: !!document.querySelector('.mr-newer'), cards: document.querySelectorAll('[data-post-id]').length, scrollY: Math.round(window.scrollY) }));
console.log('back to latest:', JSON.stringify(latest));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-newer.png' });
await b.close();