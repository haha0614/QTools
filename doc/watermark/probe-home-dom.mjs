
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(2500);
const out = await p.evaluate(() => {
  const rows = [];
  const seen = new Set();
  for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
    const m = (a.getAttribute('href') || '').match(/archives\/(\d+)/);
    if (!m || seen.has(m[1])) continue;
    seen.add(m[1]);
    // 往上找 3 层祖先的 class，判断属于哪个区块
    const chain = [];
    let el = a;
    for (let i = 0; i < 4 && el; i++) {
      chain.push((el.className && typeof el.className === 'string') ? el.className.split(' ').slice(0, 2).join('.') : el.tagName.toLowerCase());
      el = el.parentElement;
    }
    const card = a.closest('article, .post-card, .brick, li, div');
    rows.push({ id: m[1], chain: chain.join(' < '), cardCls: card ? String(card.className).split(' ').slice(0, 3).join('.') : '' });
  }
  return rows.slice(0, 32);
});
for (const r of out) console.log(r.id, '|', r.cardCls, '|', r.chain);
await b.close();
