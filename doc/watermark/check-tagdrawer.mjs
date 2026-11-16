import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1905, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3200);
const cloud = await p.evaluate(() => ({ hot: [...document.querySelectorAll('.mr-tags .mr-chip:not(.mr-chip-more):not(.mr-chip-clear)')].map((c) => c.textContent.trim()), more: (document.querySelector('.mr-chip-more') || {}).textContent?.trim() }));
console.log('tag cloud:', JSON.stringify(cloud));
// 打开抽屉
await p.locator('.mr-chip-more').click();
await p.waitForTimeout(900);
const drawer = await p.evaluate(() => ({ open: !!document.querySelector('.el-drawer'), tags: document.querySelectorAll('.mr-drawer-tag').length, hint: (document.querySelector('.mr-drawer-hint') || {}).textContent?.trim() }));
console.log('drawer:', JSON.stringify(drawer));
// 选两个标签
const picks = p.locator('.mr-drawer-tag .mr-drawer-pick');
await picks.nth(0).click();
await p.waitForTimeout(1200);
await picks.nth(1).click();
await p.waitForTimeout(1500);
const afterPick = await p.evaluate(() => ({ hint: (document.querySelector('.mr-drawer-hint') || {}).textContent?.trim(), cards: document.querySelectorAll('.mr-card').length, clear: (document.querySelector('.mr-chip-clear') || {}).textContent?.trim() }));
console.log('after pick two:', JSON.stringify(afterPick));
// 屏蔽第一个标签
await p.locator('.mr-drawer-block').first().click();
await p.waitForTimeout(1800);
const afterBlock = await p.evaluate(() => ({ hint: (document.querySelector('.mr-drawer-hint') || {}).textContent?.trim(), blockedSection: !!document.querySelector('.mr-drawer-tag.blocked') }));
console.log('after block:', JSON.stringify(afterBlock));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-tagdrawer.png' });
await b.close();