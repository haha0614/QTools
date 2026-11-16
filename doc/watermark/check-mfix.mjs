import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 360, height: 800 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const m = await p.evaluate(() => {
  const chip = document.querySelector('.mr-bar .mr-chip');
  const st = chip ? getComputedStyle(chip) : null;
  return {
    docW: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth,
    chipLines: chip ? Math.round(chip.getBoundingClientRect().height) : null,
    chipWhiteSpace: st ? st.whiteSpace : null,
    tagsHidden: (() => { const t = document.querySelector('.mr-tags'); return t ? getComputedStyle(t).display : 'none-el'; })(),
    tagsMobileBtn: (() => { const t = document.querySelector('.mr-tags-mobile'); return t ? getComputedStyle(t).display + ':' + t.textContent.trim().slice(0, 12) : null; })(),
    sheetClose: !!document.querySelector('.mr-sheet-close')
  };
});
console.log('mobile fixes:', JSON.stringify(m));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/m-fix1.png' });
await p.evaluate(() => { const el = document.querySelector('.mr-bar'); el.scrollIntoView({ block: 'center' }); });
await p.waitForTimeout(600);
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/m-fix2.png' });
await b.close();