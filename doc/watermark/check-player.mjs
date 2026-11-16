import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
// 从列表里找一条多视频的（有「视频 2」标签的），直接打开详情弹窗
await p.evaluate(() => document.querySelector('[data-post-id]').click());
await p.waitForTimeout(4000);
const st = await p.evaluate(() => ({ vids: document.querySelectorAll('.md-video').length, player: !!document.querySelector('.md-player'), novideo: !!document.querySelector('.md-novideo'), title: (document.querySelector('.md-title') || {}).textContent?.slice(0, 14) }));
console.log('详情弹窗:', JSON.stringify(st), '| errors:', JSON.stringify(errs.slice(0,2)));
await b.close();