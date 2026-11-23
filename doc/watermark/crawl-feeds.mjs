import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import { sql } from '../lib/db.mjs';

const exe = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find((p) => { try { return fs.existsSync(p); } catch { return false; } });
// 默认跟着脚本走（本机便携布局：<根>/data/media/web）；原来写死 D:/project/QTools-All/...，
// B 机（E 盘）上会把封面写到 D 盘旧树里，站上全是 404（2026-11-23 修）。
const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const WEBROOT = process.env.QTOOLS_WEBROOT || path.resolve(HERE, '../../../data/media/web');
// 源站域名会轮换（见 gitlab.com/mrds/mrds 的 README，或跑 sync-source-host.mjs 同步库里地址）
const BASE = process.env.BASE || 'https://able.nwfuhxhj.cc';
const PAGES = Number(process.env.PAGES || 3);
const DRY = process.env.DRY === '1';
const ONLY = process.env.ONLY || '';

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
    // 源站最近的帖子只显示「11 月 21 日」（没有年份），旧帖才带「2026 年」。
    // 原来强制要求 4 位年份 → 新帖被判成"没有日期"当广告位跳过，连封面都不会下载，
    // 结果置顶帖里有 2 条 cover 为空、前端只显示得出 1 条（2026-11-23 定位）。
    const today = (() => { const d = new Date(); return d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate(); })();
    const toNum = (s) => {
      const m = (s || '').match(/(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*日/);
      if (!m) return 0;
      let n = (m[1] ? Number(m[1]) : new Date().getFullYear()) * 10000 + Number(m[2]) * 100 + Number(m[3]);
      if (!m[1] && n > today + 7) n -= 10000;   // 跨年：1 月看到「12 月 28 日」按去年算
      return n;
    };
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
        const insSql = 'insert ignore into tool_web_post (source, source_id, title, category, cover, published_at, url, source_rank, featured, featured_kind)'
          + " values ('mrds','" + esc(c.id) + "','" + esc(c.title) + "','" + esc(c.category) + "','" + esc(coverPath) + "','"
          + esc(c.date) + "','" + esc(BASE + '/archives/' + c.id + '/') + "'," + rank + ',' + (c.featured ? 1 : 0) + ','
          + (kind ? "'" + kind + "'" : 'null') + ');';
        sql(insSql);
        // 已存在的行也要补标记（insert ignore 不会更新）
        if (c.featured) {
          const kind = feed.name === '首页' ? 'home' : 'pinned';
          // 首页精选要记住编辑排列位次，精选带按它排才和主列表区分开
          const homeRankSql = feed.name === '首页' ? ', home_rank=' + (homePos + 1) : '';
          sql("update tool_web_post set featured=1, featured_kind=case when featured_kind is null or featured_kind='' or featured_kind='" + kind + "' then '" + kind + "' else 'both' end" + homeRankSql + " where source_id='" + esc(c.id) + "';");
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
console.log(sql('select count(*) from tool_web_post;'));