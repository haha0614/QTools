import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds/post/138519', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(6000);
const st = await p.evaluate(() => {
  const vids = [...document.querySelectorAll('.md-video')];
  return { videos: vids.length, tabs: document.querySelectorAll('.md-vbtn').length, tops: vids.map((v) => Math.round(v.getBoundingClientRect().top)), sizes: vids.map((v) => Math.round(v.getBoundingClientRect().width) + 'x' + Math.round(v.getBoundingClientRect().height)), titles: document.querySelectorAll('.md-title').length };
});
console.log('多视频帖:', JSON.stringify(st), '| errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/multi-video2.png' });
await b.close();