import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/archives/200564/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2500);
const raw = await p.content();
const out = [];
let from = 0;
while (out.length < 6) {
  const k = raw.indexOf('m3u8', from);
  if (k < 0) break;
  from = k + 4;
  out.push(raw.slice(Math.max(0, k - 260), k + 40).replace(/\s+/g, ' '));
}
out.forEach((s, i) => console.log('--- ' + i + ':', s.slice(-260)));
await b.close();