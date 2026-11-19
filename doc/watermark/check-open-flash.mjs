import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1890, height: 922 } });
const p = await ctx.newPage();
const id = process.argv[2];
await p.goto('http://localhost/tool/mrds?focus=' + id, { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(3500);
const t0 = Date.now();
await p.evaluate((pid) => document.querySelector('[data-post-id="' + pid + '"]').click(), id);
const frames = [];
for (let i = 0; i < 16; i += 1) {
  const s = await p.evaluate(() => {
    const box = document.querySelector('.md-player-box');
    const side = document.querySelector('.md-side');
    const main = document.querySelector('.md-main');
    if (!box) return { box: null, side: side ? Math.round(side.getBoundingClientRect().width) : null, main: main ? Math.round(main.getBoundingClientRect().width) : null };
    const r = box.getBoundingClientRect();
    return { box: Math.round(r.width) + 'x' + Math.round(r.height), side: side ? Math.round(side.getBoundingClientRect().width) : null, main: main ? Math.round(main.getBoundingClientRect().width) : null };
  });
  frames.push(((Date.now() - t0) + 'ms').padEnd(8) + JSON.stringify(s));
  await p.waitForTimeout(60);
}
frames.forEach((f) => console.log(f));
await b.close();