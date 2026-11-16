import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
for (const pg of [240, 250, 260, 270]) {
  try {
    const resp = await p.goto('https://able.heermtat.cc/archives/page/' + pg + '/', { waitUntil: 'domcontentloaded', timeout: 40000 });
    await p.waitForTimeout(1200);
    const n = await p.evaluate(() => document.querySelectorAll('.archive-title a').length);
    console.log('page', pg, '| status', resp.status(), '| cards', n);
  } catch (e) { console.log('page', pg, '| ERR', String(e.message).split('\n')[0].slice(0, 70)); }
}
await b.close();