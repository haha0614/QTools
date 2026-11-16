import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
for (const pg of [2, 3, 5, 20]) {
  const url = 'https://able.heermtat.cc/archives/page/' + pg + '/';
  try {
    const resp = await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 40000 });
    await p.waitForTimeout(1800);
    const ids = await p.evaluate(() => [...document.querySelectorAll('a[href*="/archives/"]')].map((a) => (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/)).filter(Boolean).map((m) => m[1]).filter((v, i, arr) => arr.indexOf(v) === i));
    console.log('page', pg, '|', resp.status(), '| cards', ids.length, '| first', ids.slice(0, 2).join(','), '| last', ids.slice(-2).join(','));
  } catch (e) { console.log('page', pg, 'ERR', String(e.message).slice(0, 60)); }
}
await b.close();