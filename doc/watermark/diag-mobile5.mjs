import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const res = await p.evaluate(() => {
  const cw = document.documentElement.clientWidth;
  const chain = [];
  let el = document.querySelector('.mrds');
  while (el && el !== document.documentElement) {
    const st = getComputedStyle(el);
    chain.push({ cls: el.tagName.toLowerCase() + '.' + String(el.className).split(' ').slice(0, 2).join('.'), w: Math.round(el.getBoundingClientRect().width), minW: st.minWidth, ox: st.overflowX, pos: st.position, ml: st.marginLeft });
    el = el.parentElement;
  }
  return { cw, docW: document.documentElement.scrollWidth, chain };
});
console.log(JSON.stringify(res, null, 1).slice(0, 1600));
await b.close();