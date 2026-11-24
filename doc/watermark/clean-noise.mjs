import { sql, execStdin } from '../lib/db.mjs';
import fs from 'node:fs';
import path from 'node:path';
const NOISE_FILE = process.env.QTOOLS_NOISE || path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), 'noise-lines.json');
const DRY = process.env.DRY !== '0';
// df=5 的那几条是同一系列帖子的「关键词：…」行，属于正文内容，不能当样板；
// 真正的站点样板 df 是 26 / 560 这个量级，所以门槛取 10。
const MIN_DF = Number(process.env.MIN_DF || 10);
const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");
const t0 = Date.now();

// 这一列里存的是 '' 分隔的正文段落；站点在每个帖子末尾塞了同样的推荐位标题，
// 用文档频率(出现在多少个不同帖子里)判定样板最稳，不依赖具体文案。
//
// 一次取完 id + 两列，且都用 to_base64：mysql -B 会把值里的 \n、\t、\\ 转义，
// JSON 正文里本来就有反斜杠，解转义还原会出错；base64 没有这个问题。
// 原来的写法是「每帖再起一个 mysql 查一次 content」——49.8k 帖 × 约 120ms ≈ 100 分钟，
// 每轮都撞满 refresh-latest 的 10 分钟 timeout，等于一直在空转（HANDOFF §23）。
// 坑：MySQL 的 to_base64 每 76 字符插一个换行，-N -B 按行切会把它切成碎块、列错位
// （表现为解出来的正文后半截是乱码、JSON.parse 全部失败）→ 必须 replace(..., char(10), '')
const raw = sql("select id, replace(to_base64(cast(content_text as binary)), char(10), ''), replace(to_base64(ifnull(content, '')), char(10), '') from tool_web_post where content_text is not null and content_text <> '';");
const b64 = (s) => Buffer.from(s || '', 'base64').toString('utf8');
const posts = raw.split('\n').filter(Boolean).map((line) => {
  const [id, t, c] = line.split('\t');
  return { id, text: b64(t), json: b64(c) };
});
const df = new Map();
for (const r of posts) {
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
console.log('posts', posts.length, '| distinct lines', df.size, '| boilerplate(>=' + MIN_DF + ')', boiler.length, '| 读取 ' + (Date.now() - t0) + 'ms');
for (const [t, n] of boiler.slice(0, 12)) console.log('  df=' + n, t.slice(0, 50));

// 把本次识别出的样板行写盘：爬虫在抽取阶段就能直接丢掉，不必等事后清理
try {
  fs.writeFileSync(NOISE_FILE, JSON.stringify(boiler.map(([t]) => t), null, 1));
} catch (e) {
  console.log('写噪音清单失败', String(e.message).slice(0, 80));
}

let touched = 0, removed = 0, blocksRemoved = 0;
const boilerSet = new Set(boiler.map(([t]) => t));
const stmts = [];
const ids = [];
const BATCH = Number(process.env.BATCH || 200);
for (const r of posts) {
  const lines = r.text.split('\n');
  const kept = lines.filter((t) => !boilerSet.has(t.trim()));
  const removedHere = lines.length - kept.length;

  // 详情页渲染的是 content（blocks JSON），正文文本列只是搜索用；两处都要清
  let blocks = [];
  try { blocks = JSON.parse(r.json) || []; } catch (e) { blocks = []; }
  const keptBlocks = blocks.filter((b) => !(b && b.type === 'text' && boilerSet.has(String(b.text || '').trim())));
  const blocksRemovedHere = blocks.length - keptBlocks.length;

  if (!removedHere && !blocksRemovedHere) continue;
  touched += 1; removed += removedHere; blocksRemoved += blocksRemovedHere;
  if (DRY) continue;
  const json = JSON.stringify(keptBlocks).replace(/\\/g, '\\\\').replace(/'/g, "''");
  stmts.push("update tool_web_post set content_text='" + esc(kept.join('\n')) + "', content='" + json + "' where id=" + r.id + ';');
  ids.push(r.id);
}
// 写库也走 stdin 批量：200 条一次 mysql 进程，而不是 200 次。
// 改的是正文，先留底：同一 id 的原文进 tool_web_post_content_bak（可反复执行，只追加）
if (!DRY && stmts.length) {
  const ddl = "create table if not exists tool_web_post_content_bak (id bigint not null, content longtext null, content_text longtext null, backed_up_at datetime not null, key idx_bak_id (id)) engine=InnoDB default charset=utf8mb4 comment='clean-noise 改正文前的原文留底'";
  await execStdin(ddl + ";");
  for (let i = 0; i < ids.length; i += BATCH) {
    const list = ids.slice(i, i + BATCH).join(',');
    await execStdin("insert into tool_web_post_content_bak (id, content, content_text, backed_up_at) select id, content, content_text, now() from tool_web_post where id in (" + list + ");");
  }
  console.log('已留底', ids.length, '条原文到 tool_web_post_content_bak');
  for (let i = 0; i < stmts.length; i += BATCH) await execStdin(stmts.slice(i, i + BATCH).join('\n'));
}
console.log(DRY ? 'DRY' : 'APPLIED', '| posts touched', touched, '| text lines removed', removed, '| blocks removed', blocksRemoved, '| 总计 ' + (Date.now() - t0) + 'ms');
