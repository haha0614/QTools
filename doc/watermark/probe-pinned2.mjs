import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/category/mrds/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2500);
for (let i = 0; i < 3; i++) { await p.mouse.wheel(0, 1300); await p.waitForTimeout(250); }
const out = await p.evaluate(() => {
  const res = [];
  for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
    const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/);
    if (!m) continue;
    const card = a.closest('.post-card') || a.parentElement;
    const text = (card.innerText || '').replace(/\s+/g, ' ').trim();
    const dateM = text.match(/\d{4} 年 \d{1,2} 月 \d{1,2} 日/);
    res.push({ id: m[1], date: dateM ? dateM[0] : '', text: text.slice(0, 40) });
    if (res.length >= 12) break;
  }
  return res;
});
for (let i = 0; i < out.length; i++) console.log((i + 1) + '.', out[i].id, '|', out[i].date, '|', out[i].text.slice(0, 30));
await b.close();