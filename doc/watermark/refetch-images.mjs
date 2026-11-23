import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import { rows, sql } from '../lib/db.mjs';

const C = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find((p) => { try { return fs.existsSync(p); } catch { return false; } });
const WEBROOT = process.env.QTOOLS_WEBROOT || path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../../../data/media/web');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';
const LIMIT = Number(process.env.LIMIT || 40);
const DRY = process.env.DRY === '1';
// 源站已删的老帖（302 跳到宣传图）：id 记进这个文件，排空与后台补详情跳过它们。
// 用文件而不是加列 —— tool_web_post.published_ts 是 str_to_date 生成列，表里有 published_at 脏值
// （'5-25'），ALTER TABLE 会整表重建、当场 ERROR 1411（详见 HANDOFF §6.7）。
const DEAD_FILE = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../../../data/detail-dead.json');
const DEAD_IDS = (() => { try { return new Set(JSON.parse(fs.readFileSync(DEAD_FILE, 'utf8'))); } catch (e) { return new Set(); } })();
function markDead(id) {
  if (DRY || DEAD_IDS.has(id)) return;
  DEAD_IDS.add(id);
  try { fs.writeFileSync(DEAD_FILE, JSON.stringify([...DEAD_IDS])); } catch (e) { /* 写不进去不影响本轮 */ }
}

/** 从 JPEG 缓冲区读宽高（只扫 SOF 段，不引第三方库） */
function jpegDims(buf) {
  if (!buf || !buf.length) return null;
  let i = 2;
  while (i + 9 < buf.length) {
    if (buf[i] !== 0xFF) { i += 1; continue; }
    const m = buf[i + 1];
    if (m >= 0xC0 && m <= 0xCF && m !== 0xC4 && m !== 0xC8 && m !== 0xCC) {
      const h = buf.readUInt16BE(i + 5);
      const w = buf.readUInt16BE(i + 7);
      return w > 0 && h > 0 ? [w, h] : null;
    }
    const len = buf.readUInt16BE(i + 2);
    if (len < 2) return null;
    i += 2 + len;
  }
  return null;
}
/**
 * 后台补详情只抓文字/标签/封面，跳过图集下载。
 *
 * 图集是每条最贵的部分（平均 10 张、最多 143 张，占单个帖子 3~5 秒）。
 * 后台先铺满"文字 + 标签 + 封面"，用户点开某条时再由 prefetch（不带这个开关）
 * 按需抓这一条的图 —— 总耗时能降到原来的 1/3~1/5。
 */
const SKIP_IMAGES = process.env.SKIP_IMAGES === '1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 取播放列表里第一个 key/分片路径，作为"是不是同一段视频"的指纹 */
async function fingerprintPlaylist(url) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!r.ok) return null;
    const text = await r.text();
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^#EXT-X-KEY.*URI="([^"]+)"/);
      if (m) return m[1].split('?')[0];
    }
    for (const line of text.split(/\r?\n/)) {
      if (line && !line.startsWith('#')) return line.split('?')[0];
    }
  } catch (err) {
    /* 取不到就保留原地址 */
  }
  return null;
}

/** 按内容指纹去重：同一条视频的"直连地址 / 包装地址"会指向同一份分片 */
async function dedupeVideoUrls(list) {
  if (list.length < 2) return list.slice();
  const seen = new Set();
  const out = [];
  for (const u of list) {
    const fp = await fingerprintPlaylist(u);
    const key = fp || u;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(u);
  }
  return out;
}

// 站点样板行（推荐位标题、投稿说明等）：由 clean-noise.mjs 按文档频率自动发现并写盘，
// 抽取阶段直接丢掉，新抓的帖子就不会再把它们当正文存进来。
const NOISE_FILE = process.env.QTOOLS_NOISE || path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), 'noise-lines.json');
const NOISE_LINES = (() => {
  try {
    return new Set(JSON.parse(fs.readFileSync(NOISE_FILE, 'utf8')));
  } catch (e) {
    return new Set();
  }
})();
const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");

