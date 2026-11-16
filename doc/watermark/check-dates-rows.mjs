import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4500);
console.log(JSON.stringify(await p.evaluate(() => [...document.querySelectorAll('.mr-bar .mr-dates, .mr-dates-all')].map((el) => ({ cls: el.className, h: Math.round(el.getBoundingClientRect().height), text: el.textContent.replace(/\s+/g, ' ').slice(0, 60) }))), null, 0));
await b.close();