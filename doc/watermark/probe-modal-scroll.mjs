import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
for (let i = 0; i < 6; i++) { await p.mouse.wheel(0, 1500); await p.waitForTimeout(400); }
await p.waitForTimeout(1000);
console.log('打开前 y =', await p.evaluate(() => Math.round(window.scrollY)));
await p.locator('[data-post-id]').nth(2).click();
const samples = [];
for (let i = 0; i < 8; i++) {
  samples.push(await p.evaluate(() => Math.round(window.scrollY)));
  await p.waitForTimeout(250);
}
console.log('打开后（不滚轮）采样:', JSON.stringify(samples));
const info = await p.evaluate(() => ({ bodyPos: document.body.style.position || '-', htmlOverflow: document.documentElement.style.overflow || '-', docH: document.documentElement.scrollHeight, innerH: window.innerHeight }));
console.log('状态:', JSON.stringify(info));
await b.close();