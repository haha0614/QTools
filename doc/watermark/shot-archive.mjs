import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1905, height: 929 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const info = await p.evaluate(() => {
  const cards = [...document.querySelectorAll('.mr-card')];
  const imgs = [...document.querySelectorAll('.mr-card img')];
  return { cards: cards.length, imgs: imgs.length, ok: imgs.filter((i) => i.naturalWidth > 0).length,
    firstTitles: cards.slice(0, 4).map((c) => (c.innerText || '').split('\n').filter(Boolean).slice(0, 2).join(' | ')) };
});
console.log(JSON.stringify(info));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-archive-list.png' });
await b.close();