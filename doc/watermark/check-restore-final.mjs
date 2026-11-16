import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 100)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
for (let i = 0; i < 5; i++) { await p.mouse.wheel(0, 1200); await p.waitForTimeout(400); }
await p.waitForTimeout(1000);
const before = await p.evaluate(() => ({ y: Math.round(window.scrollY), cards: document.querySelectorAll('[data-post-id]').length }));
await p.evaluate(() => { const cards=[...document.querySelectorAll('[data-post-id]')]; const vis=cards.find((e)=>{const r=e.getBoundingClientRect();return r.top>100&&r.bottom<window.innerHeight;})||cards[0]; vis.click(); });
await p.waitForTimeout(1500);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(2500);
// 返回列表：连续采样 (可见性, y)
await p.locator('.mp-back').click();
const samples = [];
for (let i = 0; i < 14; i++) {
  samples.push(await p.evaluate(() => { const el = document.querySelector('.mrds'); const vis = el ? getComputedStyle(el).visibility : 'none'; return vis + ':' + Math.round(window.scrollY); }));
  await p.waitForTimeout(100);
}
console.log('离开前:', JSON.stringify(before));
console.log('返回采样:', JSON.stringify(samples));
const visible = samples.filter((s) => s.startsWith('visible'));
const firstVisibleY = visible.length ? Number(visible[0].split(':')[1]) : null;
console.log('首次可见时 y =', firstVisibleY, '| 最终 y =', Number(samples[samples.length-1].split(':')[1]));
console.log('结论:', firstVisibleY === before.y ? '一次到位 ✓' : '仍有跳动 ✗', '| errors:', JSON.stringify(errs.slice(0,2)));
await b.close();