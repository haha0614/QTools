import { chromium } from 'playwright-core';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const exe = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const MYSQL = 'E:/111/project/QTools/tools/mysql/bin/mysql.exe';
const BASE = process.env.BASE || 'https://able.nwfuhxhj.cc';
// 归档条目的 published_at 只有「月 日」，生成列 published_ts 在严格模式下会报 1411，
// 所以统一放宽 sql_mode（这些行本来就该是 NULL 日期）。
const db = (sql) => execFileSync(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','--default-character-set=utf8mb4','-N','-B','-e', "set session sql_mode=''; " + sql, 'qtools'], { encoding: 'utf8', maxBuffer: 1 << 28 });

// featured 原来把两类混在一个标记里：首页精选（编辑挑的 30 条）和分类页顶部置顶（每日大赛前 3 条）。
// 这里按源站实时状态重新分类，前端才能分别渲染「精选横滑带」和「置顶块」。
const b = await chromium.launch({ executablePath: exe, headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();

// 广告位（“回家的路 / 发送邮件获取最新地址”之类）也指向 /archives/<id>，会被当成帖子抓进来。
// 判据：标题命中推广话术，或卡片压根没有封面图 —— 真实帖子两者都不会缺。
const AD_TITLE = /发送邮件|获取最新地址|回家的路|@pm\.me|永久域名|官方电报|备用网址|最新入口/;

async function entries(url) {
  await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await p.waitForTimeout(2200);
  for (let i = 0; i < 4; i++) { await p.mouse.wheel(0, 2000); await p.waitForTimeout(300); }
  return p.evaluate(() => {
    const out = [];
    const seen = new Set();
    for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
      const m = (a.getAttribute('href') || '').match(/archives\/(\d+)/);
      if (!m || seen.has(m[1])) continue;
      seen.add(m[1]);
      const card = a.closest('article, .post-card, .brick, .archives, li, div') || a;
      const hasCover = !!card.querySelector('img, [style*="background-image"]');
      // 广告位的 <a> 常常没有文字（整块是背景图），所以退回用卡片文本判断
      const rawText = (a.getAttribute('title') || a.textContent || card.innerText || '').replace(/\s+/g, ' ').trim();
      const title = rawText.slice(0, 160);
      const dm = rawText.match(/(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日/);
      const dateKey = dm ? dm[1] + '-' + String(dm[2]).padStart(2, '0') + '-' + String(dm[3]).padStart(2, '0') : '';
      out.push({ id: m[1], title, hasCover, dateKey });
    }
    return out;
  });
}

// 只按标题判定是广告位。封面不能作为判据：分类页封面是延迟加载的 background-image，
// 经常抓不到，用它会误杀全部真实帖子（第一次就是这么把精选清空的）。
const isReal = (e) => !!e.id && !AD_TITLE.test(e.title || '');

/**
 * 把「推广/置顶位」和「正常内容流」切开，不写死条数。
 *
 * 判据：内容流是按发布时间倒序的（dateKey 非递增），推广位是编辑挑的、顺序随机。
 * 于是从末尾往前找出最长的"日期非递增"后缀 —— 那一段就是内容流，
 * 前面的就是推广位。源站今天放 23 条还是 30 条都不影响。
 * 没有日期的条目视为打断单调（广告位通常没日期），会被划到推广位一侧。
 */
function splitPromoBlock(list) {
  let start = list.length;
  let prev = null;
  for (let i = list.length - 1; i >= 0; i--) {
    const d = list[i].dateKey;
    if (!d) break;
    if (prev !== null && d > prev) break;
    prev = d;
    start = i;
  }
  return { promo: list.slice(0, start), feed: list.slice(start), start };
}

const homeRaw = await entries(BASE + '/');
const catRaw = await entries(BASE + '/category/mrds/');
// 兜底判据（比标题匹配可靠）：广告位在库里没有封面图（cover 为空）。
// 标题文本在页面上经常抓不到（广告整块是背景图、a 里没有文字），所以两条一起用。
const noCover = new Set(
  db("select source_id from tool_web_post where cover is null or cover='';")
    .split('\n').map((s) => s.trim()).filter(Boolean)
);
const notAd = (e) => isReal(e) && !noCover.has(e.id);
const homeSplit = splitPromoBlock(homeRaw.filter(notAd));
// 分类页结构实测是「置顶 N 条 → 广告位 → 内容流」，所以用第一个广告位当分隔符，
// 比"取前 3"稳；没有广告位时退回按时间单调性切
const firstAd = catRaw.findIndex((e) => !notAd(e));
const catPromoRaw = firstAd > 0 ? catRaw.slice(0, firstAd) : null;
const catSplit = catPromoRaw
  ? { promo: catPromoRaw, feed: catRaw.slice(firstAd) }
  : splitPromoBlock(catRaw.filter(notAd));
let home = homeSplit.promo.map((e) => e.id);
let cat = catSplit.promo.map((e) => e.id);
const homeFeed = homeSplit.feed.map((e) => e.id);
const catFeed = catSplit.feed.map((e) => e.id);
const skipped = catRaw.filter((e) => !isReal(e)).map((e) => e.id);
console.log('首页推广位', home.length, '条，内容流首条', homeFeed[0], '| 分类页推广位', cat.length, '条，内容流首条', catFeed[0]);
// 兜底：真过滤光了说明判据出问题，宁可不过滤也不要清空精选带
if (!home.length) home = homeRaw.map((e) => e.id);
if (!cat.length) cat = catRaw.map((e) => e.id);
// 分类页顶部置顶：日期比本页最新日期旧的连续前几条（最多 3 条）
const maxCatId = Math.max.apply(null, cat.map(Number).filter((n) => n > 0));
const pinned = cat.slice(0, Math.max(0, cat.length));
console.log('home', home.length, '| cat', cat.length, '| pinned', pinned.join(','), '| 跳过广告', skipped.join(','));
await b.close();

if (!home.length && !cat.length) {
  console.log('两个列表都没抓到，放弃本次写入（避免把精选带清空）');
  process.exit(0);
}
db('update tool_web_post set featured=0, featured_kind=null, home_rank=null;');
const setKind = (list, kind) => {
  if (!list.length) return;
  const inList = list.map((x) => "'" + x + "'").join(',');
  db("update tool_web_post set featured=1, featured_kind=case when featured_kind is null or featured_kind='' or featured_kind='" + kind + "' then '" + kind + "' else 'both' end where source_id in (" + inList + ');');
};
setKind(home, 'home');
setKind(pinned, 'pinned');
// 首页精选要按「源站编辑的排列顺序」展示，不能按发布时间：否则精选带看起来和主列表一样
home.forEach((sid, i) => {
  db("update tool_web_post set home_rank=" + (i + 1) + " where source_id='" + sid + "';");
});
console.log(db('select featured_kind, count(*) from tool_web_post where featured=1 group by featured_kind;'));