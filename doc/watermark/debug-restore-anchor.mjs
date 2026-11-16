import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
for (let i = 0; i < 5; i++) { await p.mouse.wheel(0, 900); await p.waitForTimeout(500); }
await p.waitForTimeout(1200);
const before = await p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].find((e)=>e.getBoundingClientRect().bottom>120); return { id: c.getAttribute('data-post-id'), top: Math.round(c.getBoundingClientRect().top), y: Math.round(window.scrollY), n: document.querySelectorAll('[data-post-id]').length }; });
console.log('离开前:', JSON.stringify(before));
// 点视口中部的一张卡（模拟用户点红框那种）
await p.evaluate(() => { const c=[...document.querySelectorAll('[data-post-id]')].filter((e)=>{const r=e.getBoundingClientRect();return r.top>350&&r.bottom<800;}); (c[0]||document.querySelector('[data-post-id]')).click(); });
await p.waitForTimeout(1400);
await p.locator('.mr-sheet-head button', { hasText: '展开整页' }).click();
await p.waitForTimeout(300);
const snap = await p.evaluate(() => JSON.parse(sessionStorage.getItem('ms.mrds.listState') || 'null'));
console.log('快照:', JSON.stringify(snap));
await p.waitForTimeout(1500);
await p.locator('.mp-back').click();
await p.waitForTimeout(3000);
const after = await p.evaluate((id) => { const e=document.querySelector('[data-post-id="'+id+'"]'); return { top: e?Math.round(e.getBoundingClientRect().top):null, y: Math.round(window.scrollY), n: document.querySelectorAll('[data-post-id]').length }; }, before.id);
console.log('返回后 同一条卡:', JSON.stringify(after), '| 期望 top≈', before.top);
await b.close();