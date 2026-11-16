import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
const st = await p.evaluate(() => ({
  featured: document.querySelectorAll('.mr-fcard').length,
  pinned: [...document.querySelectorAll('.mr-pcard')].map((e) => (e.querySelector('.mr-ptitle') || {}).textContent?.slice(0, 20)),
  adText: document.body.innerText.includes('获取最新地址')
}));
console.log('页面:', JSON.stringify(st));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/pinned-fixed.png' });
await b.close();