import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 900 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
await p.goto('http://localhost/tool/mrds/post/138519', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(6000);
const st = await p.evaluate(() => {
  const side = document.querySelector('.md-side');
  const main = document.querySelector('.md-main');
  const body = document.querySelector('.mr-sheet-body');
  const info = (el) => el ? { ch: el.clientHeight, sh: el.scrollHeight, scrollable: el.scrollHeight > el.clientHeight + 4, ov: getComputedStyle(el).overflowY } : null;
  return { side: info(side), main: info(main), body: info(body), bodyOv: body ? getComputedStyle(body).overflowY : null };
});
console.log(JSON.stringify(st));
// 分别滚动两栏，确认互不影响
const sideBox = await p.locator('.md-side').boundingBox();
if (sideBox) { await p.mouse.move(sideBox.x + sideBox.width / 2, sideBox.y + 200); await p.mouse.wheel(0, 400); await p.waitForTimeout(500); }
const after = await p.evaluate(() => ({ side: Math.round(document.querySelector('.md-side').scrollTop), main: Math.round(document.querySelector('.md-main').scrollTop), page: Math.round(window.scrollY) }));
console.log('滚左栏后:', JSON.stringify(after));
const mainBox = await p.locator('.md-main').boundingBox();
if (mainBox) { await p.mouse.move(mainBox.x + mainBox.width / 2, mainBox.y + 200); await p.mouse.wheel(0, 400); await p.waitForTimeout(500); }
const after2 = await p.evaluate(() => ({ side: Math.round(document.querySelector('.md-side').scrollTop), main: Math.round(document.querySelector('.md-main').scrollTop) }));
console.log('再滚右栏后:', JSON.stringify(after2));
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/two-scroll.png' });
await b.close();