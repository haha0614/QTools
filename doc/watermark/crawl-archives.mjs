import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import { sql } from '../lib/db.mjs';

const exe = ['C:/Program Files/Google/Chrome/Application/chrome.exe'].find((p) => { try { return fs.existsSync(p); } catch { return false; } });
const WEBROOT = process.env.QTOOLS_WEBROOT || 'D:/project/QTools-All/data/media/web';
// 源站域名会轮换（见 gitlab.com/mrds/mrds 的 README，或跑 sync-source-host.mjs）
const BASE = process.env.BASE || 'https://able.nwfuhxhj.cc';
const FROM = Number(process.env.FROM || 1);
const TO = Number(process.env.TO || 5);
const WITH_COVER = process.env.WITH_COVER !== '0';
const DRY = process.env.DRY === '1';
// 归档条目只有「月 日」，生成列 published_ts 在严格模式下会报 1411（sql_mode 已由 lib/db.mjs 统一放宽）
const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");

const b = await chromium.launch({ executablePath: exe, headless: process.env.HEADLESS === '1', args: ['--mute-audio','--window-position=-32000,-32000','--window-size=1300,950'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();

let total = 0, inserted = 0, covers = 0;
let rank = Number(sql('select coalesce(max(source_rank),0) from tool_web_post;').trim()) || 0;

for (let page = FROM; page <= TO; page++) {
  const url = BASE + '/archives/page/' + page + '/';
  try { await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 }); } catch (e) { console.log(' page', page, 'nav fail'); continue; }
  await p.waitForTimeout(1500);
  let prev = 0;
  for (let i = 0; i < 12; i++) {
    await p.mouse.wheel(0, 3000); await p.waitForTimeout(250);
    const n = await p.evaluate(() => document.querySelectorAll('a[href*="/archives/"]').length);
    if (n === prev && i > 4) break;
    prev = n;
  }
  // 归档页是纯文字列表：div.brick > div.archives > div.archive-title > a，文本形如 "11-15 标题"
  // （只有月日，没有年份），也没有封面 —— 封面只能等详情抓取时用正文首图补。
  const cards = await p.evaluate(() => {
    const out = [];
    for (const a of document.querySelectorAll('.archives .archive-title a, .archive-title a, a[href*="/archives/"]')) {
      const m = (a.getAttribute('href') || '').match(/\/archives\/(\d+)\/?/);
      if (!m) continue;
      const text = (a.innerText || '').replace(/\s+/g, ' ').trim();
      const dm = text.match(/^(\d{1,2})-(\d{1,2})\s*(.+)$/);
      if (!dm) continue;
      out.push({ id: m[1], title: dm[3].slice(0, 300), mm: Number(dm[1]), dd: Number(dm[2]) });
    }
    return out;
  });
  let fresh = 0;
  const values = [];
  for (const c of cards) {
    total += 1; rank += 1;
    // 归档页只给「月-日」，不给年份 —— 硬推年份会造出假数据，所以只存月日，
    // 等详情抓取时再用详情页上的完整日期覆盖（published_ts 生成列此时为 NULL，排在有日期的内容之后）。
    const dateText = c.mm + ' 月 ' + c.dd + ' 日';
    // 每页攒成一条多值 insert：单页 ~290 条，逐条 spawn mysql 会让 5 万条多花 40 分钟
    values.push("('mrds','" + esc(c.id) + "','" + esc(c.title) + "','" + esc(dateText) + "','" + esc(BASE + '/archives/' + c.id + '/') + "'," + rank + ')');
    fresh += 1;
  }
  if (!DRY && values.length) {
    for (let i = 0; i < values.length; i += 200) {
      // on duplicate key update：重跑时把 published_at 改回源站原文（月-日），并清掉推算标记
      sql('insert into tool_web_post (source, source_id, title, published_at, url, source_rank) values '
        + values.slice(i, i + 200).join(',')
        // 只补"没有年份"的行：详情抓来的真日期（含「年」）绝不能被归档页的月日覆盖
        + " on duplicate key update published_at = if(published_at like '%年%', published_at, values(published_at)), date_inferred = 0;");
    }
  }
  inserted += fresh;
  console.log(' page', page, '| cards', cards.length, '| inserted', fresh, '| covers', covers);
}
await b.close();
console.log('done | cards', total, '| inserted(ignore)', inserted, '| covers', covers);
console.log(sql('select count(*) from tool_web_post;'));