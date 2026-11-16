import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
const n = await p.evaluate(() => document.querySelectorAll('[data-post-id]').length);
console.log('卡片数:', n);
await p.evaluate(() => document.querySelector('[data-post-id]').click());
await p.waitForTimeout(7000);
const st = await p.evaluate(() => {
  const vids = [...document.querySelectorAll('.md-video')];
  const side = document.querySelector('.md-side');
  const hint = document.querySelector('.md-player-hint');
  return {
    videos: vids.length,
    dims: vids.slice(0, 2).map((v) => Math.round(v.getBoundingClientRect().width) + 'x' + Math.round(v.getBoundingClientRect().height)),
    ready: vids.slice(0, 2).map((v) => v.readyState),
    hint: hint ? hint.textContent.slice(0, 60) : null,
    sideDisplay: side ? getComputedStyle(side).display : null,
    sideH: side ? Math.round(side.getBoundingClientRect().height) : null,
    sheetScroll: (() => { const el = document.querySelector('.mr-sheet-body'); return el ? el.clientHeight + '/' + el.scrollHeight : null; })()
  };
});
console.log('移动端详情:', JSON.stringify(st));
console.log('errors:', JSON.stringify(errs.slice(0,3)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mobile-detail.png' });
await b.close();