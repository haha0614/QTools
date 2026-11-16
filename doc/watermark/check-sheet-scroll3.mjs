import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
// 用较矮的视口，保证详情内容一定超出弹窗高度
const ctx = await b.newContext({ viewport: { width: 1400, height: 620 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate(() => document.querySelector('[data-post-id]').click());
await p.waitForTimeout(4000);
const m0 = await p.evaluate(() => { const el = document.querySelector('.mr-sheet-body'); return { sh: el.scrollHeight, ch: el.clientHeight, top: Math.round(el.scrollTop) }; });
await p.mouse.move(900, 400);
await p.mouse.wheel(0, 700);
await p.waitForTimeout(600);
const m1 = await p.evaluate(() => { const el = document.querySelector('.mr-sheet-body'); return { top: Math.round(el.scrollTop), bgY: Math.round(window.scrollY) }; });
console.log('弹窗体:', JSON.stringify(m0), '→ 滚动后 scrollTop =', m1.top, '| 背景 y =', m1.bgY);
console.log(m1.top > 0 ? '弹窗可滚动 ✓' : '仍然滚不动 ✗');
await b.close();