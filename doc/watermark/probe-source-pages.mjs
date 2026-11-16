
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BASE = 'https://able.heermtat.cc';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();

async function probe(url) {
  await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await p.waitForTimeout(2000);
  return p.evaluate(() => {
    const pages = [...document.querySelectorAll('a[href*="/page/"]')].map((a) => a.getAttribute('href'));
    const nums = pages.map((h) => (h.match(/page\/(\d+)/) || [])[1]).filter(Boolean).map(Number);
    const total = document.querySelector('.page-navigator')?.innerText?.match(/(\d+)/g);
    return { maxPage: nums.length ? Math.max(...nums) : null, sample: pages.slice(0, 6), navText: total ? total.slice(-5).join(',') : '' };
  });
}
console.log('HOME page2:', JSON.stringify(await probe(BASE + '/')));
console.log('CAT  page2:', JSON.stringify(await probe(BASE + '/category/mrds/')));
console.log('HOME p2 url:', JSON.stringify(await probe(BASE + '/page/2/')));
console.log('CAT  p2 url:', JSON.stringify(await probe(BASE + '/category/mrds/page/2/')));
// 202053 属于哪个分类
await p.goto(BASE + '/archives/202053/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(1500);
const cats = await p.evaluate(() => [...document.querySelectorAll('a[href*="/category/"]')].map((a) => a.textContent.trim()).filter(Boolean).slice(0, 8));
console.log('202053 所属分类/标签:', JSON.stringify(cats));
await b.close();
