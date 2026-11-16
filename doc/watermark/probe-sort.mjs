
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BASE = 'https://able.heermtat.cc';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();

// 发布时间对比
for (const id of ['202053', '202076']) {
  await p.goto(BASE + '/archives/' + id + '/', { waitUntil: 'domcontentloaded', timeout: 45000 });
  await p.waitForTimeout(1200);
  const meta = await p.evaluate(() => {
    const t = document.querySelector('meta[property="article:published_time"]');
    const mod = document.querySelector('meta[property="article:modified_time"]');
    const time = document.querySelector('time');
    return { pub: t ? t.content : null, mod: mod ? mod.content : null, time: time ? time.textContent.trim() : null };
  });
  console.log(id, JSON.stringify(meta));
}

// 分类页翻页控件
await p.goto(BASE + '/category/mrds/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2000);
const pager = await p.evaluate(() => {
  const el = document.querySelector('.page-navigator, .pagination, #pagination');
  if (!el) return { none: true };
  return { html: el.outerHTML.replace(/\s+/g, ' ').slice(0, 500) };
});
console.log('CAT pager:', JSON.stringify(pager));

// 分类页 1 里有没有 202053（滚动加载更多）
await p.mouse.wheel(0, 6000);
await p.waitForTimeout(1500);
await p.mouse.wheel(0, 6000);
await p.waitForTimeout(1500);
const has = await p.evaluate(() => document.body.innerHTML.includes('/archives/202053/'));
console.log('分类页 1 是否含 202053:', has);
await b.close();
