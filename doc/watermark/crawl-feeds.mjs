import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const exe = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find((p) => { try { return fs.existsSync(p); } catch { return false; } });
const MYSQL = process.env.QTOOLS_MYSQL || 'E:/111/project/QTools/tools/mysql/bin/mysql.exe';
const WEBROOT = process.env.QTOOLS_WEBROOT || 'E:/111/project/QTools/data/media/web';
const BASE = process.env.BASE || 'https://able.heermtat.cc';
const PAGES = Number(process.env.PAGES || 3);
const DRY = process.env.DRY === '1';
const ONLY = process.env.ONLY || '';

const db = (sql) => execFileSync(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','-N','-B','-e', sql, 'qtools'], { encoding: 'utf8', maxBuffer: 1 << 28 });
const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const b = await chromium.launch({ executablePath: exe, headless: process.env.HEADLESS === '1', args: ['--mute-audio','--window-position=-32000,-32000','--window-size=1300,950'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();

await p.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(2500);
// 分类 feed 从站点导航里取，别写死（源站会加减分类）
const feeds = await p.evaluate((base) => {
  const out = [{ name: '首页', url: base + '/' }];
  for (const a of document.querySelectorAll('a[href*="/category/"]')) {
    const href = a.getAttribute('href') || '';
    const name = (a.textContent || '').trim();
    if (!name || name.length > 8) continue;
    const url = href.startsWith('http') ? href : base + href;
    if (!out.some((f) => f.url === url)) out.push({ name, url });
  }
  return out;
}, BASE);
console.log('feeds', feeds.length, feeds.map((f) => f.name).join(' | '));

const seen = new Set();
let rank = 0, inserted = 0, covers = 0;
const list = ONLY ? feeds.filter((f) => f.name.indexOf(ONLY) >= 0) : feeds;

for (const feed of list) {
  let prev = 0;
  for (let page = 1; page <= PAGES; page++) {
    const url = page === 1 ? feed.url : feed.url.replace(/\/$/, '') + '/page/' + page + '/';
    try { await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 }); } catch (e) { console.log('  ', feed.name, 'p' + page, 'fail'); break; }
    await p.waitForTimeout(1800);
    // 列表是懒渲染：固定滚几次会只拿到前几条，置顶判定必须看完整页（否则 maxDate 算错）
    let prevCount = 0;
    for (let i = 0; i < 25; i++) {
      await p.mouse.wheel(0, 2200);
      await p.waitForTimeout(320);
      const now = await p.evaluate(() => document.querySelectorAll('a[href*="/archives/"]').length);
      if (now === prevCount && i > 6) break;
      prevCount = now;
    }
    const cards = await p.evaluate(() => {
      const out = [];
      for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
        const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/);
        if (!m) continue;
        const card = a.closest('.post-card') || a.parentElement;
        if (!card) continue;
        const titleEl = card.querySelector('.post-title, h2, h3, .card-title');
        const text = (card.innerText || '').replace(/\s+/g, ' ').trim();
        const dateM = text.match(/\d{4} 年 \d{1,2} 月 \d{1,2} 日/);
        const catEl = card.querySelector('a[href*="/category/"]');
        const bgEl = card.querySelector('.blog-background');
        let cover = '';
        if (bgEl) {
          const bgv = getComputedStyle(bgEl).backgroundImage || '';
          const k = bgv.indexOf('base64,');
          if (k >= 0) cover = bgv.slice(k + 7, bgv.length - 2);
        }
        out.push({
          id: m[1],
          title: ((titleEl && titleEl.textContent) || text).trim().slice(0, 300),
          date: dateM ? dateM[0] : '',
          category: ((catEl && catEl.textContent) || '').trim(),
          cover,
        });
      }
      return out;
    });
    // 分类页顶部会插「精选/置顶」若干条：它们日期比后面更旧，且列表中段还会插无标题无日期的广告位。
    // 判定：先算出本页最新日期，再把「出现在第一条最新日期之前的更旧条目」标记为 featured。
    const toNum = (s) => { const m = (s || '').match(/(\d{4}) 年 (\d{1,2}) 月 (\d{1,2}) 日/); return m ? Number(m[1]) * 10000 + Number(m[2]) * 100 + Number(m[3]) : 0; };
    const dated = cards.map((c) => toNum(c.date)).filter((n) => n > 0);
    const maxDate = dated.length ? Math.max.apply(null, dated) : 0;
    // 置顶只在「开头连续几条」且日期明显旧时成立，最多 3 条（实测 mrds 分类正好 3 条）；
    // 放宽会把正常的更新序条目误判成置顶（不同分类的最新日期不一样）。
    let pinnedOpen = true;
    let pinnedCount = 0;
    for (const c of cards) {
      const n = toNum(c.date);
      if (n === 0) { c.skip = true; continue; }        // 广告位：没有日期，不是帖子
      const isPinned = pinnedOpen && n < maxDate && pinnedCount < 3;
      if (!isPinned && n >= maxDate) pinnedOpen = false;
      if (isPinned) pinnedCount += 1;
      c.featured = feed.name === '首页' || isPinned ? 1 : 0;
    }
    let fresh = 0;
    let homePos = 0;   // 首页 feed 里的位次（编辑排序）
    for (const c of cards) {
      if (c.skip) continue;
      if (seen.has(c.id)) continue;
      seen.add(c.id); rank += 1; fresh += 1;
      if (feed.name === '首页') homePos += 1;
      const dir = path.join(WEBROOT, c.id);
      let coverPath = '';
      if (c.cover) {
        const buf = Buffer.from(c.cover, 'base64');
        if (buf.length > 5000) {
          if (!DRY) { fs.mkdirSync(dir, { recursive: true }); fs.writeFileSync(path.join(dir, 'cover.jpg'), buf); }
          coverPath = '/web/' + c.id + '/cover.jpg'; covers += 1;
        }
      }
      if (!DRY) {
        const kind = c.featured ? (feed.name === '首页' ? 'home' : 'pinned') : null;
        const sql = 'insert ignore into tool_web_post (source, source_id, title, category, cover, published_at, url, source_rank, featured, featured_kind)'
          + " values ('mrds','" + esc(c.id) + "','" + esc(c.title) + "','" + esc(c.category) + "','" + esc(coverPath) + "','"
          + esc(c.date) + "','" + esc(BASE + '/archives/' + c.id + '/') + "'," + rank + ',' + (c.featured ? 1 : 0) + ','
          + (kind ? "'" + kind + "'" : 'null') + ');';
        db(sql);
        // 已存在的行也要补标记（insert ignore 不会更新）
        if (c.featured) {
          const kind = feed.name === '首页' ? 'home' : 'pinned';
          // 首页精选要记住编辑排列位次，精选带按它排才和主列表区分开
          const homeRankSql = feed.name === '首页' ? ', home_rank=' + (homePos + 1) : '';
          db("update tool_web_post set featured=1, featured_kind=case when featured_kind is null or featured_kind='' or featured_kind='" + kind + "' then '" + kind + "' else 'both' end" + homeRankSql + " where source_id='" + esc(c.id) + "';");
        }
      }
      inserted += 1;
      if (DRY && fresh <= 6) console.log('    sample', JSON.stringify({ id: c.id, title: c.title.slice(0, 22), date: c.date, category: c.category, cover: c.cover.length }));
    }
    console.log('  ', feed.name, 'p' + page, 'cards', cards.length, 'new', fresh);
    if (fresh === 0 || fresh === prev) break;
    prev = fresh;
    await sleep(150);
  }
}
await b.close();
console.log(DRY ? 'DRY ' : '', 'seen', seen.size, 'inserted', inserted, 'covers', covers);
console.log(db('select count(*) from tool_web_post;'));