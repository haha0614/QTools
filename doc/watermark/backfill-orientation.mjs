import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
const MYSQL = process.env.QTOOLS_MYSQL || process.env.MYSQL || 'D:/project/QTools-All/tools/mysql/bin/mysql.exe';
const ROOT = process.env.QTOOLS_WEBROOT || 'D:/project/QTools-All/data/media/web';
const DB_PWD = process.env.MYSQL_PWD || process.env.QTOOLS_MYSQL_PWD || 'dqq1107.';
const db = (sql) => execFileSync(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-p' + DB_PWD,'--default-character-set=utf8mb4','-N','-B','-e', "set session sql_mode=''; " + sql, 'qtools'], { encoding: 'utf8', maxBuffer: 1 << 28 });

function jpegSize(file) {
  const b = fs.readFileSync(file);
  let i = 2;
  while (i + 9 < b.length) {
    if (b[i] !== 0xff) { i += 1; continue; }
    const m = b[i + 1];
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
      return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
    }
    const len = b.readUInt16BE(i + 2);
    i += 2 + len;
  }
  return null;
}

// 1) 优先用库里的 ffprobe 比例
const ratioRows = db("select id, ifnull(video_ratios,'') from tool_web_post where video_ratios is not null").split(/\r?\n/).filter(Boolean);
const l1 = []; const p1 = [];
for (const line of ratioRows) {
  const [id, raw] = line.split('\t');
  let arr = [];
  try { arr = JSON.parse(raw) || []; } catch (e) { arr = []; }
  const first = arr.find((x) => typeof x === 'string' && /^\d+\/\d+$/.test(x));
  if (!first) continue;
  const [w, h] = first.split('/').map(Number);
  (w >= h ? l1 : p1).push(id);
}

// 2) 没有比例的，用图集第一张（本地已有 p1.jpg 的）
const imgRows = db("select id, source_id from tool_web_post where video_ratios is null and image_count > 0").split(/\r?\n/).filter(Boolean);
const l2 = []; const p2 = [];
for (const line of imgRows) {
  const [id, sid] = line.split('\t');
  const file = path.join(ROOT, String(sid), 'p1.jpg');
  if (!fs.existsSync(file)) continue;
  let d = null;
  try { d = jpegSize(file); } catch (e) { continue; }
  if (!d) continue;
  (d.w >= d.h ? l2 : p2).push(id);
}

const chunk = (a, n) => a.reduce((acc, x, i) => (i % n ? acc[acc.length - 1].push(x) : acc.push([x]), acc), []);
for (const [ids, val] of [[l1, 'l'], [p1, 'p'], [l2, 'l'], [p2, 'p']]) {
  for (const part of chunk(ids, 800)) {
    db("update tool_web_post set orientation='" + val + "' where id in (" + part.join(',') + ');');
  }
}
console.log('按视频比例: 横', l1.length, '竖', p1.length);
console.log('按图集第一张: 横', l2.length, '竖', p2.length);
console.log('结果:', db("select orientation, count(*) from tool_web_post group by orientation;").trim().replace(/\n/g, ' | '));