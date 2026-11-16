
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BASE = 'https://able.heermtat.cc';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();

// 1) 202053 的真实分类（面包屑 / post 里的 category 链接）
await p.goto(BASE + '/archives/202053/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(1500);
const info = await p.evaluate(() => {
  const crumb = [...document.querySelectorAll('.breadcrumb a, .post-crumb a, nav a')].map((a) => a.textContent.trim());
  const inCat = [...document.querySelectorAll('.post-content a[href*="/category/"]')].map((a) => a.textContent.trim());
  return { crumb: crumb.slice(0, 8), inContent: inCat.slice(0, 5) };
});
console.log('202053:', JSON.stringify(info));

// 2) 分类页的第 2 页到底怎么翻
await p.goto(BASE + '/category/mrds/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2000);
const pag = await p.evaluate(() => {
  const out = { ajax: [...document.querySelectorAll('[class*=more], [class*=load], button')].map((e) => (e.textContent || '').trim()).filter(Boolean).slice(0, 8) };
  out.nextLinks = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href')).filter((h) => h && (h.includes('page') || h.includes('more'))).slice(0, 8);
  out.pagerHtml = (document.querySelector('.page-navigator, .pagination, #pagination') || {}).outerHTML ? 'has' : 'none';
  return out;
});
console.log('分类页翻页线索:', JSON.stringify(pag));

// 3) 直接试 ?page=2
await p.goto(BASE + '/category/mrds/?page=2', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2000);
const p2 = await p.evaluate(() => [...document.querySelectorAll('a[href*="/archives/"]')].slice(0, 6).map((a) => (a.getAttribute('href') || '').match(/archives\/(\d+)/)?.[1]));
console.log('分类 ?page=2 前 6 个 id:', JSON.stringify(p2));
await b.close();
