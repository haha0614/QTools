import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds?focus=97', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
const found = await p.evaluate(() => !!document.querySelector('[data-post-id="97"]'));
if (found) { await p.evaluate(() => document.querySelector('[data-post-id="97"]').click()); await p.waitForTimeout(6000); }
const st = await p.evaluate(() => {
  const boxes = [...document.querySelectorAll('.md-player-box')].slice(0, 3).map((el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return { h: Math.round(r.height), w: Math.round(r.width), ar: cs.aspectRatio, maxH: cs.maxHeight }; });
  const player = document.querySelector('.md-player');
  const side = document.querySelector('.md-side');
  const main = document.querySelector('.md-main');
  return { boxes, playerH: player ? Math.round(player.getBoundingClientRect().height) : null, sideH: side ? Math.round(side.getBoundingClientRect().height) : null, mainTop: main ? Math.round(main.getBoundingClientRect().top) : null, layoutDir: (() => { const l = document.querySelector('.md-layout'); return l ? getComputedStyle(l).flexDirection : null; })() };
});
console.log('移动端:', JSON.stringify(st));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mobile-detail2.png' });
await b.close();