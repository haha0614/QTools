import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.locator('.mr-dl-toggle').click();
await p.waitForTimeout(400);
await p.locator('.mr-dl-expand').first().click();
await p.waitForTimeout(900);
const info = await p.evaluate(() => {
  const d = document.querySelector('.el-dialog');
  const body = d.querySelector('.el-dialog__body');
  const full = d.querySelector('.mr-dl-full');
  const r = (el) => el ? { h: Math.round(el.getBoundingClientRect().height), sh: el.scrollHeight, ch: el.clientHeight } : null;
  return { dialog: r(d), body: r(body), full: r(full), fullCss: full ? getComputedStyle(full).maxHeight : null, items: d.querySelectorAll('.mr-dl-fitem').length };
});
console.log(JSON.stringify(info));
await b.close();