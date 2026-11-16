import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1905, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
await p.locator('.mr-chip-more').click();
await p.waitForTimeout(800);
await p.locator('.mr-drawer-search').fill('伪娘');
await p.waitForTimeout(500);
// 点「屏蔽」
await p.locator('.mr-drawer-block').first().click();
await p.waitForTimeout(2200);
const blocked = await p.evaluate(() => ({ hint: (document.querySelector('.mr-drawer-hint') || {}).textContent?.trim(), blockedCount: document.querySelectorAll('.mr-drawer-tag.blocked').length }));
console.log('after block:', JSON.stringify(blocked));
// 刷新页面确认持久化 + 该条不再出现在列表
await p.reload({ waitUntil: 'networkidle' });
await p.waitForTimeout(3500);
const persisted = await p.evaluate(async () => {
  const owner = window.localStorage.getItem('ms.owner');
  const r = await fetch('/api/tool/web/tag-blocks', { headers: { 'X-Ms-Owner': owner } });
  const j = await r.json();
  const r2 = await fetch('/api/tool/web/posts?size=60&tags=' + encodeURIComponent('伪娘'), { headers: { 'X-Ms-Owner': owner } });
  const j2 = await r2.json();
  return { blocks: j.tags, filteredTotal: j2.total };
});
console.log('after reload:', JSON.stringify(persisted));
// 取消屏蔽
await p.locator('.mr-chip-more').click();
await p.waitForTimeout(700);
await p.locator('.mr-drawer-tag.blocked .mr-drawer-block').first().click();
await p.waitForTimeout(2000);
const unblocked = await p.evaluate(async () => {
  const owner = window.localStorage.getItem('ms.owner');
  const r2 = await fetch('/api/tool/web/posts?size=60&tags=' + encodeURIComponent('伪娘'), { headers: { 'X-Ms-Owner': owner } });
  const j2 = await r2.json();
  return j2.total;
});
console.log('after unblock, filtered total:', unblocked);
await b.close();