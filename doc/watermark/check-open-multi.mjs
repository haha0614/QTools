import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1890, height: 922 } });
const p = await ctx.newPage();
const ids = process.argv.slice(2);
for (const id of ids) {
  await p.goto('http://localhost/tool/mrds?focus=' + id, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await p.waitForTimeout(3000);
  await p.evaluate((pid) => document.querySelector('[data-post-id="' + pid + '"]').click(), id);
  const frames = [];
  for (let i = 0; i < 5; i += 1) {
    const s = await p.evaluate(() => {
      const boxes = [...document.querySelectorAll('.md-player-box')];
      return boxes.length ? boxes.slice(0, 2).map((x) => Math.round(x.getBoundingClientRect().width) + 'x' + Math.round(x.getBoundingClientRect().height)).join(' | ') + ' 共' + boxes.length + '段' : 'none';
    });
    frames.push(s);
    await p.waitForTimeout(70);
  }
  console.log('帖子', id, '→', frames.join(' → '));
  await p.keyboard.press('Escape');
  await p.waitForTimeout(600);
}
await b.close();