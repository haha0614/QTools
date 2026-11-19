import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1890, height: 922 } });
const p = await ctx.newPage();
const id = process.argv[2];
await p.goto('http://localhost/tool/mrds?focus=' + id, { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate((pid) => document.querySelector('[data-post-id="' + pid + '"]').click(), id);
const t0 = Date.now();
const out = [];
for (let k = 0; k < 8; k += 1) {
  const s = await p.evaluate(() => {
    const box = document.querySelector('.md-player-box');
    const side = document.querySelector('.md-side');
    const v = box ? box.querySelector('video') : null;
    if (!box) return null;
    const r = box.getBoundingClientRect();
    return { box: Math.round(r.width) + 'x' + Math.round(r.height), ratio: (r.width / r.height).toFixed(2), side: side ? Math.round(side.getBoundingClientRect().width) : null, ready: v ? v.readyState : null };
  });
  out.push(((Date.now() - t0) + 'ms').padEnd(9) + JSON.stringify(s));
  await p.waitForTimeout(400);
}
out.forEach((l) => console.log(l));
await b.close();