import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1905, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const sidebar = await p.evaluate(() => [...document.querySelectorAll('.qt-rail__group a, .qt-rail__group .qt-rail__link')].map((a) => ({ text: (a.textContent || '').trim().slice(0, 10), disabled: a.className.indexOf('disabled') >= 0 })).filter((x) => x.text));
console.log('sidebar:', JSON.stringify(sidebar));
const width = await p.evaluate(() => { const el = document.querySelector('.mrds'); const r = el.getBoundingClientRect(); return { toolWidth: Math.round(r.width), viewport: window.innerWidth, tags: document.querySelectorAll('.mr-tags .mr-chip').length }; });
console.log('layout:', JSON.stringify(width));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/audit-list.png' });
// 详情页
await p.goto('http://localhost/tool/mrds/post/29', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
const detail = await p.evaluate(() => ({ blocks: document.querySelectorAll('.md-p').length, imgs: document.querySelectorAll('.md-gcell').length, video: !!document.querySelector('video'), title: (document.querySelector('.md-title') || {}).textContent?.slice(0, 20) }));
console.log('detail:', JSON.stringify(detail));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/audit-detail.png' });
await b.close();