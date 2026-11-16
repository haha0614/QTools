import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const res = await p.evaluate(() => {
  const docW = () => document.documentElement.scrollWidth;
  const base = docW();
  const test = (sel, prop, val) => { const el = document.querySelector(sel); if (!el) return { sel, missing: true }; const old = el.style[prop]; el.style[prop] = val; const w = docW(); el.style[prop] = old; return { sel, prop, val, docW: w }; };
  return { base, tests: [
    test('.mr-pinned-grid', 'overflowX', 'auto'),
    test('.mr-pinned-grid', 'display', 'none'),
    test('.mr-featured-strip', 'overflowX', 'hidden'),
    test('.mr-grid', 'overflowX', 'hidden'),
    test('.mrds', 'overflowX', 'hidden'),
  ] };
});
console.log(JSON.stringify(res, null, 1));
await b.close();