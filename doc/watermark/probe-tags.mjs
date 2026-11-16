import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
await p.goto('https://able.heermtat.cc/archives/202021/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2500);
const info = await p.evaluate(() => {
  const out = [];
  for (const el of document.querySelectorAll('a[href*="/tag/"], a[href*="/tags/"], .post-tags a, .tags a, a[rel=tag]')) {
    out.push({ text: (el.textContent || '').trim().slice(0, 20), href: (el.getAttribute('href') || '').slice(-30), cls: String(el.parentElement && el.parentElement.className || '').slice(0, 30) });
  }
  return { count: out.length, sample: out.slice(0, 15), tagBlocks: document.querySelectorAll('.post-tags, .tags, .tag-list').length };
});
console.log(JSON.stringify(info, null, 1).slice(0, 1600));
await b.close();