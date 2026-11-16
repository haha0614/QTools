import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
for (let i = 0; i < 6; i++) { await p.mouse.wheel(0, 1200); await p.waitForTimeout(350); }
await p.waitForTimeout(800);
const before = await p.evaluate(() => ({ y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length }));
await p.evaluate(() => { document.querySelector('[data-post-id]').click(); });
await p.waitForTimeout(1200);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(1800);
const t0 = Date.now();
await p.locator('.mp-back').click();
const timeline = [];
for (let i = 0; i < 20; i++) {
  timeline.push(await p.evaluate(() => { const skel = !!document.querySelector('.mr-skel-wrap'); const el = document.querySelector('.mrds'); const op = el ? getComputedStyle(el).opacity : '?'; return (skel ? 'S' : '-') + '/' + op + '/' + Math.round(window.scrollY); }));
  await p.waitForTimeout(80);
}
const settled = timeline.findIndex((t) => t.startsWith('-') && Number(t.split('/')[1]) > 0.9);
console.log('离开前:', JSON.stringify(before));
console.log('返回时间线:', JSON.stringify(timeline));
console.log('骨架屏出现帧数:', timeline.filter((t) => t.startsWith('S')).length, '| 到内容可见耗时约', settled >= 0 ? settled * 80 : '>1600', 'ms');
await p.waitForTimeout(600);
const after = await p.evaluate(() => ({ y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length }));
console.log('落定:', JSON.stringify(after), after.y === before.y ? '（位置正确 ✓）' : '（位置不对 ✗）');
await b.close();