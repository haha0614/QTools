import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 900 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
// 找一条图多的（占星猫 = id 5），用搜索定位
await p.evaluate(() => { const s = document.querySelector('.mr-search'); })
await p.goto('http://localhost/tool/mrds?focus=5', { waitUntil: 'domcontentloaded' });
await p.waitForTimeout(3000);
const ok = await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/post/5');
  const j = await r.json();
  return (j.imageCount || 0) + '/' + (j.videoCount || 0);
});
console.log('post5 图片/视频:', ok);
await b.close();