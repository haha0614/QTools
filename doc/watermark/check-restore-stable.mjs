import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
await p.evaluate(() => window.scrollTo(0, 3000));
await p.waitForTimeout(1500);
const before = await p.evaluate(() => Math.round(window.scrollY));
await p.locator('[data-post-id]').nth(2).click();
await p.waitForTimeout(1800);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(2500);
// 返回列表后连续采样滚动位置，看是否有二次跳动
await p.locator('.mp-back').click();
const samples = [];
for (let i = 0; i < 10; i++) {
  samples.push(await p.evaluate(() => Math.round(window.scrollY)));
  await p.waitForTimeout(120);
}
console.log('离开前 y =', before);
console.log('返回后采样:', JSON.stringify(samples));
const finalY = samples[samples.length - 1];
const stable = samples.slice(2).every((v) => Math.abs(v - finalY) <= 2);
console.log('是否一次到位:', stable ? '是 ✓' : '否 ✗', '| 最终 y =', finalY, '| 目标 =', before);
await b.close();