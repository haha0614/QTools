import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/archives/page/1/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2500);
const out = await p.evaluate(() => {
  const res = [];
  for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
    const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/);
    if (!m) continue;
    const chain = [];
    let n = a;
    for (let d = 0; d < 5 && n && n !== document.body; d++) { chain.push(n.tagName.toLowerCase() + (n.className ? '.' + String(n.className).replace(/\s+/g, '.') : '')); n = n.parentElement; }
    res.push({ id: m[1], href: a.getAttribute('href'), cls: String(a.className || ''), text: (a.innerText || '').replace(/\s+/g, ' ').slice(0, 40), chain: chain.join(' < ') });
    if (res.length >= 4) break;
  }
  return res;
});
console.log(JSON.stringify(out, null, 1).slice(0, 2200));
await b.close();