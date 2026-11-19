import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1890, height: 922 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds?focus=153257', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(3500);
await p.evaluate(() => document.querySelector('[data-post-id="153257"]').click());
await p.waitForTimeout(3000);
const noFallback = await p.evaluate(() => {
  const box = document.querySelector('.md-player-box');
  if (!box) return null;
  const r = box.getBoundingClientRect();
  return { box: Math.round(r.width) + 'x' + Math.round(r.height), ratio: (r.width / r.height).toFixed(2), transition: getComputedStyle(box).transitionProperty };
});
console.log('无任何比例时的兜底:', JSON.stringify(noFallback));
// 过渡实测：直接把宽度从当前改成 374，采样中间值
const widths = await p.evaluate(async () => {
  const box = document.querySelector('.md-player-box');
  const out = [];
  box.style.width = '374px';
  box.style.height = '665px';
  const t0 = performance.now();
  for (let i = 0; i < 8; i += 1) {
    await new Promise((r) => setTimeout(r, 40));
    out.push(Math.round(box.getBoundingClientRect().width) + '@' + Math.round(performance.now() - t0) + 'ms');
  }
  return out;
});
console.log('过渡采样:', widths.join(' → '));
await b.close();