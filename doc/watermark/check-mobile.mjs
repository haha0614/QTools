import { chromium, devices } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1' });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
const list = await p.evaluate(() => {
  const g = document.querySelector('.mr-grid');
  return { cols: g ? getComputedStyle(g).gridTemplateColumns : null, cards: document.querySelectorAll('.mr-card').length, docW: document.documentElement.scrollWidth, winW: window.innerWidth, sidebarVisible: !!document.querySelector('.qt-rail') && getComputedStyle(document.querySelector('.qt-rail')).display !== 'none' };
});
console.log('mobile list:', JSON.stringify(list));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mobile-list.png' });
// 打开详情弹窗
await p.locator('.mr-card').first().click();
await p.waitForTimeout(1500);
const sheet = await p.evaluate(() => { const s = document.querySelector('.mr-sheet'); const r = s.getBoundingClientRect(); const v = document.querySelector('video'); return { w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top), radius: getComputedStyle(s).borderRadius, videoW: v ? Math.round(v.getBoundingClientRect().width) : null, overflowX: document.documentElement.scrollWidth > window.innerWidth }; });
console.log('mobile sheet:', JSON.stringify(sheet));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mobile-detail.png' });
await b.close();