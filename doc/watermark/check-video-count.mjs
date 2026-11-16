import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds/post/3293', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(6000);
const st = await p.evaluate(() => ({ videos: document.querySelectorAll('.md-video').length, tabs: document.querySelectorAll('.md-vbtn').length }));
console.log('3293（用户反馈那条）:', JSON.stringify(st));
// 仍为多视频的一条，确认没被误删
await p.goto('http://localhost/tool/mrds/post/138519', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(6000);
console.log('138519（真多视频）:', JSON.stringify(await p.evaluate(() => ({ videos: document.querySelectorAll('.md-video').length }))));
await b.close();