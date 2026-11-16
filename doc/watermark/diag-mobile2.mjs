import { chromium, devices } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
for (const cfg of [{ name: 'plain390', opts: { viewport: { width: 390, height: 844 } } }, { name: 'iphone13', opts: { ...devices['iPhone 13'] } }, { name: 'pixel', opts: { ...devices['Pixel 5'] } }]) {
  const ctx = await b.newContext(cfg.opts);
  const p = await ctx.newPage();
  await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
  await p.waitForTimeout(4500);
  const m = await p.evaluate(() => ({ innerW: window.innerWidth, clientW: document.documentElement.clientWidth, dpr: window.devicePixelRatio, zoomVisual: Math.round(window.visualViewport ? window.visualViewport.scale * 100 : 0) / 100, cards: document.querySelectorAll('.mr-card').length, docW: document.documentElement.scrollWidth }));
  console.log(cfg.name, JSON.stringify(m));
  if (cfg.name === 'iphone13') await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mobile-iphone.png' });
  await ctx.close();
}
await b.close();