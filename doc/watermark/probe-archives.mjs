import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/archives.html', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2000);
let prev = 0;
for (let i = 0; i < 20; i++) {
  await p.mouse.wheel(0, 2500); await p.waitForTimeout(350);
  const n = await p.evaluate(() => document.querySelectorAll('a[href*="/archives/"]').length);
  if (n === prev && i > 5) break;
  prev = n;
}
const info = await p.evaluate(() => {
  const anchors = [...document.querySelectorAll('a[href*="/archives/"]')];
  const ids = anchors.map((a) => (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/)).filter(Boolean).map((m) => m[1]).filter((v, i, arr) => arr.indexOf(v) === i);
  const pager = [...document.querySelectorAll('a')].filter((a) => /page|下一页|next/i.test(a.getAttribute('href') || '') || /下一页|next/i.test(a.textContent || '')).map((a) => (a.getAttribute('href') || '') + ' :: ' + (a.textContent || '').trim().slice(0, 10)).slice(0, 6);
  const nums = ids.map(Number);
  return { cards: ids.length, first: ids.slice(0, 3), last: ids.slice(-3), sortedDesc: nums.every((v, i) => i === 0 || nums[i - 1] >= v), pager };
});
console.log(JSON.stringify(info));
await b.close();