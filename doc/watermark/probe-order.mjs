import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
async function ids(url) {
  await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await p.waitForTimeout(2500);
  for (let i = 0; i < 5; i++) { await p.mouse.wheel(0, 1300); await p.waitForTimeout(300); }
  return p.evaluate(() => [...document.querySelectorAll('a[href*="/archives/"]')].map((a) => (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/)).filter(Boolean).map((m) => m[1]).filter((v, i, arr) => arr.indexOf(v) === i).slice(0, 12));
}
console.log('homepage :', (await ids('https://able.heermtat.cc/')).join(','));
console.log('category :', (await ids('https://able.heermtat.cc/category/mrds/')).join(','));
await b.close();