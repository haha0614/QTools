import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const info = await p.evaluate(() => ({ reduce: window.matchMedia('(prefers-reduced-motion: reduce)').matches, card: !!document.querySelector('.mr-grid .mr-card'), coverImg: !!document.querySelector('.mr-grid .mr-card .mr-cover img'), coverDiv: !!document.querySelector('.mr-grid .mr-card .mr-cover'), title: !!document.querySelector('.mr-grid .mr-card .mr-title') }));
console.log('env:', JSON.stringify(info));
await b.close();