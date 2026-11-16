import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const res = await p.evaluate(() => {
  const docW = () => document.documentElement.scrollWidth;
  const base = docW();
  const test = (sel, prop, val) => { const el = document.querySelector(sel); if (!el) return { sel, missing: true }; const old = el.style[prop]; el.style[prop] = val; const w = docW(); el.style[prop] = old; return { sel, w }; };
  const cw = document.documentElement.clientWidth;
  const cands = ['.mr-bar', '.mr-catbar', '.mr-tools', '.mr-toolbar', '.mr-featured', '.mr-featured-strip', '.mr-pinned-grid', '.mr-grid', '.mrds', '.mr-topbar'];
  return { base, cw, tests: cands.map((s) => test(s, 'overflowX', 'hidden')) };
});
console.log(JSON.stringify(res));
await b.close();