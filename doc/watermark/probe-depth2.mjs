import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
for (const pg of [300, 500, 800, 1200]) {
  try {
    const resp = await p.goto('https://able.heermtat.cc/archives/page/' + pg + '/', { waitUntil: 'domcontentloaded', timeout: 40000 });
    await p.waitForTimeout(1200);
    const info = await p.evaluate(() => { const ids = []; for (const a of document.querySelectorAll('.archive-title a, a[href*="/archives/"]')) { const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/); if (m && !ids.includes(m[1])) ids.push(m[1]); } return { n: ids.length, first: ids[0] || '', last: ids[ids.length - 1] || '' }; });
    console.log('page', pg, '| status', resp.status(), '| cards', info.n, '| first', info.first, '| last', info.last);
  } catch (e) { console.log('page', pg, 'ERR', String(e.message).slice(0, 50)); }
}
await b.close();