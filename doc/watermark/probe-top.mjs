import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/category/mrds/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2500);
const top = await p.evaluate(() => [...document.querySelectorAll('a[href*="/archives/"]')].slice(0, 10).map((a) => { const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/); const card = a.closest('.post-card') || a.parentElement; const t = (card.innerText || '').replace(/\s+/g, ' ').trim(); return m ? m[1] + ' | ' + t.slice(0, 26) : ''; }));
console.log(JSON.stringify(top, null, 0));
await b.close();