import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 360, height: 800 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
await p.locator('.mr-tags-mobile .mr-chip').first().click();
await p.waitForTimeout(1200);
const st = await p.evaluate(() => {
  const d = document.querySelector('.el-drawer');
  const body = document.querySelector('.el-drawer__body');
  const r = d ? d.getBoundingClientRect() : null;
  return {
    drawerW: r ? Math.round(r.width) : null,
    drawerLeft: r ? Math.round(r.left) : null,
    docW: document.documentElement.scrollWidth,
    cw: document.documentElement.clientWidth,
    bodyScrollW: body ? body.scrollWidth : null,
    bodyClientW: body ? body.clientWidth : null,
    tags: document.querySelectorAll('.mr-drawer-tag').length
  };
});
console.log('drawer:', JSON.stringify(st));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/m-drawer.png' });
await b.close();