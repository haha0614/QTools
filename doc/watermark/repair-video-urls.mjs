/**
 * 批量修复「播放地址损坏」的帖子（换机后可直接用）
 *
 * 背景：历史上出现过 `video_url = ","` / `video_urls = [",",","]` 这类脏数据，
 * 播放时表现为 manifestLoadError。后端已有周期自愈（WebPostVideoUrlSelfCheck），
 * 这个脚本用于**一次性大批量**修复，或排查时手动跑。
 *
 * 用法：
 *   node repair-video-urls.mjs            # 扫描全库并修复（默认，串行）
 *   node repair-video-urls.mjs --dry      # 只统计不写库
 *   node repair-video-urls.mjs --id 126   # 只修某一条（本站 id）
 *
 * 依赖：抓取 sidecar 已在 127.0.0.1:8765 运行（`HEADLESS=1 node resign-sidecar.mjs`）
 */
import { execFileSync } from 'node:child_process';

const MYSQL = process.env.QTOOLS_MYSQL || 'E:/111/project/QTools/tools/mysql/bin/mysql.exe';
const MYSQL_ARGS = ['-h127.0.0.1', '-P3307', '-uroot', '-pdqq1107.', '--default-character-set=utf8mb4', '-N', '-B'];
const SIDECAR = process.env.SIDECAR || 'http://127.0.0.1:8765';
const DRY = process.argv.includes('--dry');
const ONLY_ID = (() => {
  const i = process.argv.indexOf('--id');
  return i > 0 ? Number(process.argv[i + 1]) : null;
})();

const db = (sql) => execFileSync(MYSQL, [...MYSQL_ARGS, '-e', "set session sql_mode=''; " + sql, 'qtools'],
  { encoding: 'utf8', maxBuffer: 1 << 28 });
const esc = (s) => String(s).replace(/'/g, "''");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 判断一条地址是否可用 */
const isHttp = (u) => typeof u === 'string' && u.indexOf('http') === 0;

async function resignOne(pageUrl) {
  try {
    const r = await fetch(SIDECAR + '/resign', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ url: pageUrl }),
    });
    const j = await r.json().catch(() => ({}));
    return (j.urls || []).filter(isHttp);
  } catch (e) {
    return [];
  }
}

const where = ONLY_ID ? (' and id=' + ONLY_ID) : '';
const rows = db('select id, ifnull(source_id,\'\'), ifnull(url,\'\'), ifnull(video_urls,\'\') '
  + 'from tool_web_post where has_video=1' + where).split(/\r?\n/).filter(Boolean);

let total = 0;
let broken = 0;
for (const line of rows) {
  const [id, sid, pageUrl, raw] = line.split('\t');
  total += 1;
  const hasHttp = raw.indexOf('http') >= 0;
  if (hasHttp && raw) continue;              // 数组没问题，跳过
  broken += 1;
}

console.log('扫描:', total, '条，其中地址损坏:', broken, '条', DRY ? '(dry-run，不写库)' : '');

let ok = 0;
let fail = 0;
let idx = 0;
for (const line of rows) {
  const [id, sid, pageUrl0, raw] = line.split('\t');
  if (raw && raw.indexOf('http') >= 0) continue;
  idx += 1;
  const pageUrl = pageUrl0 || ((process.env.BASE || 'https://able.nwfuhxhj.cc') + '/archives/' + sid + '/');
  const list = await resignOne(pageUrl);
  if (!list.length) {
    fail += 1;
    console.log('[' + idx + '/' + broken + '] 失败 id=' + id + ' sid=' + sid);
    continue;
  }
  if (!DRY) {
    db("update tool_web_post set video_url='" + esc(list[0]) + "', video_urls='" + esc(JSON.stringify(list))
      + "', video_count=" + list.length + ", has_video=1 where id=" + id + ';');
  }
  ok += 1;
  console.log('[' + idx + '/' + broken + '] ok id=' + id + ' sid=' + sid + ' → ' + list.length + ' 段');
  await sleep(800);
}
console.log('完成：成功', ok, '失败', fail, DRY ? '(dry-run)' : '');
