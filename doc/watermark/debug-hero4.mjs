import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const card = p.locator('.mr-grid .mr-card').first();
await card.scrollIntoViewIfNeeded();
await p.waitForTimeout(500);
await card.click({ position: { x: 50, y: 30 } });
const probes = [];
for (const wait of [50, 100, 150, 200, 300]) {
  await p.waitForTimeout(wait);
  probes.push(await p.evaluate(() => { const c = document.querySelector('.mr-hero-cover'); return { n: document.querySelectorAll('.mr-hero').length, moving: !!document.querySelector('.mr-hero.moving'), t: c ? getComputedStyle(c).transform.slice(0, 30) : null }; }));
}
console.log('probes:', JSON.stringify(probes));
await p.waitForTimeout(600);
const end = await p.evaluate(() => ({ heroes: document.querySelectorAll('.mr-hero').length, stageOpacity: (() => { const s = document.querySelector('.md-hero-target'); return s ? getComputedStyle(s).opacity : null; })() }));
console.log('end:', JSON.stringify(end));
await b.close();