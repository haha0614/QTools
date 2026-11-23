#!/usr/bin/env node
/**
 * 把「有封面路径却没有封面文件」的帖子改用同目录的 p1.jpg（正文第一张图）。
 *
 * 背景（2026-11-23）：crawl-feeds/crawl-archives/refetch-covers 三个脚本把 WEBROOT 默认写成
 * D:/project/QTools-All/data/media/web，B 机（E 盘）上新帖子的 cover.jpg 全写到 D 盘旧树里，
 * 站上 /web/<id>/cover.jpg 一律 404 —— 卡片全是破图。脚本已改成跟着脚本自身定位（<根>/data/media/web），
 * 这个脚本负责把历史数据里那条断掉的引用接上：
 *   - 目录里有 p1.jpg → cover 改成 /web/<源站id>/p1.jpg（真图，能显示）
 *   - 两样都没有 → 不动（前端 onerror 会退成占位文字，不制造破图）
 *
 * 用法：MYSQL_PWD=... node fix-cover-refs.mjs          真跑
 *      DRY=1 ... node fix-cover-refs.mjs               只看会改多少
 * 幂等：跑第二遍会改 0 行。
 */
import fs from 'node:fs';
import path from 'node:path';
import { sql } from '../lib/db.mjs';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const WEBROOT = process.env.QTOOLS_WEBROOT || path.resolve(HERE, '../../../data/media/web');
const DRY = process.env.DRY === '1';

const rows = sql("select source_id, cover from tool_web_post where cover like '/web/%';")
  .trim().split(/\r?\n/).filter(Boolean).map((l) => l.split('\t').map((s) => s.trim())); // mysql -B 的行尾是 \r

const fix = [];
let ok = 0, noP1 = 0;
for (const [sid, cover] of rows) {
  if (!/\/cover\.jpg$/.test(cover)) continue; // 已经改指 p1.jpg 的行不再计入
  const dir = path.join(WEBROOT, sid);
  if (fs.existsSync(path.join(dir, 'cover.jpg'))) { ok++; continue; }
  if (fs.existsSync(path.join(dir, 'p1.jpg'))) fix.push(sid);
  else noP1++;
}
console.log('WEBROOT', WEBROOT);
console.log('扫到有封面路径的', rows.length, '| 封面文件在', ok, '| 可改用 p1', fix.length, '| 两样都没有', noP1);

if (!DRY) {
  let done = 0;
  for (let i = 0; i < fix.length; i += 500) {
    const chunk = fix.slice(i, i + 500);
    const inList = chunk.map((s) => "'" + s.replace(/'/g, "''") + "'").join(',');
    sql("update tool_web_post set cover = concat('/web/', source_id, '/p1.jpg')"
      + " where cover = concat('/web/', source_id, '/cover.jpg') and source_id in (" + inList + ");");
    done += chunk.length;
  }
  console.log('已改写', done, '条');
} else {
  console.log('DRY：将改写', fix.length, '条');
}

// 第二段：cover 还空着、但本地已经有 cover.jpg 的行（封面早就下下来了，只是没写回库里）。
// 成因：crawl-feeds 用 insert ignore 入库，已存在的行不会被更新；而封面文件是这一轮才补下的。
const blank = sql('select source_id from tool_web_post where cover is null or cover=\'\';')
  .trim().split(/\r?\n/).filter(Boolean).map((s) => s.trim());
const fill = [];
for (const sid of blank) {
  if (fs.existsSync(path.join(WEBROOT, sid, 'cover.jpg'))) fill.push(sid);
}
console.log('封面为空的', blank.length, '| 已有 cover.jpg 可回填', fill.length);
if (DRY) {
  console.log('DRY：将回填', fill.length, '条');
} else {
  let filled = 0;
  for (let i = 0; i < fill.length; i += 500) {
    const chunk = fill.slice(i, i + 500);
    const inList = chunk.map((s) => "'" + s.replace(/'/g, "''") + "'").join(',');
    sql("update tool_web_post set cover = concat('/web/', source_id, '/cover.jpg')"
      + " where (cover is null or cover='') and source_id in (" + inList + ");");
    filled += chunk.length;
  }
  console.log('已回填', filled, '条');
}
