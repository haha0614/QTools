#!/usr/bin/env node
/**
 * 方向回填：给 tool_web_post.orientation 写 'l'（横屏）/ 'p'（竖屏）。
 *
 * 判定顺序：
 *   1) 库里的 video_ratios（ffprobe 探到的每段宽高比，形如 ["720/1280"]）
 *   2) 图集第一张 data/media/web/<source_id>/p1.jpg 的尺寸（sharp 读元数据）
 * 两个来源都判不出来的留 NULL（前端把它们留在「全部」里）。
 *
 * 用法：
 *   node backfill-orientation.mjs          # 写库
 *   node backfill-orientation.mjs --dry    # 只统计不写库
 *
 * 环境变量：QTOOLS_MYSQL / QTOOLS_WEBROOT / MYSQL_PWD（都有默认值，默认是本机便携布局）
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';

const MYSQL = process.env.QTOOLS_MYSQL || process.env.MYSQL || 'D:/project/QTools-All/tools/mysql/bin/mysql.exe';
const WEBROOT = process.env.QTOOLS_WEBROOT || 'D:/project/QTools-All/data/media/web';
const DB_PWD = process.env.MYSQL_PWD || process.env.QTOOLS_MYSQL_PWD || 'dqq1107.';
const DRY = process.argv.includes('--dry');

const db = (sql) => execFileSync(MYSQL, [
  '-h127.0.0.1', '-P3307', '-uroot', '-p' + DB_PWD, '--default-character-set=utf8mb4',
  '-N', '-B', '-e', "set session sql_mode=''; " + sql, 'qtools',
], { encoding: 'utf8', maxBuffer: 1 << 28 });

const rows = (out) => out.split(/\r?\n/).filter(Boolean).map((l) => l.split('\t'));

// 1) 优先用 ffprobe 探到的第一段比例
const l1 = [];
const p1 = [];
for (const [id, raw] of rows(db("select id, ifnull(video_ratios,'') from tool_web_post where video_ratios is not null"))) {
  let arr = [];
  try { arr = JSON.parse(raw) || []; } catch (e) { arr = []; }
  const first = arr.find((x) => typeof x === 'string' && /^\d+\/\d+$/.test(x));
  if (!first) continue;
  const [w, h] = first.split('/').map(Number);
  (w >= h ? l1 : p1).push(id);
}

// 2) 没有比例的，用图集第一张（sharp 读宽高，失败就跳过）
const l2 = [];
const p2 = [];
let unreadable = 0;
for (const [id, sid] of rows(db("select id, source_id from tool_web_post where video_ratios is null and image_count > 0"))) {
  const file = path.join(WEBROOT, String(sid), 'p1.jpg');
  if (!fs.existsSync(file)) continue;
  let meta = null;
  try { meta = await sharp(file).metadata(); } catch (e) { unreadable += 1; continue; }
  if (!meta || !meta.width || !meta.height) { unreadable += 1; continue; }
  (meta.width >= meta.height ? l2 : p2).push(id);
}

const chunk = (a, n) => a.reduce((acc, x, i) => (i % n ? acc[acc.length - 1].push(x) : acc.push([x]), acc), []);
if (!DRY) {
  for (const [ids, val] of [[l1, 'l'], [p1, 'p'], [l2, 'l'], [p2, 'p']]) {
    for (const part of chunk(ids, 800)) {
      db("update tool_web_post set orientation='" + val + "' where id in (" + part.join(',') + ');');
    }
  }
}

console.log('按视频比例: 横 ' + l1.length + ' 竖 ' + p1.length);
console.log('按图集首图: 横 ' + l2.length + ' 竖 ' + p2.length + (unreadable ? '（读不出尺寸 ' + unreadable + '）' : ''));
console.log('本次可判定合计: 横 ' + (l1.length + l2.length) + ' 竖 ' + (p1.length + p2.length));
if (DRY) console.log('--dry：未写库');
else console.log('库内分布:', db('select orientation, count(*) from tool_web_post group by orientation;').trim().replace(/\n/g, ' | '));
