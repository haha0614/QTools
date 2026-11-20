import { sql } from '../lib/db.mjs';
const DRY = process.env.DRY !== '0';
const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");
const unescape = (s) => s.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\\\/g, '\\');

/**
 * 正文结构清理（按内容形态，不依赖文档频率）
 *   1. 「关键词：/关键字：」是源站正文的结尾标记，其后一律丢弃
 *   2. 纯链接行、带推广位参数的链接行
 *   3. 已知推广话术（点我查看详情帖 / 大赛家族期待 / 官方 Github …）
 */
const TAIL_MARK = /(关键词|关键字)\s*[:：]/;
const PROMO_LINK = /^https?:\/\/|^www\.|gitlab\.com|aff\d+\.|\.org\/\?code=/;
const PROMO_TEXT = /点我查看详情帖|大赛家族期待|欢迎加入每日大赛|官方Github|gitlab\.com|永久域名|备用网址|发布页/;

const isNoise = (p) => PROMO_LINK.test(p) || PROMO_TEXT.test(p);

function trimParagraphs(paras) {
  const out = [];
  for (const p of paras) {
    const t = String(p || '').trim();
    if (!t) continue;
    if (isNoise(t)) continue;
    out.push(t);
    if (TAIL_MARK.test(t)) break; // 结尾标记：后面全是推广
  }
  return out;
}

const raw = sql("select id, replace(content_text, char(10), '<BR>'), content from tool_web_post where content_text is not null and content_text <> '';");
const posts = raw.split('\n').filter(Boolean).map((line) => {
  const parts = line.split('\t');
  return { id: parts[0], text: unescape(parts[1] || ''), content: parts[2] || '' };
});

let changed = 0, dropped = 0;
for (const r of posts) {
  const paras = r.text.split('<BR>');
  const kept = trimParagraphs(paras);
  if (kept.length === paras.filter((p) => String(p).trim()).length) continue;
  dropped += paras.filter((p) => String(p).trim()).length - kept.length;
  changed += 1;
  if (DRY) continue;
  let blocks = [];
  try { blocks = JSON.parse(r.content || '[]'); } catch (e) { blocks = []; }
  const keptBlocks = [];
  for (const b of blocks) {
    if (b && b.ty === 'text') {
      const t = String(b.tx || '').trim();
      if (!t || isNoise(t)) continue;
      keptBlocks.push(b);
      if (TAIL_MARK.test(t)) break;
    } else {
      if (keptBlocks.some((x) => x && x.ty === 'text' && TAIL_MARK.test(String(x.tx || '')))) continue;
      keptBlocks.push(b);
    }
  }
  sql("update tool_web_post set content_text='" + esc(kept.join('\n')) + "', content='" + esc(JSON.stringify(keptBlocks)) + "' where id=" + r.id + ';');
}
console.log((DRY ? 'DRY-RUN' : 'APPLIED') + ' | posts', changed, '| paragraphs dropped', dropped);
