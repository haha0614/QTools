import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
// 找一条有详情且内容多的：直接点第 3 张卡
await p.evaluate(() => document.querySelectorAll('[data-post-id]')[2].click());
await p.waitForTimeout(4000);
const info = await p.evaluate(() => {
  const rows = [];
  const walk = (el, depth) => {
    if (!el || depth > 4) return;
    const cs = getComputedStyle(el);
    rows.push({ d: depth, cls: (el.className || '').toString().slice(0, 28), ovY: cs.overflowY, sh: el.scrollHeight, ch: el.clientHeight });
    for (const c of el.children) walk(c, depth + 1);
  };
  const mask = document.querySelector('.mr-mask');
  if (mask) walk(mask, 0);
  return rows.filter((r) => r.sh > r.ch + 4 || /sheet|md-/.test(r.cls)).slice(0, 14);
});
console.log(JSON.stringify(info, null, 0));
await p.mouse.move(1000, 500);
await p.mouse.wheel(0, 900);
await p.waitForTimeout(600);
const tops = await p.evaluate(() => [...document.querySelectorAll('.mr-mask, .mr-mask *')].filter((el) => el.scrollTop > 0).map((el) => (el.className || '').toString().slice(0, 24) + '=' + Math.round(el.scrollTop)));
console.log('有滚动位移的元素:', JSON.stringify(tops), '| window.scrollY =', await p.evaluate(() => Math.round(window.scrollY)));
await b.close();