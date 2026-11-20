import { sql } from '../lib/db.mjs';

const DRY = process.env.DRY === '1';
const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");

// 归档页只给「11-15」这种月日，没有年份。归档列表本身是按发布时间倒序的，
// 所以可以用「已确认日期」的条目当锚点，给中间的条目推算年份：
//   按 source_rank（抓取时的归档顺序）走一遍，未定日期的条目沿用上一个锚点的年份，
//   并且不允许比锚点更新（归档是倒序，越过锚点说明推算偏了）。
const recs = sql('select id, source_rank, published_ts, published_at from tool_web_post order by source_rank asc, id asc;')
  // mysql -B 批处理把 NULL 打成字面量 "NULL"，必须归一化，否则每行都被当成锚点
  .split('\n').filter(Boolean).map((l) => { const [id, rank, ts, at] = l.split('\t'); const norm = (v) => (!v || v === 'NULL' ? '' : v); return { id, rank: Number(rank) || 0, ts: norm(ts), at: norm(at) }; });
console.log('rows', recs.length);

let anchor = null; // {y, m, d}
const updates = [];
let inferred = 0, skipped = 0;
for (const r of recs) {
  if (r.ts && r.ts !== '0000-00-00') {
    const [y, m, d] = r.ts.split('-').map(Number);
    anchor = { y, m, d };
    continue;
  }
  const mm = (r.at || '').match(/^(\d{1,2})\s*月\s*(\d{1,2})\s*日/);
  if (!mm || !anchor) { skipped += 1; continue; }
  const m = Number(mm[1]); const d = Number(mm[2]);
  // 归档倒序：未定日期的条目应该不比锚点更新；比锚点新就贴到锚点当天（避免跨年误判）
  let y = anchor.y;
  const cand = y * 10000 + m * 100 + d;
  const anc = anchor.y * 10000 + anchor.m * 100 + anchor.d;
  let useM = m, useD = d;
  if (cand > anc) { useM = anchor.m; useD = anchor.d; }
  updates.push({ id: r.id, d: y + ' 年 ' + useM + ' 月 ' + useD + ' 日' });
  inferred += 1;
}
console.log('inferred', inferred, 'skipped', skipped);
if (!DRY && updates.length) {
  // 每个 db() 都是独立 mysql 会话，临时表不跨会话；直接按块拼一条 case 更新
  let done = 0;
  for (let i = 0; i < updates.length; i += 300) {
    const chunk = updates.slice(i, i + 300);
    const stmt = 'update tool_web_post set published_at = case id '
      + chunk.map((x) => 'when ' + x.id + " then '" + esc(x.d) + "'").join(' ')
      + ' end, date_inferred = 1 where id in (' + chunk.map((x) => x.id).join(',') + ');';
    sql(stmt);
    done += chunk.length;
  }
  console.log('updated', done);
}
console.log(sql("select date_format(published_ts,'%Y-%m') m, count(*) c, sum(date_inferred) inf from tool_web_post where published_ts > '1970-01-01' group by m order by m desc limit 12;"));