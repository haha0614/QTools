import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import { sql } from '../lib/db.mjs';

const C = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find((p) => { try { return fs.existsSync(p); } catch { return false; } });
const WEBROOT = process.env.QTOOLS_WEBROOT || 'D:/project/QTools-All/data/media/web';
const BASE = (process.env.BASE || 'https://able.nwfuhxhj.cc').replace(/\/$/, '') + '/';
const DRY = process.env.DRY === '1';

// 源站列表卡片的封面不是 <img>，而是 .post-card 里 .blog-background 的内联 background-image
// （base64 data URI，和正文图集是不同的图 —— 正文 p1 是「第一张内容图」，封面是编辑另选的）。
// 所以封面只能从列表页按 /archives/<id>/ 反查。
const wanted = new Set(sql('select source_id from tool_web_post;').trim().split(/\r?\n/).map((s) => s.trim()).filter(Boolean));
console.log('want covers for', wanted.size, 'posts');

const b = await chromium.launch({ executablePath: C, headless: false, args: ['--mute-audio','--window-position=-32000,-32000','--window-size=1300,950','--disable-blink-features=AutomationControlled'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();

const found = new Map();
for (let page = 1; page <= 10 && found.size < wanted.size; page++) {
  const url = page === 1 ? BASE : BASE + 'page/' + page + '/';
  try {
    await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await p.waitForTimeout(2500);
    for (let i = 0; i < 6; i++) { await p.mouse.wheel(0, 1400); await p.waitForTimeout(350); }
    await p.waitForTimeout(800);
  } catch (e) { console.log('  page', page, 'fail', String(e.message).slice(0, 60)); continue; }
  const cards = await p.evaluate(() => {
    const out = [];
    for (const a of document.querySelectorAll('a[href*="/archives/"]')) {
      const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/);
      if (!m) continue;
      const card = a.closest('.post-card') || a.parentElement;
      const bg = card && card.querySelector('.blog-background');
      if (!bg) continue;
      const bgv = getComputedStyle(bg).backgroundImage || '';
      const k = bgv.indexOf('base64,');
      if (k < 0) continue;
      out.push({ id: m[1], data: bgv.slice(k + 7, bgv.length - 2) });
    }
    return out;
  });
  let added = 0;
  for (const c of cards) {
    if (!wanted.has(c.id) || found.has(c.id)) continue;
    found.set(c.id, c.data);
    added++;
  }
  console.log('  page', page, 'cards', cards.length, 'new', added, 'total', found.size);
}
await b.close();

let written = 0;
for (const [sid, b64] of found) {
  const buf = Buffer.from(b64, 'base64');
  if (buf.length < 5000) continue;
  const dir = path.join(WEBROOT, sid);
  fs.mkdirSync(dir, { recursive: true });
  if (!DRY) fs.writeFileSync(path.join(dir, 'cover.jpg'), buf);
  if (!DRY) sql("update tool_web_post set cover='/web/" + sid + "/cover.jpg' where source_id='" + sid + "';");
  written++;
}
const missing = [...wanted].filter((s) => !found.has(s));
console.log('covers written', written, '/', wanted.size, '| missing', missing.join(',') || '-');