const SID = process.env.SID || '';
// ROWID = 本站主键（sidecar 预取队列用的是它）；SID = 源站 id（手动排查时用）
// FORCE_TOP = 不管有没有详情，按源站 id 倒序重抓前 N 条（用于回填 published_full）
const ROWID = process.env.ROWID || '';
const FORCE_TOP = Number(process.env.FORCE_TOP || 0);
let WHERE = FORCE_TOP
  ? ' order by cast(source_id as unsigned) desc'
  : (ROWID
  ? ' where id=' + Number(ROWID)
  : (SID
  ? " where source_id='" + SID + "'"
  : (process.env.ONLY_NO_DETAIL === '1'
    ? " where content_text is null or content_text=''"
    : (process.env.ONLY_NEW_VIDEO === '1' ? " where video_url is null or video_url=''" : ''))));
// 补详情要"补最新的"：source_id 是源站自增 id，越大越新；source_rank 记的是抓取顺序，不适合
// ONLY_NO_DETAIL 原来不带 ORDER，MySQL 每轮都可能返回同一批行（补不动的就永远卡在那）；
// 加 order by id 让批次随补完推进。
// IDS：只抓指定的这几行（后台「连续排空」用 —— 由 sidecar 自己按游标选行，
// 保证每批都往前推进，抓不动的行（404 / 页面没正文）不会永远占着队头）。
const IDS = String(process.env.IDS || '').split(',').map((x) => Number(x)).filter((n) => n > 0);
if (IDS.length) WHERE = ' where id in (' + IDS.join(',') + ')';
const ORDER = FORCE_TOP ? '' : ' order by id';
const posts = rows('select id, source_id, url, video_url from tool_web_post' + WHERE + ORDER + ' limit ' + LIMIT)
  .map(([id, sid, url, video]) => ({ id, sid, url, video: video || '' }));
console.log('rows', posts.length, DRY ? '(dry)' : '');

// ── 页面卡死的兜底（2026-11-23） ──────────────────────────────────
// Playwright 的 evaluate 没有内置超时；源站个别帖子的脚本会进死循环，一条帖就能把整批拖死，
// 而且卡住的渲染器会一直满速空转（2026-11-23 那次 4 个 E 核被打满就是这一类，见 HANDOFF §15.2）。
// 做法：给页面调用套一层超时；超时后整只浏览器回收重开（close 内部会走 taskkill /T /F）。
// 整批卡死的第二道兜底：页面里的死循环能被上面的 evaluate 超时兜住，但「页面不报错、也没任何回应」
// （浏览器进程挂着、源站不回包、mysql CLI 不返回）照样能让这一批永不结束，sidecar 就一直卡在
// refreshing true 里等（2026-11-23 11:31 实测：整批 10 分钟零输出、零请求、CPU 也不涨）。
// 心跳超时自己退出，让 sidecar 记一次 fail 并按退避重试 —— 卡死变可重试，而不是永久停摆。
const STALL_MS = Number(process.env.STALL_MS || 180000);
let lastBeat = Date.now();
function beat() { lastBeat = Date.now(); }
const stallTimer = setInterval(() => {
  if (Date.now() - lastBeat < STALL_MS) return;
  console.log('  stall 超过 ' + Math.round(STALL_MS / 1000) + ' 秒没有任何进度，退出让 sidecar 重试');
  process.exit(2);
}, 5000);
stallTimer.unref();
const EVAL_TIMEOUT = Number(process.env.EVAL_TIMEOUT || 25000);
let pageHung = false;
let curPage = null;
const timed = (fn, ms, tag) => (...args) => {
  let timer;
  const guard = new Promise((_, rej) => {
    timer = setTimeout(() => { pageHung = true; rej(new Error('页面无响应：' + tag + ' 超过 ' + ms + 'ms')); }, ms);
  });
  const call = Promise.resolve().then(() => fn(...args));
  call.catch(() => {});
  return Promise.race([call, guard]).finally(() => { clearTimeout(timer); beat(); });
};
const p = new Proxy({}, {
  get(_, key) {
    if (!curPage) throw new Error('页面尚未创建');
    const v = curPage[key];
    if (typeof v !== 'function') return v;
    const bound = v.bind(curPage);
    if (key === 'evaluate') return timed(bound, EVAL_TIMEOUT, 'evaluate');
    if (key === 'goto') return timed(bound, Number(process.env.GOTO_TIMEOUT || 45000) + 5000, 'goto');
    return bound;
  },
});
let b = null;
async function openBrowser() {
  if (b) {
    // 卡死的渲染器可能连 close 都不理，最多等 8 秒就走下一步（Playwright 自己会降级到 taskkill /T /F）
    await Promise.race([b.close().catch(() => {}), new Promise((r) => setTimeout(r, 8000))]);
  }
  b = await chromium.launch({ executablePath: C, headless: process.env.HEADLESS === '1', args: ['--mute-audio','--window-position=-32000,-32000','--window-size=1200,900','--disable-blink-features=AutomationControlled'] });
  const ctx = await b.newContext({ userAgent: UA, viewport: { width: 1280, height: 900 } });
  // 只补正文时把图片/媒体/字体直接掐断：源站详情页是被重定向到的新域名在扛，落地页的图片资源
  // 常常让 domcontentloaded 干等 25 秒（实测 curl 拿首跳 1 秒、Chromium 等整页 24.8 秒）。
  // 正文/标签在 DOM 里，掐掉这些不影响解析，反而把每条从 ~25 秒压回几秒（2026-11-23 实测）。
  if (SKIP_IMAGES) {
    await ctx.route('**/*', (route) => {
      const t = route.request().resourceType();
      if (t === 'image' || t === 'media' || t === 'font') return route.abort();
      return route.continue();
    });
  }
  curPage = await ctx.newPage();
}
await openBrowser();

