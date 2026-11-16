import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 360, height: 800 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
await p.locator('.mr-filter-toggle').click();
await p.waitForTimeout(400);
const inside = await p.evaluate(() => {
  const panel = document.querySelector('.mr-more-filters');
  return { tagsInPanel: !!panel.querySelector('.mr-tags-mobile'), viewsInPanel: !!panel.querySelector('.mr-views'), outsideTags: document.querySelectorAll('.mrds > .mr-tags-mobile').length };
});
console.log('panel contents:', JSON.stringify(inside));
// 切到列表视图检查排版
await p.locator('.mr-view', { hasText: '列表' }).click();
await p.waitForTimeout(1800);
const row = await p.evaluate(() => { const r = document.querySelector('.mr-row'); if (!r) return null; const cs = getComputedStyle(r); const t = r.querySelector('.mr-title'); return { display: cs.display, cols: cs.gridTemplateColumns, titleW: t ? Math.round(t.getBoundingClientRect().width) : null, rowH: Math.round(r.getBoundingClientRect().height) }; });
console.log('list row:', JSON.stringify(row));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/m-listview.png' });
await b.close();