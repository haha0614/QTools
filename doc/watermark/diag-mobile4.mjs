import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const res = await p.evaluate(() => {
  const base = document.documentElement.scrollWidth;
  const probe = (sel) => { const el = document.querySelector(sel); if (!el) return { sel, missing: true }; const prev = el.style.display; el.style.display = 'none'; const w = document.documentElement.scrollWidth; el.style.display = prev; return { sel, without: w }; };
  const sels = ['.mr-featured', '.mr-pinned', '.mr-bar', '.mr-catbar', '.mr-tags', '.mr-grid', '.mr-tools'];
  return { base, items: sels.map(probe) };
});
console.log(JSON.stringify(res, null, 1));
await b.close();