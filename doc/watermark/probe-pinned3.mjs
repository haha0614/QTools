import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/category/mrds/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2500);
// 滚到底，直到卡片数稳定（列表是懒渲染，滚不够会只拿到前几条）
let count = 0;
for (let i = 0; i < 25; i++) {
  await p.mouse.wheel(0, 2200);
  await p.waitForTimeout(350);
  const now = await p.evaluate(() => document.querySelectorAll('a[href*="/archives/"]').length);
  if (now === count && i > 6) break;
  count = now;
}
const out = await p.evaluate(() => {
  const res = [];
  for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
    const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/);
    if (!m) continue;
    const card = a.closest('.post-card') || a.parentElement;
    const text = (card.innerText || '').replace(/\s+/g, ' ').trim();
    const dm = text.match(/\d{4} 年 \d{1,2} 月 \d{1,2} 日/);
    res.push({ id: m[1], d: dm ? dm[0] : '' });
  }
  return res;
});
const num = (s) => { const m = (s || '').match(/(\d{4}) 年 (\d{1,2}) 月 (\d{1,2}) 日/); return m ? Number(m[1]) * 10000 + Number(m[2]) * 100 + Number(m[3]) : 0; };
const dated = out.filter((x) => num(x.d) > 0);
const maxD = Math.max.apply(null, dated.map((x) => num(x.d)));
const firstMax = out.findIndex((x) => num(x.d) === maxD);
console.log('cards', out.length, 'dated', dated.length, 'maxDate', maxD, 'firstMaxIdx', firstMax);
console.log('pinnedPrefix:', out.slice(0, Math.max(0, firstMax)).filter((x) => num(x.d) < maxD).map((x) => x.id + '@' + x.d).join(' | '));
console.log('head:', out.slice(0, 10).map((x) => x.id + '@' + (x.d || 'nodate')).join(' '));
await b.close();