import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
async function cards(url, label) {
  await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await p.waitForTimeout(2500);
  for (let i = 0; i < 4; i++) { await p.mouse.wheel(0, 1300); await p.waitForTimeout(250); }
  const out = await p.evaluate(() => {
    const res = [];
    const anchors = [...document.querySelectorAll('a[href*="/archives/"]')];
    for (const a of anchors) {
      const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/);
      if (!m) continue;
      const card = a.closest('.post-card') || a.parentElement;
      const chain = [];
      let n = card;
      for (let d = 0; d < 4 && n && n !== document.body; d++) { chain.push(n.tagName.toLowerCase() + (n.className ? '.' + String(n.className).replace(/\s+/g, '.') : '') + (n.id ? '#' + n.id : '')); n = n.parentElement; }
      const badges = [...card.querySelectorAll('*')].filter((e) => /置顶|推荐|精选|荐|hot|top/i.test(e.className || '') || /^(置顶|推荐|精选|荐|HOT)$/i.test((e.textContent || '').trim())).map((e) => e.tagName + '.' + e.className + ':' + (e.textContent || '').trim().slice(0, 8)).slice(0, 3);
      res.push({ id: m[1], cardCls: String(card.className || ''), chain: chain.join(' < '), badges });
      if (res.length >= 8) break;
    }
    return res;
  });
  console.log('=== ' + label);
  for (const c of out) console.log(' ', c.id, '|', c.cardCls, '|', c.chain.slice(0, 110), '| badges', JSON.stringify(c.badges));
  return out.map((c) => c.id);
}
const home = await cards('https://able.heermtat.cc/', '首页');
const cat = await cards('https://able.heermtat.cc/category/mrds/', '分类 每日大赛');
console.log('home in cat?', home.map((id) => id + ':' + (cat.indexOf(id) >= 0 ? 'Y' : 'n')).join(' '));
await b.close();