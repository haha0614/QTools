import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/archives/202063/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(3000);
const info = await p.evaluate(() => {
  const raw = document.documentElement.innerHTML;
  const q = String.fromCharCode(34);
  const blocks = [];
  let from = 0;
  while (true) {
    const k = raw.indexOf('m3u8', from);
    if (k < 0) break;
    from = k + 4;
    const h264 = raw.lastIndexOf(q + 'video' + q + ':', k);
    const h265 = raw.lastIndexOf(q + 'video_h265' + q + ':', k);
    blocks.push({ kind: h265 > h264 ? 'h265' : 'h264' });
  }
  return { videos: document.querySelectorAll('video').length, blocks, iframes: document.querySelectorAll('iframe').length };
});
console.log('源站页面:', JSON.stringify(info));
await b.close();