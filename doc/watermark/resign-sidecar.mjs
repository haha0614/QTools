import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { execFile, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chromium } from 'playwright-core';
import { sqlAsync } from '../lib/db.mjs';

// 重签 sidecar：源站详情页对普通 HTTP 请求返回的是假页面（curl 拿不到 m3u8），
// 只有真浏览器渲染后配置 JSON 才在。所以「签名过期」这件事后端自己解决不了，
// 由这个本地小服务代劳：后端播放列表 502 时 POST 过来，拿回新的签名地址。
const PORT = Number(process.env.SIDECAR_PORT || 8765);
const exe = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find((p) => { try { return fs.existsSync(p); } catch { return false; } });

let browser = null;
let queue = Promise.resolve();

async function getBrowser() {
  if (browser && browser.isConnected()) return browser;
  browser = await chromium.launch({ executablePath: exe, headless: true, args: ['--mute-audio','--disable-blink-features=AutomationControlled'] });
  return browser;
}

/** 取页面配置里第一个 m3u8（斜杠被转义成 \/，按 charCode 走） */
function pickM3u8(src) {
  const k = src.indexOf('m3u8');
  if (k < 0) return '';
  const q = String.fromCharCode(34);
  const startKey = src.lastIndexOf(q + 'url' + q + ':', k);
  if (startKey < 0) return '';
  let j = startKey + 7;
  let out = '';
  while (j < src.length && src.charCodeAt(j) !== 34) {
    const c = src.charCodeAt(j);
    if (c === 92 && j + 1 < src.length) { j++; out += src[j]; } else { out += src[j]; }
    j++;
  }
  return out.indexOf('m3u8') >= 0 ? out : '';
}

/**
 * 重签：返回页面上的全部视频地址（一条帖子可能有多段视频）。
 * 只返回第一条会让"第 2 段视频过期"时被错播成第 1 段，所以这里给出完整数组。
 */
// 方案2：短 TTL 缓存。同一个帖子在"恢复播放"之前会被前端重试打好几次，
// 每次都排队开一遍浏览器；签名有效期约 1 小时，90 秒内复用完全安全。
const resignCache = new Map();
const RESIGN_CACHE_MS = Number(process.env.RESIGN_CACHE_MS || 90000);

// 同一个 URL 的并发请求合并成一次真实重签（single-flight）：
// 前端重试会连发好几次，若每次都排队开浏览器，队列会越排越长（实测第 2 次 30 秒）。
const resignInflight = new Map();

async function resign(url) {
  const hit = resignCache.get(url);
  if (hit && Date.now() - hit.at < RESIGN_CACHE_MS) return hit.urls;
  const running = resignInflight.get(url);
  if (running) return running;
  const task = (async () => {
    const urls = await resignFresh(url);
    if (urls && urls.length) resignCache.set(url, { urls, at: Date.now() });
    if (resignCache.size > 500) {
      for (const [k, v] of resignCache) if (Date.now() - v.at > RESIGN_CACHE_MS) resignCache.delete(k);
    }
    return urls;
  })();
  resignInflight.set(url, task);
  try {
    return await task;
  } finally {
    resignInflight.delete(url);
  }
}

// 复用同一个 context：每次 newContext 都要重新建一次隔离环境（几百毫秒，
// 且会让 Cloudflare 更警惕）。同时在路由层拦掉图片/字体/样式/媒体 ——
// 我们只需要播放器配置那段 JSON，这些资源一个都不用下载。
let resignCtx = null;
async function getResignContext(b) {
  if (resignCtx) return resignCtx;
  const ctx = await b.newContext({ viewport: { width: 1200, height: 800 } });
  try {
    await ctx.route('**/*', (route) => {
      const t = route.request().resourceType();
      if (t === 'image' || t === 'font' || t === 'stylesheet' || t === 'media') {
        return route.abort().catch(() => {});
      }
      return route.continue().catch(() => {});
    });
  } catch (e) { /* 老版本 playwright 不支持 route 时忽略 */ }
  resignCtx = ctx;
  return ctx;
}

async function resignFresh(url) {
  const b = await getBrowser();
  const ctx = await getResignContext(b);
  const p = await ctx.newPage();
  try {
    const resp = await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    const raw = (resp && (await resp.text())) || '';
    // 快路径：原始 HTML 里就有播放器配置时，不再序列化整页 DOM
    // （p.content() 在这类页面上要 1~3 秒，是"慢"的主要来源之一）
    let urls = pickAllM3u8(raw);
    if (!urls.length) {
      // 有些播放器配置是 JS 注入的，只取 HTML 会漏段（历史上只拿到 1 条导致两段都播第 1 段）
      const dom = await p.content();
      urls = pickAllM3u8(raw + '\n' + dom);
    }
    return urls;
  } finally {
    await p.close().catch(() => {});
  }
}

