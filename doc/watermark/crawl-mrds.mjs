/**
 * crawl-mrds.mjs —— 「每日大赛」站点镜像：列表 + 详情抓取（P1，先跑通链路）
 *
 * 输入：BASE（站点根），PAGES（抓前几页，默认 2）
 * 输出：<out>.json —— [{ sourceId, url, title, category, tags[], author, publishedAt, cover, hasPlayer }]
 *
 * 为什么用真浏览器：站点有 Cloudflare 类防护，纯 HTTP 客户端首页就被拒（实测）。
 * 浏览器在屏外运行，用户看不到窗口；同一实例串行访问，避免打爆源站。
 */
import { chromium } from 'playwright-core';
import fs from 'node:fs';

const BASE = process.env.BASE || 'https://able.akmevtunk.cc';
const PAGES = Number(process.env.PAGES || 2);
const OUT = process.env.OUT || 'mrds-crawl.json';
const CANDIDATES = ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'];
const CHROME = CANDIDATES.find(p => { try { return fs.existsSync(p); } catch { return false; } });
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch({ executablePath: CHROME, headless: false, args: ['--mute-audio', '--window-position=-32000,-32000', '--window-size=1000,800'] });
const ctx = await browser.newContext({ userAgent: UA, viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
const page = await ctx.newPage();

/** 列表页 → 该页的 post 链接 */
async function listPage(n) {
  const url = n <= 1 ? BASE + '/' : BASE + '/page/' + n + '/';
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(1200);
  const links = await page.evaluate(() => {
    const set = new Set();
    document.querySelectorAll('a[href*="/archives/"]').forEach((a) => {
      const href = a.getAttribute('href') || '';
      const m = /\/archives\/(\d+)\/?/.exec(href);
      if (m) set.add(m[1]);
    });
    return Array.from(set);
  });
  return { url, links };
}

/** 详情页 → 元数据 */
async function detail(id) {
  const url = BASE + '/archives/' + id + '/';
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(900);
  return page.evaluate((linkUrl) => {
    const txt = (el) => (el ? (el.textContent || '').trim() : '');
    const meta = (name) => {
      const el = document.querySelector('meta[property="' + name + '"], meta[name="' + name + '"]');
      return el ? (el.getAttribute('content') || '').trim() : '';
    };
    const crumbs = Array.from(document.querySelectorAll('a[href*="/category/"], .breadcrumb a, .crumbs a')).map(txt).filter(Boolean);
    const tags = Array.from(document.querySelectorAll('a[href*="/tag/"], a[rel="tag"]')).map(txt).filter(Boolean);
    const time = document.querySelector('time');
    const cover = meta('og:image') || (document.querySelector('.post-content img, .entry-content img, article img') || {}).src || '';
    return {
      url: linkUrl,
      title: txt(document.querySelector('h1')) || document.title,
      category: crumbs.length ? crumbs[crumbs.length - 1] : '',
      tags: tags.slice(0, 8),
      author: txt(document.querySelector('.author, .post-author')),
      publishedAt: (time && (time.getAttribute('datetime') || txt(time))) || meta('article:published_time'),
      cover,
      hasPlayer: !!document.querySelector('video, .dplayer, #player, [id*="player" i]')
    };
  }, url);
}

const out = [];
for (let n = 1; n <= PAGES; n++) {
  const { links } = await listPage(n);
  console.log('[list] page', n, '->', links.length, 'items');
  for (const id of links) {
    try {
      const d = await detail(id);
      out.push({ sourceId: id, ...d });
      console.log('  [ok]', id, d.title.slice(0, 40), '| tags:', d.tags.join(','), '| player:', d.hasPlayer);
    } catch (e) {
      console.log('  [fail]', id, e.message);
    }
    await sleep(400);
  }
  await sleep(600);
}
await browser.close();
fs.writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log('saved', out.length, 'items ->', OUT);
