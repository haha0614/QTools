import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
for (let i = 0; i < 6; i++) { await p.mouse.wheel(0, 1500); await p.waitForTimeout(500); }
await p.waitForTimeout(1200);
const before = await p.evaluate(() => { const c = document.querySelector('[data-post-id]'); const cards = [...document.querySelectorAll('[data-post-id]')]; const vis = cards.find((e) => e.getBoundingClientRect().bottom > 0); return { visId: vis && vis.getAttribute('data-post-id'), visTop: vis ? Math.round(vis.getBoundingClientRect().top) : null, y: Math.round(window.scrollY) }; });
await p.locator('[data-post-id]').nth(2).click();
await p.waitForTimeout(1800);
await p.keyboard.press('Escape');
await p.locator('.mr-sheet-head button', { hasText: '关闭' }).click().catch(() => {});
await p.waitForTimeout(900);
const after = await p.evaluate((id) => { const el = document.querySelector('[data-post-id="' + id + '"]'); return { visTop: el ? Math.round(el.getBoundingClientRect().top) : null, y: Math.round(window.scrollY) }; }, before.visId);
console.log('打开前:', JSON.stringify(before));
console.log('关闭后:', JSON.stringify(after), Math.abs((after.visTop ?? 0) - (before.visTop ?? 0)) <= 3 ? '（同一张卡还在原位 ✓）' : '（位置变了 ✗）');
await b.close();