// 源站删帖的第二种形态：页面不是 200 而是 30x 跳到宣传图，Chromium 会报
// ERR_NAME_NOT_RESOLVED / ERR_INVALID_RESPONSE 之类（重定向目标的域名常常已经不解析），
// 拿不到 content-type 就没法判断。这里用一次手动跟跳的 HTTP 探测补判：
// 跳到图片、或落地不是 html → 死链；探测本身失败（真网络抖动）→ 不下结论，仍算失败重试。
const IMG_LOC = /\.(jpe?g|png|gif|webp|bmp|avif)(\?|#|$)/i;
async function probeDead(url) {
  let cur = url;
  for (let hop = 0; hop < 4; hop++) {
    let r;
    try {
      r = await fetch(cur, { redirect: 'manual', headers: { 'user-agent': UA }, signal: AbortSignal.timeout(5000) });
    } catch (e) { return ''; }
    const loc = r.headers.get('location') || '';
    if (r.status >= 300 && r.status < 400 && loc) {
      if (IMG_LOC.test(loc)) return '跳到图片';
      try { cur = new URL(loc, cur).toString(); } catch (e) { return ''; }
      continue;
    }
    const ct = String(r.headers.get('content-type') || '').toLowerCase();
    if (r.ok && ct && !ct.includes('html')) return '落地是 ' + ct;
    return '';
  }
  return '';
}

let okCount = 0, imgTotal = 0, noImg = [], goneCount = 0;
// 「打不开」的名额（每批最多 5 个）：有些老帖不是 302 跳图片，而是连响应都没有 —— goto 每次干等到超时。
// 这类行在源站侧等同已删（活帖实测 1~2 秒就返回），三次都打不开就记进死链名单，避免它每轮再耗 75 秒。
// 但整站抖动也会表现成同样症状，所以每批封顶 5 个，超了就不记（宁可慢一轮，也不要一次把活帖全标死）。
let deadByTimeout = 0;
const HOPELESS = /ERR_NAME_NOT_RESOLVED|ERR_CONNECTION|ERR_EMPTY_RESPONSE|ERR_TIMED_OUT|ERR_ADDRESS_UNREACHABLE|ERR_CERT|net::|Timeout|timed out/i;
for (const row of posts) {
  beat();
  try {
    let nav = false;
    let raw = '';
    let gone = false;
    let why = '';
    for (let attempt = 1; attempt <= 3 && !nav; attempt++) {
      try {
        // GOTO_TIMEOUT：后台排空用更短的超时 —— 源站偶发 DNS/证书失败，45 秒白等一条会拖垮整批
        const resp = await p.goto(row.url, { waitUntil: 'domcontentloaded', timeout: Number(process.env.GOTO_TIMEOUT || 45000) });
        nav = true;
        // 源站删掉的老帖不是 404，而是 302 跳到一张宣传图（Content-Type: image/jpeg）——
        // 这种行重试再多次也拿不到正文，记一次 detail_checked_at 让排空跳过它；
        // 不记的话 9.8k 条死链会把队列永远占住，每条还要白等 goto 超时（实测整批卡 10 分钟零输出）。
        const ct = String((resp && resp.headers && resp.headers()['content-type']) || '').toLowerCase();
        if (ct && !ct.includes('html')) { gone = true; why = '落地是 ' + ct; }
        try { raw = gone ? '' : ((await resp.text()) || ''); } catch (e) { raw = ''; }
      } catch (e) {
        // 死链的导航失败形态两种：立刻报 ERR_NAME_NOT_RESOLVED，或一直等到 GOTO_TIMEOUT（25s）。
        // 后者重试 3 次就要白等 75 秒/条，一批 40 条能耗掉十几分钟 → 第一次失败就先探一次：
        // 探测确认是死链就当场收工，只有"探测也说不清"才走重试。
        why = await probeDead(row.url);
        if (why) { gone = true; break; }
        if (attempt < 3) { await p.waitForTimeout(1200); continue; }
        if (HOPELESS.test(String((e && e.message) || e)) && deadByTimeout < 5) {
          deadByTimeout += 1;
          why = '三次都打不开';
          gone = true;
          break;
        }
        throw e;
      }
    }
    if (gone) {
      goneCount += 1;
      markDead(row.id);
      console.log('  gone', row.sid, '| 源站已删' + (why ? '（' + why + '）' : '（跳到图片）'));
      beat();
      await sleep(120);
      continue;
    }
    // 原来这里固定等 800ms + 滚动 4×250ms + 500ms ≈ 2.3 秒。实测帖图是内联 data URI，
    // DOMContentLoaded 后 0.5~0.9 秒由页面脚本注入；但个别帖（228293）不滚就一张都不注入，
    // 所以滚动照旧保留（只是把 250ms 的步间隔压到 120ms），等图改为"数到稳定为止"：
    // 连续 3 次读数不变即视为就绪，上限 1500ms。实测每条约省 0.9~1.0 秒。
    const countInline = () => p.evaluate(() => {
      let k = 0;
      for (const el of document.querySelectorAll('.post-content img')) {
        const s = el.getAttribute('src') || '';
        if (s.startsWith('data:image/') && s.length > 20000) k++;
      }
      return k;
    });
    if (SKIP_IMAGES) {
      // 不抓图就不用滚动催加载：正文/标签在首屏 DOM 里就有
      await p.waitForTimeout(400);
    } else {
      for (let i = 0; i < 4; i++) { await p.mouse.wheel(0, 1600); await p.waitForTimeout(120); }
      let ready = -1, same = 0;
      for (let waited = 0; waited < 1500 && same < 3; waited += 120) {
        const n = await countInline();
        if (n === ready) same++; else { ready = n; same = 0; }
        await p.waitForTimeout(120);
      }
    }
    // ★ 源站的帖图不是外链，而是把整张 JPEG 以 base64 data URI 直接内联在 <img src> 里
    //   （data-xkrkllgl 那个 https://pic.gyezbp.cn/... 地址对外只返回加密的 binary/octet-stream，
    //    抓下来是乱码）。所以正确做法是从 DOM 取 data URI 再解码落盘。
    // 真正的帖图在 div.post-content 里；页面顶部/底部的 img#article-*-banner-* 是站点广告位
    // （alt 是"在线约炮/PG官方"之类），必须靠容器边界排除。
    const picked = await p.evaluate(() => {
      const out = [];
      const inline = (el) => { const s = el.getAttribute('src') || ''; return s.startsWith('data:image/') && s.length > 20000; };
      for (const el of document.querySelectorAll('.post-content img')) if (inline(el)) out.push({ key: '', src: el.getAttribute('src') });
      if (!out.length) {
        for (const el of document.querySelectorAll('img')) {
          const src = el.getAttribute('src') || '';
          let key = '';
          for (const a of el.attributes) if (a.value.indexOf('upload_01') >= 0) key = a.value;
          if (key || (/^https?:/.test(src) && src.indexOf('upload_01') >= 0)) out.push({ key, src });
        }
      }
      return out;
    });

    const dir = path.join(WEBROOT, row.sid);
    const local = [];
    if (picked.length) {
      fs.mkdirSync(dir, { recursive: true });
      for (let n = 0; n < picked.length; n++) {
        const it = picked[n];
        const file = 'p' + (n + 1) + '.jpg';
        const dst = path.join(dir, file);
        const comma = it.src.indexOf(',');
        if (it.src.startsWith('data:')) {
          const buf = Buffer.from(it.src.slice(comma + 1), 'base64');
          if (buf.length < 2000) continue;
          const exists = fs.existsSync(dst) && fs.statSync(dst).size === buf.length;
          if (!DRY && !SKIP_IMAGES && !exists) fs.writeFileSync(dst, buf);
          // 跳过图片时不记录本地路径，免得详情页引用到不存在的文件
          if (!SKIP_IMAGES) local.push('/web/' + row.sid + '/' + file);
        } else {
          local.push(it.src);
        }
      }
    }

    // 正文文字与分类：新入库的条目没有历史 blocks，文字必须在这里一并抓
    const meta = await p.evaluate((__NOISE_LINES__) => {
      let category = '';
      const crumb = document.querySelector('.xqbj-breadcrumb-container');
      if (crumb) {
        const parts = (crumb.innerText || '').split('>').map((s) => s.trim()).filter(Boolean);
        if (parts.length >= 2) category = parts[parts.length - 2];
      }
      let box = null, best = -1;
      document.querySelectorAll('.post-content, .post, article, .entry-content').forEach((el) => {
        const score = el.querySelectorAll('p').length + el.querySelectorAll('img').length * 2;
        if (score > best) { best = score; box = el; }
      });
      const NOISE = /在线约炮|同城约炮|广告|最新地址|自动回复|推荐浏览器|邮箱自动|热门大赛|重磅热瓜|温馨提示|重要提示|点击可下载|官方网址|分享每日大赛|复制最新网址|谨防假冒|永久有效|点我查看详情帖|大赛家族期待|欢迎加入每日大赛|官方Github|gitlab\.com/;
      // 纯推广链接行（外链、短链、带 code 参数的推广位）
      const PROMO_LINK = /^https?:\/\/|^www\.|gitlab\.com|aff\d+\.|\.org\/\?code=/;
      // 正文结尾标记：源站用「关键词：#…」或「关键字：#…」（两种写法都有）
      const TAIL_MARK = /(关键词|关键字)\s*[:：]/;
      // 与下方 NOISE_LINES 配合：正则挡常见的推广语，清单挡"推荐位标题"这类整行样板
      const noiseSet = new Set(__NOISE_LINES__);
      const seen = new Set();
      const texts = [];
      let keywordLine = '';
      if (box) {
        for (const el of box.querySelectorAll('p, h2, h3, blockquote')) {
          const t = (el.textContent || '').replace(/\s+/g, ' ').trim();
          if (!t || t.length < 10 || NOISE.test(t) || PROMO_LINK.test(t) || seen.has(t) || noiseSet.has(t)) continue;
          seen.add(t);
          texts.push(t.slice(0, 2000));
          // ★「关键词/关键字：#a #b …」是源站正文的结尾标记，其后挂的全是推荐位与推广文案。
          //   实测 1,263/1,315 条以它结尾、0 条出现两次，见到就停。
          if (TAIL_MARK.test(t)) {
            keywordLine = t;
            break;
          }
        }
      }
      // 详情页有完整日期（归档页只有月日），抓一次就把它变成真数据
      let dateText = '';
      let fullTime = '';
      const meta = document.querySelector('meta[property="article:published_time"]');
      if (meta && meta.getAttribute('content')) {
        const raw = meta.getAttribute('content') || '';
        const m = raw.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
        if (m) {
          dateText = Number(m[1]) + ' 年 ' + Number(m[2]) + ' 月 ' + Number(m[3]) + ' 日';
          // 保留完整时分秒：源站列表就是按它排的，只留日期会丢掉同一天内的先后
          fullTime = m[1] + '-' + m[2] + '-' + m[3] + ' ' + m[4] + ':' + m[5] + ':' + m[6];
        } else {
          const d = raw.match(/^(\d{4})-(\d{2})-(\d{2})/);
          if (d) dateText = Number(d[1]) + ' 年 ' + Number(d[2]) + ' 月 ' + Number(d[3]) + ' 日';
        }
      }
      // 标签：详情页的 .keywords 里是一串 /tag/xxx/ 链接（以前完全没抽，导致标签云只有最早那 30 条有数据）
      const tagSet = new Set();
      for (const a of document.querySelectorAll('.keywords a, a[href*="/tag/"]')) {
        const t = (a.textContent || '').trim();
        if (t && t.length <= 32 && !t.includes(',')) tagSet.add(t);
      }
      // 标签兜底：页面 .keywords 链接抽不到时，从「关键词：#a #b」行里解析
      if (!tagSet.size && keywordLine) {
        // 关键词行格式统一为 #tag #tag …，直接当标签兜底
        const hashes = keywordLine.match(/#[^\s#]+/g) || [];
        for (const h of hashes) {
          const t = h.slice(1).trim();
          if (t && t.length <= 32) tagSet.add(t);
        }
      }
      return { category, texts: texts.slice(0, 40), dateText, fullTime, tags: Array.from(tagSet).slice(0, 20) };
    }, Array.from(NOISE_LINES));

    // 播放地址在页面配置 JSON 里："video":{"url":"https://…m3u8?auth_key=…&v=3&time=0"}
    // 斜杠被转义成 \/，直接搜 https:// 会漏，必须按 charCode 走
    // 一个帖子可能有多个播放器（多视频）：把所有 "url":"…m3u8…" 都抽出来，按出现顺序去重
    const videoUrls = [];
    {
      const src = raw || '';
      const q = String.fromCharCode(34);
      let from = 0;
      while (true) {
        const k = src.indexOf('m3u8', from);
        if (k < 0) break;
        from = k + 4;
        // 同一段视频在页面里会出现两次：video(H.264) 和 video_h265(H.265)。
        // 后者只是另一种编码，不是"第二个视频"，必须排除，否则前端会多出一个一样的播放器。
        const pH264 = src.lastIndexOf(q + 'video' + q + ':', k);
        const pH265 = src.lastIndexOf(q + 'video_h265' + q + ':', k);
        if (pH265 > pH264) continue;
        const startKey = src.lastIndexOf(q + 'url' + q + ':', k);
        if (startKey < 0) continue;
        let j = startKey + 7;
        let out = '';
        while (j < src.length && src.charCodeAt(j) !== 34) {
          const c = src.charCodeAt(j);
          if (c === 92 && j + 1 < src.length) { j++; out += src[j]; } else { out += src[j]; }
          j++;
        }
        if (out.indexOf('m3u8') >= 0 && !videoUrls.includes(out)) videoUrls.push(out);
      }
    }
    // 同一段视频常有两种地址：直连 videos5/<hash>.m3u8 和 m3m/... 的包装地址，
    // 播放内容完全一样。按"播放列表指向的 key/分片路径"做指纹去重，
    // 否则前端会把同一条视频渲染成两个播放器（用户看到"两个一样的视频"）。
    const dedupedVideos = await dedupeVideoUrls(videoUrls);
    videoUrls.length = 0;
    videoUrls.push(...dedupedVideos);
    const videoUrl = videoUrls[0] || '';

    const old = sql("select replace(replace(content, '\\\\', '\\\\\\\\'), '''', '''''') from tool_web_post where id=" + row.id + ';').trim();
    let blocks = [];
    try { blocks = JSON.parse(old.replace(/''/g, "'")) || []; } catch (e) { blocks = []; }
    const oldTexts = blocks.filter((x) => x && x.type === 'text').map((x) => x.text);
    const texts = meta.texts.length ? meta.texts : oldTexts;
    const next = texts.map((t) => ({ type: 'text', text: t })).concat(local.map((u) => ({ type: 'image', url: u })));
    const json = JSON.stringify(next).replace(/\\/g, '\\\\').replace(/'/g, "''");
    const textJoin = texts.join('\n').slice(0, 60000);

    if (!DRY) {
      // 只写正文与计数，绝不碰 cover（封面由 refetch-covers 负责）
      // 跳过图片的后台补齐：图片张数按页面实际张数记录，
      // 详情页据此判断"这条应该有图但本地没有"，用户点开时再补抓
      // 方向：优先用视频比例（video_ratios 由 ffprobe 写），没有就退到图集第一张的尺寸
      const firstRatio = (videoUrls.length ? String(meta.videoRatio || '') : '');
      const dim = firstRatio && /^\d+\/\d+$/.test(firstRatio)
        ? firstRatio.split('/').map(Number)
        : (/^data:image\//.test((picked[0] && picked[0].src) || '') ? jpegDims(Buffer.from(String(picked[0].src).slice(String(picked[0].src).indexOf(',') + 1), 'base64')) : null);
      const orientationSql = dim && dim[0] > 0 && dim[1] > 0
        ? ", orientation=case when orientation is null then '" + (dim[0] >= dim[1] ? 'l' : 'p') + "' else orientation end"
        : '';
      const imgCountForWrite = SKIP_IMAGES ? picked.length : local.length;
      sql("update tool_web_post set content='" + json + "', image_count=" + imgCountForWrite
        + orientationSql
        + ", content_text='" + textJoin.replace(/\\/g, '\\\\').replace(/'/g, "''") + "'"
        + (meta.category ? ", category='" + meta.category.replace(/'/g, "''") + "'" : '')
        + (meta.dateText ? ", published_at='" + meta.dateText.replace(/'/g, "''") + "'" : '')
        // 抽到标签就写；一条都没抽到也写空串，作为"已检查过"的标记（否则后台会反复重抓）
        + ", tags='" + (meta.tags || []).join(',').replace(/'/g, "''") + "'"
        + (meta.fullTime ? ", published_full='" + meta.fullTime + "'" : '')
        + (videoUrl ? ", video_url='" + videoUrl.replace(/'/g, "''") + "'"
            + ", video_urls='" + JSON.stringify(videoUrls).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'"
            + ", video_count=" + videoUrls.length + ", has_video=1" : '')
        + (local.length ? ", cover=case when cover is null or cover='' then '" + local[0] + "' else cover end" : '')
        + ' where id=' + row.id + ';');
    }
    okCount++; imgTotal += local.length;
    if (!local.length) noImg.push(row.sid + (row.video ? '(vid)' : ''));
    console.log('  ok', row.sid, '| imgs', local.length, '| text', texts.length, '| cat', meta.category || '-', '| vid', videoUrls.length);
  } catch (e) { console.log('  fail', row.sid, String(e.message).slice(0, 100)); }
  if (pageHung) {
    pageHung = false;
    console.log('  recycle 页面卡死，回收浏览器 | post', row.sid);
    await openBrowser();
  }
  beat();
  await sleep(120);
}
await b.close();
console.log('done', okCount, '/', posts.length, '| images', imgTotal, '| 源站已删', goneCount, deadByTimeout ? '| 其中打不开 ' + deadByTimeout : '');
if (noImg.length) console.log('no-image posts:', noImg.join(', '));