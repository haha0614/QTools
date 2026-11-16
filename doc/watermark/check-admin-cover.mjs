import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1500, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/admin-ui/media', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(6000);
const st = await p.evaluate(() => { const img = document.querySelector('.media-cover'); if (!img) return { none: true }; const cs = getComputedStyle(img); return { fit: cs.objectFit, w: cs.width, h: cs.height, src: img.src.slice(-30) }; });
console.log('cover cell:', JSON.stringify(st));
await b.close();