import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1500, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(2500);
// 用页面自己的身份，收藏 6 条、浏览 5 条
const ids = await p.evaluate(async () => {
  const owner = window.localStorage.getItem('ms.owner');
  const r = await fetch('/api/tool/web/posts?page=1&size=12');
  const j = await r.json();
  const rows = (j.rows || []).map((x) => x.id);
  for (const id of rows.slice(0, 6)) await fetch('/api/tool/web/fav?postId=' + id + '&on=1', { method: 'POST', headers: { 'X-Ms-Owner': owner } });
  for (const id of rows.slice(6, 11)) await fetch('/api/tool/web/seen?postId=' + id, { method: 'POST', headers: { 'X-Ms-Owner': owner } });
  return rows.length;
});
console.log('seeded rows:', ids);
await p.goto('http://localhost/me', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
const layout = await p.evaluate(() => {
  const groups = [...document.querySelectorAll('.me-g')].map((g) => {
    const ul = g.querySelector('.mm-list');
    const rect = ul.getBoundingClientRect();
    const first = ul.querySelector('.mm-item');
    return { label: (g.querySelector('.me-g__h') || {}).textContent.trim().slice(0, 20), items: ul.querySelectorAll('.mm-item').length, listH: Math.round(rect.height), rowH: first ? Math.round(first.getBoundingClientRect().height) : 0, cls: ul.className, btn: (g.querySelector('.me-g__more') || {}).textContent };
  });
  return { pageH: Math.round(document.body.scrollHeight), groups };
});
console.log(JSON.stringify(layout, null, 1).slice(0, 1200));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/me-collapsed.png' });
// 展开第一个分组
await p.locator('.me-g__more').first().click();
await p.waitForTimeout(800);
const after = await p.evaluate(() => { const g = document.querySelector('.me-g'); const ul = g.querySelector('.mm-list'); return { cls: ul.className, listH: Math.round(ul.getBoundingClientRect().height), btn: (g.querySelector('.me-g__more') || {}).textContent, pageH: Math.round(document.body.scrollHeight) }; });
console.log('after expand:', JSON.stringify(after));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/me-expanded.png' });
await b.close();