import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds/post/5', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(4000);
// 图集顺序：检查前几个格子是否按 DOM 顺序从左到右
const order = await p.evaluate(() => [...document.querySelectorAll('.md-gcell')].slice(0, 6).map((e) => { const r = e.getBoundingClientRect(); return { src: e.querySelector('img').src.slice(-8), x: Math.round(r.x), y: Math.round(r.y) }; }));
console.log('前 6 格:', JSON.stringify(order));
await p.locator('.md-gcell').first().scrollIntoViewIfNeeded();
await p.waitForTimeout(800);
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/gallery-order.png' });
// 灯箱：鼠标放在左上角附近滚轮缩放，检查位移是否朝向该点
await p.locator('.md-gcell').first().click();
await p.waitForTimeout(700);
const box = await p.locator('.md-lb-img').boundingBox();
const mx = box.x + box.width * 0.25;
const my = box.y + box.height * 0.25;
await p.mouse.move(mx, my);
await p.mouse.wheel(0, -300);
await p.waitForTimeout(200);
await p.mouse.wheel(0, -300);
await p.waitForTimeout(200);
const tr = await p.evaluate(() => (document.querySelector('.md-lb-img') || {}).style?.transform);
console.log('缩放后 transform:', tr, '| 鼠标点:', Math.round(mx), Math.round(my));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/lb-zoom-anchor.png' });
await b.close();