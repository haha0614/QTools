
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BASE = 'https://able.heermtat.cc';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();

async function grab(url) {
  await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await p.waitForTimeout(2500);
  for (let i = 0; i < 6; i++) { await p.mouse.wheel(0, 3000); await p.waitForTimeout(400); }
  return p.evaluate(() => {
    const items = [];
    const seen = new Set();
    for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
      const m = (a.getAttribute('href') || '').match(/archives\/(\d+)/);
      if (!m || seen.has(m[1])) continue;
      seen.add(m[1]);
      const card = a.closest('article, .post-card, .brick, .archives, li, div') || a;
      const txt = (a.getAttribute('title') || a.textContent || card.innerText || '').replace(/\s+/g, ' ').trim();
      const dateMatch = (card.innerText || '').match(/(\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日|\d{1,2}\s*月\s*\d{1,2}\s*日)/);
      items.push({ id: m[1], title: txt.slice(0, 40), date: dateMatch ? dateMatch[1].replace(/\s+/g, ' ') : '' });
    }
    // 分页信息
    const pager = document.querySelector('.page-navigator, .pagination, nav');
    return { items, pager: pager ? pager.innerText.replace(/\n/g, ' | ').slice(0, 200) : '', url: location.href };
  });
}

const home = await grab(BASE + '/');
const cat = await grab(BASE + '/category/mrds/');
console.log('HOME 条数', home.items.length, '分页:', home.pager);
console.log('CAT  条数', cat.items.length, '分页:', cat.pager);
console.log('HOME 前 30:', JSON.stringify(home.items.slice(0, 30).map((x) => x.id + '|' + x.date)));
console.log('CAT  前 30:', JSON.stringify(cat.items.slice(0, 30).map((x) => x.id + '|' + x.date)));
const homeIds = new Set(home.items.map((x) => x.id));
const catIds = new Set(cat.items.map((x) => x.id));
const onlyHome = home.items.filter((x) => !catIds.has(x.id)).map((x) => x.id);
const onlyCat = cat.items.filter((x) => !homeIds.has(x.id)).map((x) => x.id);
console.log('仅首页有:', onlyHome.length, JSON.stringify(onlyHome.slice(0, 15)));
console.log('仅分类有:', onlyCat.length, JSON.stringify(onlyCat.slice(0, 15)));
await b.close();
