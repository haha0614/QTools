import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 900 } });
const p = await ctx.newPage();

// 列表页：卡片封面
await p.goto('https://able.heermtat.cc/', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
for (let i = 0; i < 3; i++) { await p.mouse.wheel(0, 1200); await p.waitForTimeout(400); }
const listCover = await p.evaluate(() => {
  const out = [];
  for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
    const card = a.closest('.post-card') || a.parentElement;
    const bg = card && card.querySelector('.blog-background');
    if (!bg) continue;
    const url = getComputedStyle(bg).backgroundImage || '';
    out.push({ href: a.getAttribute('href'), len: url.length, head: url.slice(0, 45) });
    if (out.length >= 8) break;
  }
  return out;
});
console.log('LIST COVERS:');
for (const c of listCover) console.log(' ', JSON.stringify(c));

// 帖子页：有没有同款 blog-background
await p.goto('https://able.heermtat.cc/archives/201831/', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(2500);
const postCover = await p.evaluate(() => {
  const out = [];
  for (const el of document.querySelectorAll('.blog-background, [style*=background-image]')) {
    const bg = getComputedStyle(el).backgroundImage || '';
    if (bg && bg !== 'none') out.push({ cls: String(el.className || '').slice(0, 40), len: bg.length, head: bg.slice(0, 45) });
  }
  return out.slice(0, 6);
});
console.log('POST COVER CANDIDATES:', JSON.stringify(postCover, null, 1).slice(0, 1200));
await b.close();