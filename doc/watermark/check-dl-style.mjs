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
  const cs = getComputedStyle(d);
  const body = d.querySelector('.el-dialog__body');
  const bcs = getComputedStyle(body);
  return {
    dlgClass: d.className,
    dlgStyle: { display: cs.display, flexDirection: cs.flexDirection, height: cs.height, maxHeight: cs.maxHeight, marginTop: cs.marginTop },
    bodyStyle: { flex: bcs.flex, height: bcs.height, overflow: bcs.overflowY },
    overlay: (() => { const o = document.querySelector('.el-overlay-dialog'); const s = o && getComputedStyle(o); return s ? { display: s.display, alignItems: s.alignItems } : null; })()
  };
});
console.log(JSON.stringify(info, null, 0));
await b.close();