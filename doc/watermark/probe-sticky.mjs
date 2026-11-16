
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/category/mrds/', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(2500);
const out = await p.evaluate(() => {
  const rows = [];
  const seen = new Set();
  let idx = 0;
  for (const art of document.querySelectorAll('article, .brick')) {
    const a = art.querySelector('a[href*="/archives/"]');
    if (!a) continue;
    const m = (a.getAttribute('href') || '').match(/archives\/(\d+)/);
    if (!m || seen.has(m[1])) continue;
    seen.add(m[1]);
    if (idx++ > 7) break;
    rows.push({
      id: m[1],
      cls: String(art.className),
      badge: [...art.querySelectorAll('span, em, i, b')].map((e) => (e.textContent || '').trim()).filter((t) => t && t.length <= 6).slice(0, 4),
      parentCls: art.parentElement ? String(art.parentElement.className).split(' ').slice(0, 3).join('.') : ''
    });
  }
  return rows;
});
for (const r of out) console.log(r.id, '| article:', r.cls, '| parent:', r.parentCls, '| 角标:', JSON.stringify(r.badge));
await b.close();
