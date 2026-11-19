import http from 'node:http';
import fs from 'node:fs';
import { execFile, execFileSync } from 'node:child_process';
import { chromium } from 'playwright-core';

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
  return out.slice(0, 10);
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
        env: {
          ...process.env,
          MYSQL: process.env.MYSQL || process.env.QTOOLS_MYSQL || 'E:/111/project/QTools/tools/mysql/bin/mysql.exe',
          MYSQL_PWD: process.env.MYSQL_PWD || 'dqq1107.',
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
        env: { ...process.env, HEADLESS: '1', ...(base ? { BASE: base } : {}) },
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

function queryIds(sql) {
  return new Promise((resolve) => {
    execFile(process.env.QTOOLS_MYSQL || 'E:/111/project/QTools/tools/mysql/bin/mysql.exe',
      ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','-N','-B','-e', sql, 'qtools'],
      { encoding: 'utf8', maxBuffer: 1 << 24 },
      (err, stdout) => resolve(err ? [] : String(stdout || '').trim().split('\n').filter(Boolean).map((x) => Number(x.trim())).filter((n) => n > 0)));
  });
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
    "select id from tool_web_post where published_full is null or content_text is null or content_text='' or tags is null "
    + 'order by cast(source_id as unsigned) desc limit ' + (BG_BATCH + bgTried.size));
  const pick = ids.filter((id) => !bgTried.has(id)).slice(0, BG_BATCH);
  if (!pick.length) return;
  for (const id of pick) bgTried.add(id);
  enqueueDetail(pick, 'bg');
  console.log(new Date().toISOString(), 'bg enqueue', pick.join(','));
}

if (BG_ENABLED) {
  setTimeout(bgTick, 5 * 1000);
  setInterval(bgTick, BG_INTERVAL_MS);
  console.log('background detail backfill on (batch ' + BG_BATCH + ' / ' + BG_INTERVAL_MS + 'ms)');
}

// ---- 视频入库（下载到本地）----
// 源站是 AES-128 的 HLS；ffmpeg 直接拉 m3u8 再 -c copy 存 mp4，不转码、不吃 CPU。
// 串行队列：一条 330MB 左右，同时下多个只会互相抢带宽。
const FFMPEG = process.env.FFMPEG || 'E:/111/project/QTools/data/media/runtime/ffmpeg.exe';
const FFPROBE = process.env.FFPROBE || 'E:/111/project/QTools/data/media/runtime/ffprobe.exe';
const WEBROOT = process.env.QTOOLS_WEBROOT || 'E:/111/project/QTools/data/media/web';
let dlQueue = [];
let dlWorking = false;

const sqlOne = (sql) => new Promise((resolve) => {
  execFile(process.env.QTOOLS_MYSQL || 'E:/111/project/QTools/tools/mysql/bin/mysql.exe',
    ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','--default-character-set=utf8mb4','-N','-B','-e', "set session sql_mode=''; " + sql, 'qtools'],
    { encoding: 'utf8', maxBuffer: 1 << 24 },
    (err, stdout) => resolve(err ? '' : String(stdout || '').trim()));
});

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
  let videoUrl = mainUrl;
  if (index > 0 && urlsJson) {
    try { videoUrl = (JSON.parse(urlsJson)[index] || '').replace(/\\\//g, '/') || mainUrl; } catch (e) { /* 保持主地址 */ }
  }
  if (!sid || !videoUrl) {
    await sqlOne("update tool_web_post set download_state='error', download_error='没有可下载地址' where id=" + id + ';');
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
          videoUrl = list[index] || list[0] || videoUrl;
          // 段数以库里为准：重签有时只解析到部分播放器，
          // 直接用短数组覆盖会把 12 段写成 10 段（实测过）。
          const prevCount = Number((await sqlOne('select ifnull(json_length(video_urls),0) from tool_web_post where id=' + id + ';')).trim()) || 0;
          if (list.length >= prevCount) {
            await sqlOne("update tool_web_post set video_url='" + String(videoUrl).replace(/'/g, "''") + "', video_urls='" + JSON.stringify(list).replace(/'/g, "''") + "', video_count=" + list.length + ", has_video=1 where id=" + id + ';');
          } else {
            await sqlOne("update tool_web_post set video_url='" + String(videoUrl).replace(/'/g, "''") + "' where id=" + id + ';');
          }
        }
      }
    } catch (e) { /* 重签失败就按原地址试 */ }
    // 时长用于算进度（ffprobe 很快，失败就按未知处理）
    let duration = 0;
    try {
      duration = await new Promise((resolve) => {
        execFile(FFPROBE, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', videoUrl],
          { encoding: 'utf8', timeout: 30000 }, (err, stdout) => resolve(err ? 0 : Number(String(stdout || '').trim()) || 0));
      });
    } catch (e) { duration = 0; }

    await new Promise((resolve, reject) => {
      const args = [
        '-hide_banner', '-loglevel', 'error',
        '-headers', 'Referer: https://www.mrds66.com/\r\nUser-Agent: Mozilla/5.0\r\n',
        '-i', videoUrl, '-c', 'copy', '-bsf:a', 'aac_adtstoasc',
        '-progress', 'pipe:1', '-nostats', '-y', out
      ];
      const child = execFile(FFMPEG, args, { maxBuffer: 1 << 22, timeout: 60 * 60 * 1000 }, (err) => (err ? reject(err) : resolve()));
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

const server = http.createServer((req, res) => {
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
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