/** 抽出页面上所有 "video":{…"url":"…m3u8…"}（排除 video_h265 与广告位） */
function pickAllM3u8(html) {
  const out = [];
  // 只认键名恰好是 video 的那一块；video_h265 / 其它推荐位不会被匹配到
  const re = /"video"\s*:\s*\{[^{}]*?"url"\s*:\s*"([^"]+?m3u8[^"]*)"/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const url = m[1].replace(/\\\//g, '/');
    if (!out.includes(url)) out.push(url);
  }
  // 上限只是防「匹配到推荐位/其它帖」的保险，别当成业务规则：
  // 源站多集帖实测 12 条（archives/202112），原来写死 10 会把第 11、12 集直接截掉
  return out.slice(0, 50);
}

// ---- 定时拉最新 ----
// 源站随时在更新，库必须自己追：归档页(全量历史) + 分类页(最新/置顶) 各抓第一层，再给最新几条补详情。
const REFRESH_MINUTES = Number(process.env.REFRESH_MINUTES || 20);
let refreshing = false;

/** 库里的主域名：换域名后这里会跟着变，子进程继承 BASE 就不用手改脚本 */
let cachedBase = '';
async function sourceBase() {
  if (cachedBase) return cachedBase;
  try {
    const h = await sqlOne("select substring_index(substring_index(url,'//',-1),'/',1) from tool_web_post group by 1 order by count(*) desc limit 1;");
    if (h) cachedBase = 'https://' + String(h).trim();
  } catch (e) { /* 查不到就用脚本里的默认值 */ }
  return cachedBase;
}

/**
 * 每轮刷新前先确认源站主机还活着。
 *
 * 源站域名轮换后，库里 url 全指向死域名 → 重签超时 → 播放全 502。
 * sync-source-host.mjs 自己做健康检查（活着就什么都不做），只有真换域名才备份 + 迁移；
 * 迁移完成就清掉 cachedBase，后面的 sourceBase() 会重新读库里的新主机。
 */
async function syncSourceHost() {
  try {
    const out = await new Promise((resolve, reject) => {
      execFile(process.execPath, [new URL('./sync-source-host.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')], {
        cwd: new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'),
        windowsHide: true,
        env: {
          ...process.env,
        },
        encoding: 'utf8',
        maxBuffer: 1 << 24,
        timeout: 5 * 60 * 1000,
      }, (err, stdout) => (err ? reject(err) : resolve(stdout || '')));
    });
    const last = out.trim().split('\n').filter(Boolean).pop() || '';
    console.log(new Date().toISOString(), 'host sync', last.slice(0, 150));
    if (/迁移完成/.test(last)) {
      cachedBase = '';
      console.log(new Date().toISOString(), 'host sync 已换域名，本轮刷新使用新主机');
    }
  } catch (e) {
    // 退出码 2 = 发现不到新主机（需人工）；不是致命错误，刷新照常进行
    const raw = String((e && e.stdout) || (e && e.message) || e).trim();
    const last = raw.split('\n').filter(Boolean).pop() || '';
    console.log(new Date().toISOString(), 'host sync skip', last.slice(0, 150));
  }
}

async function runRefresh(quick) {
  if (refreshing) return;
  refreshing = true;
  await syncSourceHost();
  const script = quick ? './refresh-quick.mjs' : './refresh-latest.mjs';
  // 从库里主域名推基址，换域名后子进程自动跟上（脚本里的默认值只是兜底）
  const base = await sourceBase();
  try {
    // 异步：同步版本会把整个 sidecar 卡住几分钟（重签/预取全部无响应）
    const out = await new Promise((resolve, reject) => {
      execFile(process.execPath, [new URL(script, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')], {
        cwd: new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'),
        windowsHide: true,
        // DETAIL_N=0：refresh-latest 的详情阶段与后台 detail drain 抢浏览器（源站有 Cloudflare，并发只会互相拖慢），
        // 实测该阶段 spawnSync 撞 10 分钟 timeout 28 次、成功 0 次；补详情统一由后台 drain 串行做。
        env: { ...process.env, HEADLESS: '1', DETAIL_N: '0', ...(base ? { BASE: base } : {}) },
        encoding: 'utf8',
        maxBuffer: 1 << 26,
        timeout: 15 * 60 * 1000,
      }, (err, stdout) => (err ? reject(err) : resolve(stdout || '')));
    });
    console.log(new Date().toISOString(), 'refresh ok', out.trim().split('\n').slice(-2).join(' | '));
  } catch (e) {
    console.log(new Date().toISOString(), 'refresh fail', String((e && e.message) || e).slice(0, 160));
  } finally {
    refreshing = false;
  }
}

if (REFRESH_MINUTES > 0) {
  setTimeout(() => runRefresh(false), 30 * 1000);
  setInterval(() => runRefresh(false), REFRESH_MINUTES * 60 * 1000);
  console.log('auto refresh every ' + REFRESH_MINUTES + ' min');
}

// ---- 详情抓取队列（P0 点开插队 / P1 当前可见页）----
// 并发固定 1：源站有 Cloudflare，多开会互相拖慢甚至被拦。
// 复用 refetch-images.mjs（SID=<id>），它已经把正文/图集/播放地址/分类/真实日期/封面兜底都做完了。
const DETAIL_MIN_GAP_MS = Number(process.env.DETAIL_MIN_GAP_MS || 2500);
const DETAIL_QUEUE_MAX = Number(process.env.DETAIL_QUEUE_MAX || 200);
let detailQueue = [];
let working = false;
let lastWorkAt = 0;

function enqueueDetail(ids, priority) {
  const prio = priority === 'open' || priority === 'bg' ? priority : 'view';
  let added = 0;
  for (const id of ids) {
    const n = Number(id);
    if (!Number.isFinite(n) || n <= 0) continue;
    const existing = detailQueue.findIndex((q) => q.id === n);
    if (existing >= 0) {
      // 已在队列里：只升级优先级（bg → view → open），不重复排队
      const cur = detailQueue[existing].priority;
      const wanted = prio;
      const rankOf = (p) => (p === 'open' ? 0 : p === 'view' ? 1 : 2);
      if (rankOf(wanted) < rankOf(cur)) detailQueue[existing].priority = wanted;
      continue;
    }
    detailQueue.push({ id: n, priority, at: Date.now() });
    added += 1;
  }
  // P1 只保留最新的最近 200 条，避免快速翻页把队列塞满历史页
  if (detailQueue.length > DETAIL_QUEUE_MAX) {
    const openOnes = detailQueue.filter((q) => q.priority === 'open');
    const views = detailQueue.filter((q) => q.priority !== 'open').slice(-(DETAIL_QUEUE_MAX - openOnes.length));
    detailQueue = openOnes.concat(views);
  }
  // 必须下一轮再跑：pumpDetail 里的取数会阻塞事件循环十几秒，
  // 同步调用会把"已入队"的响应也堵住，客户端直接超时。
  setImmediate(() => {
    try { pumpDetail(); } catch (e) { console.log('pump error', String((e && e.stack) || e).slice(0, 300)); }
  });
  return added;
}

async function pumpDetail() {
  if (working) return;
  working = true;
  try {
    while (detailQueue.length) {
      // 归档独占浏览器期间让路：归档一条要跑完整帖（详情+全段视频），插进来只会互相拖慢
      while (archiveBusy) await sleep(1000);
      const gap = DETAIL_MIN_GAP_MS - (Date.now() - lastWorkAt);
      if (gap > 0) await new Promise((r) => setTimeout(r, gap));
      // 优先级：open(用户点开) > view(当前页预取) > bg(后台补齐)
      const rankOf = (p) => (p === 'open' ? 0 : p === 'view' ? 1 : 2);
      detailQueue.sort((a, b) => (rankOf(a.priority) - rankOf(b.priority)) || (a.at - b.at));
      const job = detailQueue.shift();
      lastWorkAt = Date.now();
      try {
        // 异步执行：同步的 execFileSync 会把整个 sidecar（含重签接口）卡住十几秒
        const out = await new Promise((resolve, reject) => {
          execFile(process.execPath, [new URL('./refetch-images.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')], {
            cwd: new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'),
            windowsHide: true,
            // HEADLESS=1：后台抓取绝不弹窗抢焦点（以前每抓一条就新开一个可见窗口）
            env: {
              ...process.env,
              ROWID: String(job.id),
              HEADLESS: '1',
              // 只有后台补齐(bg)跳图片；用户点开(open)和当前页预取(view)仍抓图，
              // 保证"看得到的那几条"永远是完整的
              SKIP_IMAGES: job.priority === 'bg' ? '1' : '0'
            },
            encoding: 'utf8',
            maxBuffer: 1 << 26,
            timeout: 3 * 60 * 1000,
          }, (err, stdout) => (err ? reject(err) : resolve(stdout || '')));
        });
        console.log(new Date().toISOString(), 'detail', job.id, job.priority, out.trim().split('\n').slice(-1)[0]);
      } catch (e) {
        console.log(new Date().toISOString(), 'detail fail', job.id, String((e && e.message) || e).slice(0, 120));
      }
    }
  } finally {
    working = false;
  }
}

// ---- 全天候后台补详情 ----
// 目的：源站归档页不给年份，只有详情页有真实发布时间；后台按"未抓/未补时间"的条目慢速补齐。
// 速率沿用 DETAIL_MIN_GAP_MS（默认 5 秒/条 ≈ 1.7 万条/天）；用户点开或翻页时 P0/P1 永远插到前面。
const BG_ENABLED = process.env.BG_BACKFILL !== '0';
const BG_BATCH = Number(process.env.BG_BATCH || 5);
const BG_INTERVAL_MS = Number(process.env.BG_INTERVAL_MS || 20000);
const BG_QUEUE_LIMIT = Number(process.env.BG_QUEUE_LIMIT || 6);
const bgTried = new Set();

function queryIds(query) {
  return sqlAsync(query, { maxBuffer: 1 << 24 })
    .then((s) => String(s || '').trim().split('\n').filter(Boolean).map((x) => Number(x.trim())).filter((n) => n > 0))
    .catch(() => []);
}

// 有待处理的重签（= 用户正在等播放）时，后台补详情让路，避免和用户抢源站
let pendingResigns = 0;

async function bgTick() {
  if (pendingResigns > 0) return;
  // 原来这里有一句 if (refreshing) return：refresh 每 20 分钟一轮、每轮跑 12~25 分钟，
  // 于是后台队列几乎永远进不来（实测 11.5 小时只完成 6 条）。排队本身不耗资源，
  // 真正的串行在 pumpDetail 里，所以这里不再拦。
  if (detailQueue.filter((q) => q.priority === 'bg').length >= BG_QUEUE_LIMIT) return;
  const ids = await queryIds(
    // tags is null：详情抓过但标签没抽过的老数据也要补一遍（标签是筛选/黑名单的依据）
    // content_text 的空串是「抓过、但这帖本来就没有正文」的完成标记，不再当缺失去重抓（否则永远排在队首）
    "select id from tool_web_post where published_full is null or content_text is null or tags is null "
    + 'order by cast(source_id as unsigned) desc limit ' + (BG_BATCH + bgTried.size));
  const dead = readDead();
  const pick = ids.filter((id) => !bgTried.has(id) && !dead.has(id)).slice(0, BG_BATCH);
  if (!pick.length) return;
  for (const id of pick) bgTried.add(id);
  enqueueDetail(pick, 'bg');
  console.log(new Date().toISOString(), 'bg enqueue', pick.join(','));
}

if (BG_ENABLED) {
  setTimeout(ratioTick, 60 * 1000);
  setInterval(ratioTick, 120 * 1000);
  if (process.env.DETAIL_DRAIN !== '0') {
    // 排空（每批 40 条、一次 launch）比 bgTick（每 20 秒 5 条、每条一次 launch）快 4 倍，
    // 两条路同时跑只会互相抢源站、也在队列上互相饿死，所以排空开启时 bgTick 让位。
    console.log('background detail backfill: 由 detail drain 接管');
  } else {
    setTimeout(bgTick, 5 * 1000);
    setInterval(bgTick, BG_INTERVAL_MS);
    console.log('background detail backfill on (batch ' + BG_BATCH + ' / ' + BG_INTERVAL_MS + 'ms)');
  }
}

// ---- 后台补详情「连续排空」（2026-11-21）----
// 实测口径：bgTick 每 20 秒只排 5 条，且每条一次 execFile = 一个新进程 + 新浏览器（约 2 秒），
// 加上 refresh 每 20 分钟一轮占掉 4~5 分钟，合起来只有 380 条/小时（待补 3.09 万 ≈ 3.4 天）。
// 这里换成「一次 launch 跑一批 40 条」：批内复用同一个浏览器，单条 5~7 秒 → 约 2.9 秒（实测 3 条 8.8 秒）。
// 不能靠并行提速：源站有 Cloudflare，多开会互相拖慢甚至被拦（见上方 pumpDetail 注释），
// 所以排空也只是「一个浏览器、一个页面、串行」，只是不再每行重启一次。
// 让路规则与 bgTick 一致：用户等重签 / refresh 在跑 / 前台队列没空 → 这一轮不排。
// 选行用「按 id 递增的游标」而不是 limit+offset：page 抓失败或页面本来没正文的行 content_text
// 永远是空，用不带游标的 limit 会永远卡在队头；游标保证每批都往前推进，走完一轮再从 0 开始。
// 风控闸门（宁可慢也不要被拦）：连续整批一条都没成 → 退避，累计 3 次停 30 分钟；
// 输出里出现 Cloudflare 挑战或 429/403 → 立刻停 2 小时并留日志。
const DRAIN_ENABLED = process.env.DETAIL_DRAIN !== '0';
// 40 条 × 老帖最坏 12 秒 goto + 12 秒重试 + 探活，会撞下面 pumpDetail 的 12 分钟外层超时、整批白跑；
// 24 条 × 最坏 24 秒 ≈ 10 分钟，留出余量。
const DRAIN_BATCH = Number(process.env.DETAIL_DRAIN_BATCH || 24);
const DRAIN_GAP_MS = Number(process.env.DETAIL_DRAIN_GAP_MS || 10000);
const DRAIN_BLOCK_STOP_MS = 2 * 60 * 60 * 1000;
// refresh-latest.mjs 在源站面（归档/分类/详情/精选）跑完后写这个文件，排空据此判断能否开工
const SOURCE_DONE_MARK = new URL('../../../data/refresh-source-done', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
// 源站已删的老帖（302 跳到宣传图）：refetch-images 会把 id 记进这个文件，排空与后台补详情跳过它们。
// 用文件而不是加列，原因见 HANDOFF §6.7：tool_web_post.published_ts 是 str_to_date 生成列，
// 表里有 published_at 脏值（'5-25'），ALTER TABLE 会整表重建、当场 ERROR 1411。
const DEAD_FILE = new URL('../../../data/detail-dead.json', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const readDead = () => {
  try { return new Set(JSON.parse(fs.readFileSync(DEAD_FILE, 'utf8'))); } catch (e) { return new Set(); }
};
let drainCursor = 0;      // 已排到哪个 id（重启归零，那一轮会重看一遍抓不动的行）
let drainUntil = 0;
let drainStrikes = 0;

// 选行条件与 bgTick 一致（正文 / 真实发布时间 / 标签 任一缺失都要补）：
// 排空接管后 bgTick 不再跑，所以这里必须覆盖它原来管的三种空洞。
const pickDrainIds = (after) => queryIds(
  "select id from tool_web_post where (published_full is null or content_text is null or tags is null) and id > " + Number(after)
  + ' order by id limit ' + DRAIN_BATCH);

async function drainOnce() {
  if (Date.now() < drainUntil) return;
  // 有一方在等源站就让路：用户重签 > 前台点开/翻页 > 定时 refresh 的「源站面」阶段。
  // refresh 的源站面 = 归档 / 分类 / 详情 / 精选（refresh-latest 在 featured 之后写标记文件），
  // 之后只剩 clean-noise / trim 这类纯库内维护，那段（实测 3~5 分钟）可以让排空同时跑。
  if (working || archiveBusy || detailQueue.length || pendingResigns > 0) return;
  if (refreshing) {
    let doneAt = 0;
    try { doneAt = Number(fs.readFileSync(SOURCE_DONE_MARK, 'utf8').trim()) || 0; } catch (e) { doneAt = 0; }
    if (Date.now() - doneAt > 15 * 60 * 1000) return;   // 标记是上一轮留下的 → 源站面还在跑
  }
  const dead = readDead();
  let ids = (await pickDrainIds(drainCursor)).filter((id) => !dead.has(id));
  let wrapped = false;
  if (!ids.length) {
    if (drainCursor === 0) {
      console.log(new Date().toISOString(), 'drain idle（正文已补全）');
      drainUntil = Date.now() + 10 * 60 * 1000;
      return;
    }
    // 一轮走完：回到开头，这一轮只为把新出现的空洞补上
    drainCursor = 0;
    wrapped = true;
    ids = (await pickDrainIds(0)).filter((id) => !dead.has(id));
    if (!ids.length) {
      console.log(new Date().toISOString(), 'drain idle（正文已补全）');
      drainUntil = Date.now() + 10 * 60 * 1000;
      return;
    }
  }
  const t0 = Date.now();
  let out = '';
  try {
    out = await new Promise((resolve, reject) => {
      execFile(process.execPath, [new URL('./refetch-images.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')], {
        cwd: new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'),
        windowsHide: true,
        env: {
          ...process.env,
          IDS: ids.join(','),
          HEADLESS: '1',
          // 活帖实测 1~2 秒就返回；25 秒的 upper bound 会让 40 条一批撞上外层 12 分钟超时，
          // 整批被 kill 后一条都没落库（历史上反复出现 drain fail 就是这个）。12 秒 + 每批封顶 5 个「打不开」足够。
          GOTO_TIMEOUT: '12000',
          EVAL_TIMEOUT: '15000',
          STALL_MS: '120000',
          // 这一档只要正文（图片由用户点开时补，和 bg 队列同口径）
          SKIP_IMAGES: '1',
        },
        encoding: 'utf8',
        maxBuffer: 1 << 26,
        timeout: 12 * 60 * 1000,
      }, (err, stdout) => (err ? reject(err) : resolve(stdout || '')));
    });
  } catch (e) {
    drainStrikes += 1;
    drainUntil = Date.now() + Math.min(30 * 60 * 1000, drainStrikes * 5 * 60 * 1000);
    console.log(new Date().toISOString(), 'drain fail', String((e && e.message) || e).slice(0, 120), '| strikes', drainStrikes);
    return;
  }
  const secs = Math.round((Date.now() - t0) / 1000);
  drainCursor = ids[ids.length - 1];
  if (/Just a moment|challenge-platform|cf-challenge/i.test(out)) {
    drainUntil = Date.now() + DRAIN_BLOCK_STOP_MS;
    console.log(new Date().toISOString(), 'drain 停 2 小时：输出里出现 Cloudflare 挑战');
    return;
  }
  const done = /done (\d+) \/ (\d+)/.exec(out);
  const ok = done ? Number(done[1]) : 0;
  const total = done ? Number(done[2]) : 0;
  if (!ok && total) {
    drainStrikes += 1;
    drainUntil = Date.now() + (drainStrikes >= 3 ? 30 * 60 * 1000 : drainStrikes * 5 * 60 * 1000);
    console.log(new Date().toISOString(), 'drain 空跑 ok 0/' + total, '| cursor', drainCursor, '| strikes', drainStrikes);
    return;
  }
  drainStrikes = 0;
  console.log(new Date().toISOString(), 'drain ok ' + ok + '/' + total, '|', secs + 's', '| cursor', drainCursor, wrapped ? '| 新一轮' : '');
}

let drainLastBeat = 0;
async function drainLoop() {
  if (DRAIN_ENABLED) {
    // 每 5 分钟留一行心跳，否则"排空一声不响"时无法区分是让路还是卡住了
    if (Date.now() - drainLastBeat > 5 * 60 * 1000) {
      drainLastBeat = Date.now();
      console.log(new Date().toISOString(), 'drain tick | refreshing', refreshing, '| working', working,
        '| queue', detailQueue.length, '| resigns', pendingResigns, '| cursor', drainCursor, '| until', drainUntil > Date.now() ? 'backoff' : '-');
    }
    try { await drainOnce(); } catch (e) { console.log('drain error', String((e && e.stack) || e).slice(0, 300)); }
  }
  setTimeout(drainLoop, DRAIN_GAP_MS);
}

if (DRAIN_ENABLED) {
  setTimeout(drainLoop, 90 * 1000);
  console.log('detail drain on (batch ' + DRAIN_BATCH + ' / gap ' + DRAIN_GAP_MS + 'ms)');
}

// ---- 视频入库（下载到本地）----
// 源站是 AES-128 的 HLS；ffmpeg 直接拉 m3u8 再 -c copy 存 mp4，不转码、不吃 CPU。
// 串行队列：一条 330MB 左右，同时下多个只会互相抢带宽。
const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const FFMPEG = process.env.FFMPEG || path.resolve(HERE, '../../../data/media/runtime/ffmpeg.exe');
const FFPROBE = process.env.FFPROBE || path.resolve(HERE, '../../../data/media/runtime/ffprobe.exe');
const WEBROOT = process.env.QTOOLS_WEBROOT || path.resolve(HERE, '../../../data/media/web');
let dlQueue = [];
let dlWorking = false;

const sqlOne = (query) => sqlAsync(query, { maxBuffer: 1 << 24 }).then((s) => String(s || '').trim()).catch(() => '');

/** 探测单段流的宽高比，形如 '720/1280'；失败返回空串 */
function probeRatio(url) {
  return new Promise((resolve) => {
    execFile(FFPROBE, ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height',
      '-of', 'csv=p=0', url], { encoding: 'utf8', timeout: 25000, maxBuffer: 1 << 20, windowsHide: true }, (err, stdout) => {
      if (err) return resolve('');
      const m = /^(\d+),(\d+)/.exec(String(stdout || '').trim());
      if (!m) return resolve('');
      const w = Number(m[1]); const h = Number(m[2]);
      return resolve(w > 0 && h > 0 ? w + '/' + h : '');
    });
  });
}

/**
 * 按段探测宽高比并写回 video_ratios（下标=段号，测不出存空串）。
 * 目的是让前端首帧就按正确比例撑播放器：重签/缓冲期间不再先按 16:9 再跳。
 * 一个都测不出来时不覆盖已有值，避免把好数据擦掉。
 */
async function probeAndSaveRatios(id, urls) {
  const pid = Number(id);
  if (!Number.isFinite(pid) || pid <= 0 || !Array.isArray(urls) || !urls.length) return;
  try {
    const ratios = [];
    for (const u of urls.slice(0, 20)) ratios.push(await probeRatio(u));
    if (!ratios.some((r) => r)) return;
    await sqlOne("update tool_web_post set video_ratios='" + JSON.stringify(ratios).replace(/'/g, "''") + "' where id=" + pid + ';');
    // 顺手把方向也定下来（列表页「横屏/竖屏」筛选用）；已有值不覆盖，避免抖
    const first = ratios.find((r) => /^\d+\/\d+$/.test(r));
    if (first) {
      const [w, h] = first.split('/').map(Number);
      await sqlOne("update tool_web_post set orientation='" + (w >= h ? 'l' : 'p') + "' where id=" + pid + ' and orientation is null;');
    }
    console.log(new Date().toISOString(), 'ratios', pid, ratios.join(' '));
  } catch (e) {
    console.log(new Date().toISOString(), 'ratios fail', pid, String((e && e.message) || e).slice(0, 90));
  }
}
function enqueueDownload(id, index) {
  const n = Number(id);
  const i = Number(index || 0);
  if (!Number.isFinite(n) || n <= 0) return false;
  const key = n + ':' + (Number.isFinite(i) && i > 0 ? i : 0);
  if (dlQueue.includes(key)) return false;
  dlQueue.push(key);
  setImmediate(pumpDownload);
  return true;
}

async function pumpDownload() {
  if (dlWorking) return;
  dlWorking = true;
  try {
    while (dlQueue.length) {
      while (archiveBusy) await sleep(1000);
      const key = dlQueue.shift();
      const [idStr, idxStr] = String(key).split(':');
      await downloadOne(Number(idStr), Number(idxStr) || 0);
    }
  } finally {
    dlWorking = false;
  }
}

async function downloadOne(id, index) {
  // 多段视频：index 0 存 video.mp4，其余存 v2.mp4 / v3.mp4 …（批量打包时按段命名）
  const row = await sqlOne('select source_id, ifnull(video_url,\'\'), ifnull(video_urls,\'\') from tool_web_post where id=' + id + ';');
  if (!row) return;
  const [sid, mainUrl, urlsJson] = row.split('\t');
  // index>0 时绝不退回主地址：主地址是第 1 段，退回去就会把第 1 段存成第 N 段
  // （f15913319102 与 base/-11 三份字节相同就是这么来的）
  let videoUrl = index > 0 ? '' : mainUrl;
  if (index > 0 && urlsJson) {
    try { videoUrl = (JSON.parse(urlsJson)[index] || '').replace(/\\\//g, '/'); } catch (e) { /* 保持空 */ }
  }
  // 库里存的段数可能比源站少（旧解析上限 10 段）：index 越界时先别判死刑，
  // 下面重签拿到完整列表后再决定有没有这一段
  if (!sid) {
    const why = '没有可下载地址';
    await sqlOne("update tool_web_post set download_state='error', download_error='" + why + "' where id=" + id + ';');
    return;
  }
  const dir = WEBROOT + '/' + sid;
  const out = index > 0 ? dir + '/v' + (index + 1) + '.mp4' : dir + '/video.mp4';
  try {
    fs.mkdirSync(dir, { recursive: true });
  } catch (e) { /* 已存在 */ }

  await sqlOne("update tool_web_post set download_state='running', download_percent=0, download_error=null where id=" + id + ';');
  const t0 = Date.now();
  try {
    // 地址经常已过期（源站 auth_key 有时效）：下载前先重签一次拿最新地址，
    // 否则 ffmpeg 直接 403，用户只看到"下载失败"却不知道是签名问题。
    try {
      const pageUrl = await sqlOne('select url from tool_web_post where id=' + id + ';');
      if (pageUrl) {
        const fresh = await resign(pageUrl);
        const list = Array.isArray(fresh) ? fresh : (fresh ? [fresh] : []);
        if (list.length && list.every((u) => typeof u === 'string' && u.indexOf('http') === 0)) {
          // 同样不许退回第 1 段：重签只解析到部分段时，第 N 段就是没有地址
          const picked = index > 0 ? list[index] : list[0];
          if (picked) videoUrl = picked;
          // 段数以库里为准：重签有时只解析到部分播放器，
          // 直接用短数组覆盖会把 12 段写成 10 段（实测过）。
          const prevCount = Number((await sqlOne('select ifnull(json_length(video_urls),0) from tool_web_post where id=' + id + ';')).trim()) || 0;
          // video_url 是「第 1 段」的专用列（selectVideoUrl 的 index=0 读它），
          // 不能写成正在下载的那一段：否则第 1 段播成第 N 段（实测 148950 第 1 段成了第 12 段）
          const mainHref = String(list[0]).replace(/'/g, "''");
          if (list.length >= prevCount) {
            await sqlOne("update tool_web_post set video_url='" + mainHref + "', video_urls='" + JSON.stringify(list).replace(/'/g, "''") + "', video_count=" + list.length + ", has_video=1 where id=" + id + ';');
          } else if (index === 0) {
            await sqlOne("update tool_web_post set video_url='" + mainHref + "' where id=" + id + ';');
          }
        }
      }
    } catch (e) { /* 重签失败就按原地址试 */ }
    // 第 N 段在源站已经没有了：宁可报错，也不能拿第 1 段冒充（会存出一份重复文件）
    if (!videoUrl) {
      throw new Error('第' + (index + 1) + '段没有可用地址（源站已删或重签只解析到部分段）');
    }
    // 时长用于算进度（ffprobe 很快，失败就按未知处理）
    let duration = 0;
    try {
      duration = await new Promise((resolve) => {
        execFile(FFPROBE, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', videoUrl],
          { encoding: 'utf8', timeout: 30000, windowsHide: true }, (err, stdout) => resolve(err ? 0 : Number(String(stdout || '').trim()) || 0));
      });
    } catch (e) { duration = 0; }

    await new Promise((resolve, reject) => {
      const args = [
        '-hide_banner', '-loglevel', 'error',
        '-headers', 'Referer: https://www.mrds66.com/\r\nUser-Agent: Mozilla/5.0\r\n',
        '-i', videoUrl, '-c', 'copy', '-bsf:a', 'aac_adtstoasc',
        '-progress', 'pipe:1', '-nostats', '-y', out
      ];
      const child = execFile(FFMPEG, args, { maxBuffer: 1 << 22, timeout: 60 * 60 * 1000, windowsHide: true }, (err) => (err ? reject(err) : resolve()));
      let lastPct = 0;
      if (child.stdout) {
        child.stdout.on('data', (buf) => {
          const m = String(buf).match(/out_time_ms=(\d+)/);
          if (!m || !duration) return;
          const pct = Math.min(99, Math.round((Number(m[1]) / 1e6 / duration) * 100));
          if (pct >= lastPct + 5) {
            lastPct = pct;
            sqlOne('update tool_web_post set download_percent=' + pct + ' where id=' + id + ';');
          }
        });
      }
    });

    const size = fs.existsSync(out) ? fs.statSync(out).size : 0;
    if (size < 1024 * 1024) throw new Error('文件过小，可能下载失败');
    await sqlOne("update tool_web_post set download_state='done', download_percent=100, video_file='/web/" + sid + "/video.mp4', downloaded_at=now() where id=" + id + ';');
    console.log(new Date().toISOString(), 'download', id, 'ok', (size / 1048576).toFixed(1) + 'MB', ((Date.now() - t0) / 1000).toFixed(0) + 's');
  } catch (e) {
    const msg = String((e && e.message) || e).slice(0, 200).replace(/'/g, ' ');
    await sqlOne("update tool_web_post set download_state='error', download_error='" + msg + "' where id=" + id + ';');
    console.log(new Date().toISOString(), 'download', id, 'fail', msg.slice(0, 120));
  }
}

// ---- 整帖归档（/archive）----
// 一条帖子的「归档」= 详情（正文/图集/封面/播放地址）+ 源页指纹 + 全部视频段落地。
// 全流程串行：同一时刻只跑一个归档，且不和 pumpDetail / pumpDownload 抢浏览器
// （源站有 Cloudflare，多路并发只会互相拖慢）。archiveBusy 期间那两个泵让路。
// 这 8 列是归档专用的（archive_state/percent/error/archived_at + source_etag/len/hash/checked_at），
// 与「单段下载」语义的 download_* 列互不混用。
const ARCHIVE_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';
const ARCHIVE_FETCH_MS = Number(process.env.ARCHIVE_FETCH_MS || 20000);
const ARCHIVE_DETAIL_MS = Number(process.env.ARCHIVE_DETAIL_MS || 10 * 60 * 1000);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let archiveQueue = [];
let archiveWorking = false;
let archiveBusy = false;
let archiveRunningId = 0;
let archiveDoneCount = 0;
let archiveFailedCount = 0;

/** SQL 字面量：截断到 200 字符（archive_error 是 varchar(255)），转义反斜杠与单引号 */
const sqlLit = (s) => String(s == null ? '' : s).slice(0, 200).replace(/\\/g, '\\\\').replace(/'/g, "''");

const sqlSet = async (id, cols) => {
  try {
    await sqlOne('update tool_web_post set ' + cols + ' where id=' + Number(id) + ';');
  } catch (e) {
    console.log(new Date().toISOString(), 'archive', id, 'db fail', String((e && e.message) || e).slice(0, 120));
  }
};

/**
 * 正文容器判据（/archive 与 /check 共用，不开浏览器）：
 * 选择器 + 打分与 refetch-images.mjs:332-335 完全一致 —— 候选是 class 里恰好含
 * post-content / post / article / entry-content 之一的元素，取「<p> 数 + 2×<img> 数」最大者。
 * 区别只是那边在浏览器 DOM 上做，这里在原始 HTML 上按标签配对做。
 */
const BODY_CLASSES = ['post-content', 'post', 'article', 'entry-content'];

function classList(attrs) {
  const m = /class\s*=\s*"([^"]*)"/i.exec(attrs) || /class\s*=\s*'([^']*)'/i.exec(attrs);
  return m ? m[1].split(/\s+/).filter(Boolean) : [];
}

/** 找正文容器：返回 { tag, inner, score }，找不到返回 null */
function findBodyBox(html) {
  const re = /<(\/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>/g;
  let best = null;
  let m;
  while ((m = re.exec(html)) !== null) {
    if (m[1]) continue;                       // 闭合标签跳过
    const tag = m[2].toLowerCase();
    if (!classList(m[3]).some((c) => BODY_CLASSES.includes(c))) continue;
    const innerStart = re.lastIndex;
    const open = new RegExp('<' + tag + '\\b', 'gi');
    const close = new RegExp('</' + tag + '\\s*>', 'gi');
    let depth = 1;
    let end = -1;
    let at = innerStart;
    while (depth > 0) {
      open.lastIndex = at;
      close.lastIndex = at;
      const o = open.exec(html);
      const c = close.exec(html);
      if (!c) break;                          // 标签没闭合：这个候选不可用
      if (o && o.index < c.index) { depth += 1; at = open.lastIndex; }
      else { depth -= 1; at = close.lastIndex; if (depth === 0) end = c.index; }
    }
    if (end < 0) continue;
    const inner = html.slice(innerStart, end);
    const score = (inner.match(/<p[\s>]/gi) || []).length + 2 * (inner.match(/<img[\s>]/gi) || []).length;
    if (!best || score > best.score) best = { tag, inner, score };
  }
  return best;
}

const decodeEntities = (s) => String(s)
  .replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&lt;/gi, '<')
  .replace(/&gt;/gi, '>').replace(/&quot;/gi, '"').replace(/&#39;|&apos;/gi, "'");

// 去噪口径同 refetch-images.mjs:336-340（正则 + 关键词收尾标记）
const BODY_NOISE = /在线约炮|同城约炮|广告|最新地址|自动回复|推荐浏览器|邮箱自动|热门大赛|重磅热瓜|温馨提示|重要提示|点击可下载|官方网址|分享每日大赛|复制最新网址|谨防假冒|永久有效|点我查看详情帖|大赛家族期待|欢迎加入每日大赛|官方Github|gitlab\.com/;
const BODY_PROMO = /^https?:\/\/|^www\.|gitlab\.com|aff\d+\.|\.org\/\?code=/;
const BODY_TAIL = /(关键词|关键字)\s*[:：]/;

/** 容器内按段落取正文（同 refetch-images.mjs:347-358：p/h2/h3/blockquote，去噪，遇「关键词：」收尾） */
function bodyTextOf(inner) {
  const out = [];
  const seen = new Set();
  const re = /<(p|h2|h3|blockquote)\b[^>]*>([\s\S]*?)<\/\1\s*>/gi;
  let m;
  while ((m = re.exec(inner)) !== null) {
    const t = decodeEntities(m[2].replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
    if (!t || t.length < 10 || BODY_NOISE.test(t) || BODY_PROMO.test(t) || seen.has(t)) continue;
    seen.add(t);
    out.push(t);
    if (BODY_TAIL.test(t)) break;             // 「关键词：#…」之后全是推荐位
  }
  return out.join('\n');
}

/** 兜底：容器抽不出来时，取页面里最长的那一段文本（按块级标签切段后取最长行） */
function longestTextRun(html) {
  const flat = html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<\/(p|div|li|h\d|article|section|td|tr)\s*>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, ' ');
  let best = '';
  for (const line of decodeEntities(flat).split('\n')) {
    const t = line.replace(/\s+/g, ' ').trim();
    if (t.length > best.length) best = t;
  }
  return best;
}

/**
 * 抓一次源页并算指纹：返回 { len, etag, hash, text }。
 * 用 fetch 而不是浏览器（/check 明确不许启浏览器），AF 一律用与爬虫一致的 UA，超时 <= 20s。
 * len = HTML 字节数；etag = ETag，没有就 Last-Modified，都没有则空串（落库时写 NULL）；
 * hash = 正文容器文本的 sha256 hex（小写 64 位）。
 */
async function fetchSourceFingerprint(url) {
  const resp = await fetch(url, {
    redirect: 'follow',
    headers: { 'user-agent': ARCHIVE_UA, 'accept-language': 'zh-CN,zh;q=0.9' },
    signal: AbortSignal.timeout(ARCHIVE_FETCH_MS),
  });
  if (!resp.ok) throw new Error('HTTP ' + resp.status);
  const buf = Buffer.from(await resp.arrayBuffer());
  const html = buf.toString('utf8');
  if (/Just a moment|challenge-platform|cf-challenge|__cf_chl/i.test(html)) throw new Error('源站返回 Cloudflare 挑战页');
  const box = findBodyBox(html);
  let text = box ? bodyTextOf(box.inner) : '';
  if (!text) text = longestTextRun(html);
  if (text.length < 20) throw new Error('页面没有正文（可能被拦或已删）');
  const etag = resp.headers.get('etag') || resp.headers.get('last-modified') || '';
  return { len: buf.length, etag, hash: createHash('sha256').update(text, 'utf8').digest('hex'), text };
}

function enqueueArchive(id) {
  const n = Number(id);
  if (!Number.isFinite(n) || n <= 0) return false;
  if (archiveRunningId === n || archiveQueue.includes(n)) return false;
  archiveQueue.push(n);
  // 和 enqueueDetail 同理：先让 /archive 的响应发出去，再跑（详情/下载会阻塞事件循环十几秒）
  setImmediate(() => {
    pumpArchive().catch((e) => console.log(new Date().toISOString(), 'archive pump error', String((e && e.stack) || e).slice(0, 300)));
  });
  return true;
}

async function pumpArchive() {
  if (archiveWorking) return;
  archiveWorking = true;
  try {
    while (archiveQueue.length) {
      const id = archiveQueue.shift();
      // 等现有抓取/下载让出浏览器（它们看到 archiveBusy 后不会再开始新任务）
      while (working || dlWorking) await sleep(1000);
      archiveBusy = true;
      archiveRunningId = id;
      try {
        await archiveOne(id);
      } catch (e) {
        archiveFailedCount += 1;
        console.log(new Date().toISOString(), 'archive', id, 'crash', String((e && e.stack) || e).slice(0, 200));
        await sqlSet(id, "archive_state='failed', archive_error='" + sqlLit('归档异常：' + String((e && e.message) || e)) + "'");
      } finally {
        archiveBusy = false;
        archiveRunningId = 0;
      }
    }
  } finally {
    archiveWorking = false;
  }
}

/** 归档一条帖子。任何异常都在这里兜住，绝不冒到进程顶层。 */
async function archiveOne(id) {
  const t0 = Date.now();
  const secs = () => ((Date.now() - t0) / 1000).toFixed(0) + 's';
  const say = (...parts) => console.log(new Date().toISOString(), 'archive', id, ...parts);
  await sqlSet(id, "archive_state='running', archive_percent=0, archive_error=null");
  say('start');

  // 1) 详情：与 pumpDetail 同一调法（execFile + ROWID），但 SKIP_IMAGES=0 —— 归档必须连图集一起收
  let detailOk = false;
  let detailLine = '';
  try {
    const out = await new Promise((resolve, reject) => {
      execFile(process.execPath, [new URL('./refetch-images.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')], {
        cwd: new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'),
        windowsHide: true,
        env: { ...process.env, ROWID: String(id), HEADLESS: '1', SKIP_IMAGES: '0' },
        encoding: 'utf8',
        maxBuffer: 1 << 26,
        timeout: ARCHIVE_DETAIL_MS,
      }, (err, stdout) => (err ? reject(err) : resolve(stdout || '')));
    });
    const m = /done (\d+) \/ (\d+)/.exec(out);
    detailOk = !!(m && Number(m[1]) > 0);
    detailLine = out.trim().split('\n').filter(Boolean).slice(-1)[0] || '';
  } catch (e) {
    detailLine = String((e && e.message) || e).slice(0, 120);
  }
  say('detail', detailOk ? 'ok' : 'fail', detailLine.slice(0, 120));
  if (!detailOk) {
    archiveFailedCount += 1;
    await sqlSet(id, "archive_state='failed', archive_error='" + sqlLit('详情抓取失败：' + detailLine) + "'");
    say('failed（详情）', secs());
    return;
  }

  // 2) 指纹：按 url 取一次 HTML（fetch，不开浏览器）
  const row = await sqlOne("select ifnull(url,''), ifnull(video_count,0), ifnull(source_id,'') from tool_web_post where id=" + id + ';');
  const [url, countStr, sid] = String(row).split('\t');
  if (url) {
    try {
      const fp = await fetchSourceFingerprint(url);
      await sqlSet(id, 'source_len=' + fp.len
        + ', source_etag=' + (fp.etag ? "'" + sqlLit(fp.etag) + "'" : 'NULL')
        + ", source_hash='" + fp.hash + "'");
      say('fingerprint', fp.len + 'B', fp.hash.slice(0, 12) + '…', fp.etag ? 'etag' : 'no-etag');
    } catch (e) {
      // 指纹失败不判死：详情与视频仍然可以归档，下次 /check 会再验
      say('fingerprint fail', String((e && e.message) || e).slice(0, 120));
    }
  }

  // 3) 视频：按 index 顺序逐段调已有的 downloadOne（它自己重签、落盘、更新 video_count）
  const count = Number(countStr) || 0;
  if (count <= 0) {
    archiveDoneCount += 1;
    await sqlSet(id, "archive_state='done', archive_percent=100, archive_error=null, archived_at=now(), source_checked_at=now()");
    say('done（无视频段）', secs());
    return;
  }
  if (!sid) {
    archiveFailedCount += 1;
    await sqlSet(id, "archive_state='failed', archive_error='缺少 source_id，无法定位视频目录'");
    say('failed（无 source_id）', secs());
    return;
  }
  let ok = 0;
  let firstFail = '';
  for (let i = 0; i < count; i += 1) {
    const segStart = Date.now();
    try {
      await downloadOne(id, i);
    } catch (e) {
      say('segment', (i + 1) + '/' + count, 'throw', String((e && e.message) || e).slice(0, 100));
    }
    // 成败以「文件这次真的被重写且够大」为准（downloadOne 内部自己 catch，返回值不可靠）
    const file = i > 0 ? path.join(WEBROOT, sid, 'v' + (i + 1) + '.mp4') : path.join(WEBROOT, sid, 'video.mp4');
    let good = false;
    try {
      const st = fs.statSync(file);
      good = st.size >= 1024 * 1024 && st.mtimeMs >= segStart - 1000;
    } catch (e) { good = false; }
    if (good) ok += 1;
    else if (!firstFail) {
      const err = String(await sqlOne("select ifnull(download_error,'') from tool_web_post where id=" + id + ';')).trim();
      firstFail = '第' + (i + 1) + '段失败' + (err ? '：' + err : '');
    }
    const pct = Math.round((ok / count) * 100);
    await sqlSet(id, 'archive_percent=' + pct);
    say('segment', (i + 1) + '/' + count, good ? 'ok' : 'fail', pct + '%');
  }

  // 4) 收尾
  if (ok === count) {
    archiveDoneCount += 1;
    await sqlSet(id, "archive_state='done', archive_percent=100, archive_error=null, archived_at=now(), source_checked_at=now()");
    say('done', ok + '/' + count, secs());
  } else if (ok > 0) {
    archiveFailedCount += 1;
    await sqlSet(id, "archive_state='partial', archive_error='" + sqlLit(firstFail) + "'");
    say('partial', ok + '/' + count, secs());
  } else {
    archiveFailedCount += 1;
    await sqlSet(id, "archive_state='failed', archive_error='" + sqlLit('全段失败：' + firstFail) + "'");
    say('failed（全段失败）', secs());
  }
}

/**
 * /check：取一次 HTML 算同样的指纹并与库里比。
 * 变了（库里 hash 非空且不同）→ archive_state='pending'，后端泵会重新归档。
 * 抓不到 / 被 CF 拦 → 直接返回 ok:false，不写 source_checked_at、不改 archive_state。
 */
async function checkSource(id) {
  const row = await sqlOne("select ifnull(url,''), ifnull(source_hash,'') from tool_web_post where id=" + Number(id) + ';');
  if (!String(row).length) return { ok: false, error: 'no such id' };
  const [url, prevHash] = String(row).split('\t');
  if (!url) return { ok: false, error: 'no url' };
  let fp;
  try {
    fp = await fetchSourceFingerprint(url);
  } catch (e) {
    return { ok: false, error: String((e && e.message) || e).slice(0, 200) };
  }
  const changed = !!prevHash && prevHash !== fp.hash;
  await sqlSet(id, 'source_checked_at=now()' + (changed ? ", archive_state='pending'" : ''));
  console.log(new Date().toISOString(), 'archive check', id, changed ? 'changed' : 'same', fp.len + 'B', fp.hash.slice(0, 12) + '…');
  return { ok: true, changed, hash: fp.hash, len: fp.len, checkedAt: new Date().toISOString() };
}

/**
 * 存量补比例：库里已有播放地址但还没 video_ratios 的帖子，直接对存的 m3u8 跑 ffprobe。
 * 地址多半已过期（403）→ 测不出就跳过，等它下次重签时自然补上。
 */
let ratioTried = new Set();
async function ratioTick() {
  if (pendingResigns > 0) return;                       // 用户在等播放时让路
  const rows = await queryIds(
    "select id from tool_web_post where video_ratios is null and video_urls is not null and json_length(video_urls) > 0 "
    + 'order by cast(source_id as unsigned) desc limit ' + (3 + ratioTried.size));
  const pick = rows.filter((id) => !ratioTried.has(id)).slice(0, 3);
  if (!pick.length) return;
  for (const id of pick) ratioTried.add(id);
  if (ratioTried.size > 5000) ratioTried = new Set();
  for (const id of pick) {
    const raw = await sqlOne('select ifnull(video_urls,\'\') from tool_web_post where id=' + id + ';');
    let urls = [];
    try { urls = JSON.parse(raw) || []; } catch (e) { urls = []; }
    if (!Array.isArray(urls) || !urls.length) continue;
    await probeAndSaveRatios(id, urls);
  }
}

const server = http.createServer((req, res) => {
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
  // ---- 整帖归档 ----
  const pathname = String(req.url || '').split('?')[0];
  if (req.method === 'POST' && pathname === '/archive') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let payload = {};
      try { payload = JSON.parse(body || '{}'); } catch (e) { return send(400, { error: 'bad json' }); }
      const id = Number(payload.id);
      if (!Number.isFinite(id) || id <= 0) return send(400, { error: 'missing id' });
      enqueueArchive(id);
      console.log(new Date().toISOString(), 'archive', id, 'queued | queue', archiveQueue.length, '| working', archiveWorking);
      return send(200, { ok: true, queued: true });
    });
    return undefined;
  }
  if (req.method === 'GET' && pathname === '/archive/status') {
    return send(200, { pending: archiveQueue.length, working: archiveWorking, done: archiveDoneCount, failed: archiveFailedCount });
  }
  if (req.method === 'POST' && pathname === '/check') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let payload = {};
      try { payload = JSON.parse(body || '{}'); } catch (e) { return send(400, { error: 'bad json' }); }
      const id = Number(payload.id);
      if (!Number.isFinite(id) || id <= 0) return send(400, { error: 'missing id' });
      // 同步语义（不开浏览器）：等算完再应答；fetch 自带 ARCHIVE_FETCH_MS 超时
      checkSource(id)
        .then((out) => send(out.ok ? 200 : 502, out))
        .catch((e) => send(502, { ok: false, error: String((e && e.message) || e).slice(0, 200) }));
    });
    return undefined;
  }
  if (req.method === 'POST' && req.url.startsWith('/download')) {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let payload = {};
      try { payload = JSON.parse(body || '{}'); } catch (e) { payload = {}; }
      const queued = enqueueDownload(payload.id, Number(payload.index || 0));
      return send(200, { queued, pending: dlQueue.length, working: dlWorking });
    });
    return undefined;
  }
  if (req.method === 'GET' && req.url.startsWith('/download/status')) {
    return send(200, { pending: dlQueue.length, working: dlWorking });
  }
  if (req.method === 'POST' && req.url.startsWith('/prefetch')) {
    console.log(new Date().toISOString(), 'prefetch hit', req.url);
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let payload = {};
      try { payload = JSON.parse(body || '{}'); } catch (e) { return send(400, { error: 'bad json' }); }
      const ids = Array.isArray(payload.ids) ? payload.ids : [];
      let added = 0;
      try {
        added = enqueueDetail(ids, payload.priority === 'open' ? 'open' : 'view');
      } catch (e) {
        console.log(new Date().toISOString(), 'enqueue error', String((e && e.stack) || e).slice(0, 400));
      }
      return send(200, { queued: added, pending: detailQueue.length, working });
    });
    return undefined;
  }
  if (req.method === 'GET' && req.url.startsWith('/prefetch/status')) {
    return send(200, { pending: detailQueue.length, working, open: detailQueue.filter((q) => q.priority === 'open').length });
  }
  if (req.method === 'POST' && req.url.startsWith('/refresh')) {
    // 手动触发一轮拉取（后台跑，立刻返回）
    // quick=1 只抓最新一层（约 20 秒），页面刷新时用；否则跑完整轮次（约 4 分钟）
    const quick = /[?&]quick=1/.test(req.url);
    setTimeout(() => runRefresh(quick), 0);
    return send(202, { started: true, quick });
  }
  if (req.method !== 'POST' || !req.url.startsWith('/resign')) return send(404, { error: 'not found' });
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    let payload = {};
    try { payload = JSON.parse(body || '{}'); } catch (e) { return send(400, { error: 'bad json' }); }
    if (!payload.url) return send(400, { error: 'missing url' });
    // 串行化：一个浏览器实例同时开太多标签会互相拖慢/被风控
    pendingResigns += 1;
    queue = queue.then(async () => {
      const t0 = Date.now();
      try {
        const cacheBefore = resignCache.has(payload.url) && (Date.now() - resignCache.get(payload.url).at) < RESIGN_CACHE_MS;
        const fresh = await resign(payload.url);
        const list = Array.isArray(fresh) ? fresh : (fresh ? [fresh] : []);
        const cached = cacheBefore;
        console.log(new Date().toISOString(), 'resign', payload.url.slice(-24), '->', list.length ? list.length + ' 条' : 'empty', (Date.now() - t0) + 'ms', cached ? '(cache)' : '');
        if (!list.length) return send(502, { error: 'no m3u8 found' });
        // 同时给 url（兼容旧调用）和 urls（多视频按 index 取）
        send(200, { url: list[0], urls: list });
        // 顺序探测宽高比并入库（后台跑，不占重签响应时间）
        if (payload.id) setImmediate(() => { probeAndSaveRatios(payload.id, list); });
      } catch (e) {
        console.log(new Date().toISOString(), 'resign failed', String(e.message).slice(0, 120));
        send(502, { error: String(e.message).slice(0, 200) });
      } finally {
        pendingResigns = Math.max(0, pendingResigns - 1);
      }
    });
  });
});

server.listen(PORT, '127.0.0.1', () => console.log('mrd-s resign sidecar on http://127.0.0.1:' + PORT));
process.on('SIGTERM', async () => { if (browser) await browser.close().catch(() => {}); process.exit(0); });