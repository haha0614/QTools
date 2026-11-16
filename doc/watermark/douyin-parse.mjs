/**
 * 抖音无水印取数侧车（v2：常驻页面 + 页面内 fetch）
 *
 * 演进说明：
 *  v1 每条链接都导航作品页、旁听 aweme/detail 响应 —— 7~8 秒，且每个请求新建 context 会弹窗。
 *  v2 常驻一个页面（只打开一次 douyin.com），每条链接在页面内 fetch 接口：
 *      抖音自己的请求拦截器会补 a_bogus / uifid / x-secsdk 等签名（纯 HTTP 自签已实测失败：
 *      a_bogus 能算，但服务端把签名与浏览器运行时绑死，会返回 403/空体）。
 *      实测单条 140~213ms，无导航、无新窗口。
 *
 * 关键事实：video.play_addr 是播放流（平台不加水印），video.download_addr 才是带 logo+抖音号的下载流。
 *
 * 用法：
 *   node douyin-parse.mjs --serve 7788     常驻服务
 *   node douyin-parse.mjs <shareUrl>       一次性解析
 */
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import http from 'node:http';

const CHROME_CANDIDATES = [
  process.env.WATERMARK_CHROME,
  'C:\\\\Program Files\\\\Google\\\\Chrome\\\\Application\\\\chrome.exe',
  'C:\\\\Program Files (x86)\\\\Google\\\\Chrome\\\\Application\\\\chrome.exe',
  'C:\\\\Program Files (x86)\\\\Microsoft\\\\Edge\\\\Application\\\\msedge.exe'
].filter(Boolean);

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const HOME = 'https://www.douyin.com/';
const ID_PATTERN = /(?:video|note|share\/video|share\/note|slides)\/(\d{6,})/;
const BARE_ID = /^\d{6,}$/;

let browserPromise = null;
let pagePromise = null;

function chromePath() {
  for (const p of CHROME_CANDIDATES) {
    try { if (fs.existsSync(p)) return p; } catch {}
  }
  throw new Error('找不到 Chrome，可用环境变量 WATERMARK_CHROME 指定');
}

/** 浏览器只启一次；窗口在屏幕外，用户看不到 */
async function getBrowser() {
  if (!browserPromise) {
    browserPromise = chromium.launch({
      executablePath: chromePath(),
      // 必须 headful：无头会被识别成机器人，接口直接给空数据
      headless: false,
      args: [
        '--disable-blink-features=AutomationControlled',
        '--no-first-run',
        '--no-default-browser-check',
        '--mute-audio',
        '--window-position=-32000,-32000',
        '--window-size=800,600'
      ]
    }).then(b => {
      b.on('disconnected', () => { browserPromise = null; pagePromise = null; });
      return b;
    });
  }
  return browserPromise;
}

