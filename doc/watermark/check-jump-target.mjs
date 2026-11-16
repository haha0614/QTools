import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
// 直接用页面内调用：打开月历 → 翻到 2026-01 → 点 12
await p.locator('.mr-cal-btn').first().click();
await p.waitForTimeout(1200);
for (let i = 0; i < 10; i += 1) {
  const title = await p.evaluate(() => (document.querySelector('.mc-title') || {}).textContent || '');
  if (title.includes('2026 年 1 月')) break;
  await p.evaluate(() => document.querySelectorAll('.mc-nav')[0].click());
  await p.waitForTimeout(500);
}
console.log('当前月:', await p.evaluate(() => (document.querySelector('.mc-title') || {}).textContent));
await p.evaluate(() => { const c=[...document.querySelectorAll('.mc-cell')].find((x)=>{const d=x.querySelector('.mc-day');return d&&d.textContent.trim()==='12';}); if(c) c.click(); });
await p.waitForTimeout(4500);
const st = await p.evaluate(() => {
  const cards = [...document.querySelectorAll('[data-post-id]')];
  const target = cards.find((c) => (c.textContent || '').includes('2026-01-12'));
  const r = target ? target.getBoundingClientRect() : null;
  return {
    jump: (document.querySelector('.mr-datejump') || {}).textContent?.replace(/\s+/g, ' ').trim().slice(0, 24),
    found: !!target,
    top: r ? Math.round(r.top) : null,
    viewport: window.innerHeight,
    firstCardDate: (cards[0] && cards[0].querySelector('.mr-meta')) ? cards[0].querySelector('.mr-meta').textContent.trim() : null
  };
});
console.log('结果:', JSON.stringify(st));
console.log(st.found && st.top !== null && st.top >= 0 && st.top < st.viewport ? '目标日期在视口内 ✓' : '目标日期不在视口内 ✗');
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/jump-2026-01-12.png' });
await b.close();