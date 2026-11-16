import { chromium } from 'playwright-core';
import { execFileSync } from 'node:child_process';
const C = ['C:/Program Files/Google/Chrome/Application/chrome.exe'].find((p) => { try { return require('node:fs').existsSync(p); } catch { return false; } });
const MYSQL = 'E:/111/project/QTools/tools/mysql/bin/mysql.exe';
const BASE = process.env.BASE || 'https://able.heermtat.cc/';
const db = (sql) => execFileSync(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','-N','-B','-e', sql, 'qtools'], { encoding: 'utf8', maxBuffer: 1 << 28 });
const fs = await import('node:fs');
const exe = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find((p) => { try { return fs.existsSync(p); } catch { return false; } });
const b = await chromium.launch({ executablePath: exe, headless: false, args: ['--mute-audio','--window-position=-32000,-32000','--window-size=1300,950'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
let rank = 0, updated = 0;
const seen = new Set();
for (let page = 1; page <= 12; page++) {
  const url = page === 1 ? BASE : BASE + 'page/' + page + '/';
  try { await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 }); } catch (e) { console.log(' page', page, 'fail'); continue; }
  await p.waitForTimeout(2500);
  for (let i = 0; i < 8; i++) { await p.mouse.wheel(0, 1400); await p.waitForTimeout(300); }
  const ids = await p.evaluate(() => [...document.querySelectorAll('a[href*="/archives/"]')].map((a) => (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/)).filter(Boolean).map((m) => m[1]).filter((v, i, arr) => arr.indexOf(v) === i));
  // 同一批条目在后续页会重复出现（源站翻页到底后不返回空页），
  // 位次必须「第一次见到时定死」，否则后面的页会把老条目重新排到后面。
  const fresh = ids.filter((sid) => !seen.has(sid));
  console.log(' page', page, 'ids', ids.length, 'new', fresh.length);
  if (!fresh.length) break;
  const sql = fresh.map((sid) => { seen.add(sid); rank += 1; return "update tool_web_post set source_rank=" + rank + " where source_id='" + sid + "';"; }).join('\n');
  db(sql);
  updated += fresh.length;
}
await b.close();
console.log('ranked', updated);
console.log(db('select source_rank, source_id, left(title,18) from tool_web_post order by source_rank asc limit 8;'));