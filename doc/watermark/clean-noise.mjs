import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
const MYSQL = process.env.QTOOLS_MYSQL || 'E:/111/project/QTools/tools/mysql/bin/mysql.exe';
const NOISE_FILE = process.env.QTOOLS_NOISE || 'E:/111/project/QTools/QTools/doc/watermark/noise-lines.json';
const DRY = process.env.DRY !== '0';
// df=5 的那几条是同一系列帖子的「关键词：…」行，属于正文内容，不能当样板；
// 真正的站点样板 df 是 26 / 560 这个量级，所以门槛取 10。
const MIN_DF = Number(process.env.MIN_DF || 10);
const db = (sql) => execFileSync(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','--default-character-set=utf8mb4','-N','-B','-e', sql, 'qtools'], { encoding: 'utf8', maxBuffer: 1 << 28 });
const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");

// 这一列里存的是''分隔的正文段落；站点在每个帖子末尾塞了同样的推荐位标题，
// 用文档频率(出现在多少个不同帖子里)判定样板最稳，不依赖具体文案。
// mysql -B 批处理模式会把值里的换行转义成字面量 \n，要还原回来
const unescape = (s) => s.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\\\/g, '\\');
const raw = db("select id, content_text from tool_web_post where content_text is not null and content_text <> '';");
const rows = raw.split('\n').filter(Boolean).map((line) => {
  const k = line.indexOf('\t');
  return { id: line.slice(0, k), text: unescape(line.slice(k + 1)) };
});
const df = new Map();
for (const r of rows) {
  const seenIn = new Set();
  for (const p of r.text.split('\n')) {
    const t = p.trim();
    if (t.length < 4 || t.length > 120) continue;
    if (seenIn.has(t)) continue;
    seenIn.add(t);
    df.set(t, (df.get(t) || 0) + 1);
  }
}
// 已知样板清单永久生效：清理过一次之后 df 会掉到阈值以下，
// 只靠文档频率会让个别残留（比如某条正文中间夹着的推荐位标题）永远清不掉。
let known = [];
try {
  known = JSON.parse(fs.readFileSync(NOISE_FILE, 'utf8')) || [];
} catch (e) {
  known = [];
}
const byDf = [...df.entries()].filter(([, n]) => n >= MIN_DF);
const knownSet = new Set(known);
const boiler = byDf.slice();
for (const k of known) {
  if (!boiler.some(([t]) => t === k)) boiler.push([k, knownSet.has(k) ? 1 : 0]);
}
boiler.sort((a, b) => b[1] - a[1]);
console.log('posts', rows.length, '| distinct lines', df.size, '| boilerplate(>=' + MIN_DF + ')', boiler.length);
for (const [t, n] of boiler.slice(0, 12)) console.log('  df=' + n, t.slice(0, 50));

// 把本次识别出的样板行写盘：爬虫在抽取阶段就能直接丢掉，不必等事后清理
try {
  fs.writeFileSync(NOISE_FILE, JSON.stringify(boiler.map(([t]) => t), null, 1));
} catch (e) {
  console.log('写噪音清单失败', String(e.message).slice(0, 80));
}

let touched = 0, removed = 0, blocksRemoved = 0;
const boilerSet = new Set(boiler.map(([t]) => t));
for (const r of rows) {
  const lines = r.text.split('\n');
  const kept = lines.filter((t) => !boilerSet.has(t.trim()));
  const removedHere = lines.length - kept.length;

  // 详情页渲染的是 content（blocks JSON），正文文本列只是搜索用；两处都要清
  let blocks = [];
  try {
    const rawJson = db("select replace(replace(content, '\\\\', '\\\\\\\\'), '''', '''''') from tool_web_post where id=" + r.id + ';').trim();
    blocks = JSON.parse(rawJson.replace(/''/g, "'")) || [];
  } catch (e) { blocks = []; }
  const keptBlocks = blocks.filter((b) => !(b && b.type === 'text' && boilerSet.has(String(b.text || '').trim())));
  const blocksRemovedHere = blocks.length - keptBlocks.length;

  if (!removedHere && !blocksRemovedHere) continue;
  touched += 1; removed += removedHere; blocksRemoved += blocksRemovedHere;
  if (!DRY) {
    const json = JSON.stringify(keptBlocks).replace(/\\/g, '\\\\').replace(/'/g, "''");
    db("update tool_web_post set content_text='" + esc(kept.join('\n')) + "', content='" + json + "' where id=" + r.id + ';');
  }
}
console.log(DRY ? 'DRY' : 'APPLIED', '| posts touched', touched, '| text lines removed', removed, '| blocks removed', blocksRemoved);