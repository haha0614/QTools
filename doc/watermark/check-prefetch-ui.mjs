import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1905, height: 929 } });
const p = await ctx.newPage();
const posts = [];
p.on('request', (r) => { const u = r.url(); if (u.indexOf('/tool/web/prefetch') >= 0) posts.push('PREFETCH'); if (u.indexOf('/tool/web/refresh') >= 0) posts.push('REFRESH'); });
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const first = await p.evaluate(() => ({ todo: document.querySelectorAll('.mr-todo').length, cards: document.querySelectorAll('.mr-card').length }));
console.log('first page:', JSON.stringify(first), 'calls:', JSON.stringify(posts));
// 翻到更老的页（那里全是索引条目）
for (let i = 0; i < 12; i++) { const btn = p.locator('.mr-btn', { hasText: '加载更多' }); if (await btn.count()) { await btn.first().click().catch(() => {}); await p.waitForTimeout(900); } }
const later = await p.evaluate(() => ({ todo: document.querySelectorAll('.mr-todo').length, cards: document.querySelectorAll('.mr-card').length }));
console.log('after load-more:', JSON.stringify(later), 'calls:', JSON.stringify(posts));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-prefetch.png' });
await b.close();