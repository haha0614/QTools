
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

const C = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find(p=>{try{return fs.existsSync(p)}catch{return false}});
const BASE = process.env.BASE || 'https://able.akmevtunk.cc';
const PAGES = Number(process.env.PAGES || 3);
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const MYSQL = 'E:/111/project/QTools/tools/mysql/bin/mysql.exe';

const b = await chromium.launch({ executablePath: C, headless: false, args: ['--mute-audio','--window-position=-32000,-32000','--window-size=1000,800'] });
const p = await (await b.newContext({ userAgent: UA, viewport: { width: 1280, height: 900 }, locale: 'zh-CN' })).newPage();

const ids = [];
for (let n = 1; n <= PAGES; n++) {
  const url = n <= 1 ? BASE + '/' : BASE + '/page/' + n + '/';
  await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await p.waitForTimeout(1400);
  const pageIds = await p.evaluate(() => {
    const s = new Set();
    document.querySelectorAll('a[href*="/archives/"]').forEach(a => { const m = /\/archives\/(\d+)\//.exec(a.getAttribute('href')||''); if (m) s.add(m[1]); });
    return Array.from(s);
  });
  console.log('[list] page', n, pageIds.length);
  ids.push(...pageIds);
  await sleep(700);
}
const uniq = Array.from(new Set(ids));
console.log('ids total', uniq.length);

const rows = [];
for (const id of uniq) {
  try {
    await p.goto(BASE + '/archives/' + id + '/', { waitUntil: 'domcontentloaded', timeout: 45000 });
    await p.waitForTimeout(650);
    const d = await p.evaluate(() => {
      const txt = el => (el ? (el.textContent||'').trim() : '');
      const h1 = document.querySelector('h1');
      const crumbs = Array.from(document.querySelectorAll('.breadcrumb a, .crumbs a, a[href*="/category/"]')).map(txt).filter(Boolean);
      const tags = Array.from(new Set(Array.from(document.querySelectorAll('a[href*="/tag/"]')).map(txt).filter(Boolean))).slice(0, 10);
      const time = document.querySelector('time');
      const html = document.documentElement.outerHTML;
      const banner = (html.match(/loadBannerDirect\(\s*'([^']+)'/) || [])[1] || '';
      const contentImgs = Array.from(document.querySelectorAll('article img, .post-content img, .content img'))
        .map(i => i.currentSrc || i.src || '').filter(s => s && /^https?:/.test(s) && !/\/usr\//.test(s));
      return {
        title: txt(h1) || document.title.replace(/\s*-\s*每日大赛\s*$/, ''),
        category: crumbs.length ? crumbs[crumbs.length - 1] : '',
        tags, date: (time && (time.getAttribute('datetime') || txt(time))) || '',
        cover: (/^https?:/.test(banner) ? banner : (contentImgs[0] || '')),
        hasVideo: !!document.querySelector('video, .dplayer, #player, [id*="player" i]')
      };
    });
    if (d.title) rows.push({ id, ...d });
  } catch (e) { console.log('  fail', id, e.message); }
  await sleep(220);
}
await b.close();

const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");
const values = rows.map(r => '(' +
  "'mrds', '" + esc(r.id) + "', '" + esc(r.title).slice(0, 900) + "', '" + esc(r.category) + "', '" +
  esc(r.tags.join(',')) + "', '" + esc(r.cover) + "', '" + esc(r.date) + "', '" + esc(BASE + '/archives/' + r.id + '/') + "', " + (r.hasVideo ? 1 : 0) + ')');
const sql = 'insert into tool_web_post (source, source_id, title, category, tags, cover, published_at, url, has_video) values\n' +
  values.join(',\n') + '\non duplicate key update title=values(title), category=values(category), tags=values(tags), cover=values(cover), published_at=values(published_at), has_video=values(has_video);\n';
fs.writeFileSync('E:/111/project/QTools/QTools/doc/web-mirror/.import.sql', sql);
const out = execFileSync(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','qtools'], { input: sql, encoding: 'utf8' });
console.log('imported', rows.length, 'rows | mysql:', String(out).trim() || 'ok');
const count = execFileSync(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','-N','-B','-e','select count(*), count(distinct category) from tool_web_post where source="mrds"','qtools'], { encoding: 'utf8' });
console.log('db rows/categories:', String(count).trim());
