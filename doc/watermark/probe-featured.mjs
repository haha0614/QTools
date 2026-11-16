import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
async function ids(url) {
  await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await p.waitForTimeout(2000);
  for (let i = 0; i < 3; i++) { await p.mouse.wheel(0, 2000); await p.waitForTimeout(300); }
  return p.evaluate(() => { const o = []; for (const a of document.querySelectorAll('a[href*="/archives/"]')) { const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/); if (m && !o.includes(m[1])) o.push(m[1]); } return o; });
}
const home = await ids('https://able.heermtat.cc/');
const cat = await ids('https://able.heermtat.cc/category/mrds/');
console.log('home', home.length, home.slice(0, 12).join(','));
console.log('cat ', cat.length, cat.slice(0, 8).join(','));
console.log('pinned still in cat?', ['201218','201196','201227'].map((x) => x + ':' + (cat.indexOf(x) >= 0 ? 'Y' : 'n')).join(' '));
console.log('home∩cat', home.filter((x) => cat.includes(x)).length);
await b.close();