import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 360, height: 800 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
const m = await p.evaluate(() => {
  const fcards = [...document.querySelectorAll('.mr-fcard')].slice(0, 4).map((e) => Math.round(e.getBoundingClientRect().width));
  const grid = document.querySelector('.mr-grid');
  const catbar = document.querySelector('.mr-catbar') || document.querySelector('.mr-bar');
  return {
    fcards,
    gridCols: grid ? getComputedStyle(grid).gridTemplateColumns : null,
    gridClass: grid ? grid.className : null,
    catCount: document.querySelectorAll('.mr-bar .mr-chip').length,
    catBarH: catbar ? Math.round(catbar.getBoundingClientRect().height) : null,
    catWrap: catbar ? getComputedStyle(catbar).flexWrap : null,
    tagsHidden: (() => { const t = document.querySelector('.mr-tags'); return t ? getComputedStyle(t).display : 'none'; })(),
    mediaRules: [...document.styleSheets].flatMap((s) => { try { return [...s.cssRules]; } catch (e) { return []; } }).filter((r) => r.conditionText && r.conditionText.includes('768')).length
  };
});
console.log(JSON.stringify(m));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/m-now.png' });
await b.close();