/** 常驻页面：只导航一次，之后所有解析都在它里面发 fetch */
async function getPage() {
  if (!pagePromise) {
    pagePromise = (async () => {
      const browser = await getBrowser();
      const context = await browser.newContext({ userAgent: UA, viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
      const page = await context.newPage();
      // 首页用于建立会话 cookie（UIFID/ttwid/msToken），只做一次
      await page.goto(HOME, { waitUntil: 'domcontentloaded', timeout: 60000 });
      await page.waitForTimeout(3000);
      console.log('[watermark] 常驻页面就绪（后续解析只发 fetch，不再导航）');
      return page;
    })().catch(e => { pagePromise = null; throw e; });
  }
  return pagePromise;
}

/** 从整段分享文案里抽地址 */
function normalizeUrl(input) {
  const text = String(input || '').trim();
  const matched = /https?:\/\/[^\s\u4e00-\u9fa5]+/.exec(text);
  let url = matched ? matched[0] : text;
  url = url.replace(/[，。、；：！？）】》>,.;:]+$/, '');
  if (!/^https?:\/\//i.test(url) && /douyin\.com/i.test(url)) url = 'https://' + url;
  return url;
}

/** 解析作品 ID：长链直接抠，短链用一次轻量 HTTP 跟随跳转（302 不需要签名） */
async function resolveVideoId(target) {
  const direct = ID_PATTERN.exec(target);
  if (direct) return direct[1];
  if (BARE_ID.test(target)) return target;
  try {
    const res = await fetch(target, { redirect: 'follow', headers: { 'User-Agent': UA } });
    const finalUrl = res.url || '';
    const m = ID_PATTERN.exec(finalUrl);
    return m ? m[1] : null;
  } catch {
    return null;
  }
}

function pickUrl(node) {
  if (!node) return null;
  if (Array.isArray(node.url_list)) {
    for (const u of node.url_list) if (u) return u;
  }
  return null;
}

/** 按短边判定档位，横屏竖屏通用 */
function prettyLabel(width, height) {
  const shortSide = Math.min(Number(width || 0), Number(height || 0));
  const name = shortSide >= 2000 ? '4K'
    : shortSide >= 1400 ? '1440p'
    : shortSide >= 1000 ? '1080p'
    : shortSide >= 700 ? '720p'
    : '540p';
  return width && height ? name + ' ' + width + 'x' + height : name;
}

/** 只取 play_addr 家族（无水印），绝不取 download_addr */
function buildResult(item) {
  const video = item.video || {};
  const items = [];
  const seen = new Set();
  const gears = [];
  for (const br of video.bit_rate || []) {
    const url = pickUrl(br.play_addr);
    if (!url || seen.has(url)) continue;
    seen.add(url);
    const width = Number(br.play_addr?.width || 0);
    const height = Number(br.play_addr?.height || 0);
    const kbps = Math.round(Number(br.bit_rate || 0) / 1000);
    gears.push({ url, kbps, label: prettyLabel(width, height) + (kbps ? ' · ' + kbps + ' kbps' : '') });
  }
  gears.sort((a, b) => b.kbps - a.kbps);
  if (!gears.length) {
    const url = pickUrl(video.play_addr);
    if (url) gears.push({ url, kbps: 0, label: '默认清晰度' });
  }
  for (const gear of gears) items.push({ type: 'video', label: gear.label, url: gear.url });

  for (const img of item.images || []) {
    const url = pickUrl(img);
    if (url) items.push({ type: 'image', label: null, url });
  }

  return {
    platform: '抖音',
    videoId: String(item.aweme_id || ''),
    title: item.desc || null,
    author: item.author?.nickname || null,
    cover: pickUrl(video.cover),
    duration: video.duration ? Math.round(video.duration / 1000) : null,
    width: video.width || null,
    height: video.height || null,
    items
  };
}

/** 在页面上下文里请求详情接口，由站点自己的拦截器签名 */
async function fetchDetail(awemeId) {
  const page = await getPage();
  return page.evaluate(async (id) => {
    const qs = new URLSearchParams({
      device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web', aweme_id: id,
      pc_client_type: '1', version_code: '170400', version_name: '17.4.0', cookie_enabled: 'true',
      screen_width: '1920', screen_height: '1080', browser_language: 'zh-CN', browser_platform: 'Win32',
      browser_name: 'Chrome', browser_version: '131.0.0.0', browser_online: 'true',
      engine_name: 'Blink', engine_version: '131.0.0.0', os_name: 'Windows', os_version: '10',
      cpu_core_num: '12', device_memory: '8', platform: 'PC', downlink: '10',
      effective_type: '4g', round_trip_time: '50'
    });
    try {
      const res = await fetch('/aweme/v1/web/aweme/detail/?' + qs.toString(), {
        headers: { accept: 'application/json, text/plain, */*' },
        credentials: 'include'
      });
      const text = await res.text();
      return { status: res.status, text };
    } catch (e) {
      return { status: 0, text: '', error: String(e) };
    }
  }, awemeId);
}

export async function parse(shareUrl) {
  const started = Date.now();
  const target = normalizeUrl(shareUrl);
  if (!/^https?:\/\//i.test(target)) {
    return { ok: false, ms: Date.now() - started, error: '没在粘贴内容里识别到链接' };
  }

  const videoId = await resolveVideoId(target);
  if (!videoId) {
    return { ok: false, ms: Date.now() - started, error: '没在链接里识别到作品 ID，请确认是单条作品链接' };
  }

  let lastError = '未捕获到作品数据';
  for (let attempt = 0; attempt < 2; attempt++) {
    const r = await fetchDetail(videoId);
    if (r.status === 200 && r.text) {
      try {
        const json = JSON.parse(r.text);
        if (json.aweme_detail) {
          const data = buildResult(json.aweme_detail);
          if (data.items.length) return { ok: true, ms: Date.now() - started, data };
          lastError = '取到作品但没有可用地址（可能是直播或付费内容）';
        } else {
          lastError = '接口未返回作品数据（可能已删除或仅登录可见）';
        }
      } catch {
        lastError = '接口返回了非 JSON 内容';
      }
    } else if (r.status === 403) {
      lastError = '被风控拦截（403），稍后重试';
    } else if (r.status === 0) {
      lastError = '页面内请求失败：' + (r.error || '未知原因');
    }
    // 页面可能失活，重建一次
    pagePromise = null;
    try { await getPage(); } catch {}
  }
  return { ok: false, ms: Date.now() - started, error: lastError };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', chunk => { raw += chunk; if (raw.length > 1e6) req.destroy(); });
    req.on('end', () => resolve(raw));
    req.on('error', reject);
  });
}

function serve(port) {
  http.createServer(async (req, res) => {
    const send = (code, obj) => {
      const body = JSON.stringify(obj);
      res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
      res.end(body);
    };
    if (req.method === 'GET' && req.url === '/health') return send(200, { ok: true, warm: !!pagePromise });
    if (req.method !== 'POST' || req.url !== '/parse') return send(404, { ok: false, error: 'not found' });

    let url;
    try {
      const body = JSON.parse(await readBody(req) || '{}');
      url = (body.url || '').trim();
    } catch { return send(400, { ok: false, error: '请求体不是合法 JSON' }); }
    if (!url) return send(400, { ok: false, error: 'url 不能为空' });

    const t0 = Date.now();
    try {
      const result = await parse(url);
      console.log('[parse] ' + (result.ok ? 'OK' : 'FAIL') + ' ' + (Date.now() - t0) + 'ms ' + url.slice(0, 60));
      return send(result.ok ? 200 : 422, result);
    } catch (e) {
      console.error('[parse] ERR ' + e);
      return send(500, { ok: false, error: String(e) });
    }
  }).listen(port, '127.0.0.1', () => console.log('[watermark] listening on 127.0.0.1:' + port));
}

const argv = process.argv.slice(2);
if (argv[0] === '--serve') {
  serve(Number(argv[1] || 7788));
} else if (argv[0]) {
  const result = await parse(argv[0]);
  console.log(JSON.stringify(result, null, 1).slice(0, 1200));
  process.exit(result.ok ? 0 : 1);
} else {
  console.log('usage: node douyin-parse.mjs --serve [port] | node douyin-parse.mjs <shareUrl>');
}
