import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1890, height: 922 } });
const p = await ctx.newPage();
const id = process.argv[2];
await p.goto('http://localhost/tool/mrds?focus=' + id, { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate((pid) => document.querySelector('[data-post-id="' + pid + '"]').click(), id);
const samples = [];
for (const ms of [300, 700, 1200, 2500, 5000, 9000]) {
  await p.waitForTimeout(ms - (samples.length ? [300,700,1200,2500,5000,9000][samples.length-1] : 0));
  const s = await p.evaluate(() => {
    const box = document.querySelector('.md-player-box');
    const wrap = document.querySelector('.md-side');
    if (!box) return null;
    const r = box.getBoundingClientRect();
    const w = wrap ? wrap.getBoundingClientRect() : null;
    const v = box.querySelector('video');
    return { box: Math.round(r.width) + 'x' + Math.round(r.height), ratio: (r.width / r.height).toFixed(2), side: w ? Math.round(w.width) : null, style: box.getAttribute('style'), ready: v ? v.readyState : null };
  });
  samples.push([ms + 'ms', s]);
}
for (const [t, s] of samples) console.log(t, JSON.stringify(s));
await b.close();