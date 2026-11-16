import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.locator('.mr-dl-toggle').click();
await p.waitForTimeout(500);
const exp = await p.locator('.mr-dl-expand');
if (await exp.count()) { await exp.first().click(); } else { console.log('展开按钮不存在'); }
await p.waitForTimeout(900);
const box0 = await p.evaluate(() => { const d = document.querySelector('.el-dialog'); if (!d) return null; const r = d.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), vw: window.innerWidth, vh: window.innerHeight }; });
console.log('弹窗位置:', JSON.stringify(box0));
if (box0) {
  const centeredX = Math.abs((box0.x + box0.w / 2) - box0.vw / 2) <= 6;
  const centeredY = Math.abs((box0.y + box0.h / 2) - box0.vh / 2) <= 20;
  console.log('水平居中:', centeredX ? '✓' : '✗', '| 垂直居中:', centeredY ? '✓' : '✗');
  const hd = await p.locator('.el-dialog__header').first().boundingBox();
  await p.mouse.move(hd.x + hd.width / 2, hd.y + hd.height / 2);
  await p.mouse.down();
  await p.mouse.move(hd.x + hd.width / 2 - 160, hd.y + hd.height / 2 + 90, { steps: 12 });
  await p.mouse.up();
  await p.waitForTimeout(500);
  const box1 = await p.evaluate(() => { const d = document.querySelector('.el-dialog'); const r = d.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y) }; });
  console.log('拖动后:', JSON.stringify(box1), (box1.x !== box0.x || box1.y !== box0.y) ? '（可拖动 ✓）' : '（没动 ✗）');
}
await b.close();