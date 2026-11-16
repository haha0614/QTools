import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 900 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
await p.goto('http://localhost/tool/mrds?focus=97', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(6000);
const ok = await p.evaluate(() => !!document.querySelector('[data-post-id="97"]'));
console.log('列表里找到 97:', ok);
if (ok) {
  await p.evaluate(() => document.querySelector('[data-post-id="97"]').click());
  await p.waitForTimeout(7000);
  const st = await p.evaluate(() => {
    const side = document.querySelector('.md-side');
    const vids = [...document.querySelectorAll('.md-video')];
    return {
      videos: vids.length,
      sideCh: side ? Math.round(side.clientHeight) : null,
      sideSh: side ? Math.round(side.scrollHeight) : null,
      sideOv: side ? getComputedStyle(side).overflowY : null,
      durations: vids.slice(0, 3).map((v) => Math.round(v.duration || 0)),
      errText: (document.querySelector('.md-player-hint') || {}).textContent || null
    };
  });
  console.log('详情:', JSON.stringify(st));
  const box = await p.locator('.md-side').boundingBox();
  await p.mouse.move(box.x + box.width / 2, box.y + 300);
  await p.mouse.wheel(0, 700);
  await p.waitForTimeout(600);
  const after = await p.evaluate(() => Math.round(document.querySelector('.md-side').scrollTop));
  console.log('左栏滚动后 scrollTop =', after, after > 0 ? '（可滚动 ✓）' : '（滚不动 ✗）');
  await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/multi7.png' });
}
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await b.close();