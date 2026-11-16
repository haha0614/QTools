import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 900 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds?focus=97', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
await p.evaluate(() => document.querySelector('[data-post-id="97"]').click());
await p.waitForTimeout(9000);
const st = await p.evaluate(() => {
  const v = document.querySelector('.md-video');
  return {
    poster: v ? v.getAttribute('poster') : null,
    preload: v ? v.getAttribute('preload') : null,
    readyState: v ? v.readyState : null,
    hasCurrentFrame: v ? v.readyState >= 2 : null,
    frameShown: v ? v.dataset.frameShown : null
  };
});
console.log('首段视频:', JSON.stringify(st));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/first-frame.png' });
await b.close();