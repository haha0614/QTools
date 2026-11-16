import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
async function probe(url) {
  try {
    const resp = await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 40000 });
    await p.waitForTimeout(2000);
    const ids = await p.evaluate(() => [...document.querySelectorAll('a[href*="/archives/"]')].map((a) => (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/)).filter(Boolean).map((m) => m[1]).filter((v, i, arr) => arr.indexOf(v) === i));
    console.log(resp.status(), url.replace('https://able.heermtat.cc', ''), '| cards', ids.length, '| first', ids.slice(0, 3).join(','));
  } catch (e) { console.log('ERR', url.slice(-40), String(e.message).slice(0, 60)); }
}
await probe('https://able.heermtat.cc/category/mrds/');
await probe('https://able.heermtat.cc/category/mrds/?page=2');
await probe('https://able.heermtat.cc/category/mrds/page/2/');
await probe('https://able.heermtat.cc/archives.html');
await probe('https://able.heermtat.cc/archives.html?page=2');
await b.close();