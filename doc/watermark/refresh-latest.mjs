import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

// 「拉最新」= 归档页前两页（全量、按更新时间倒序）→ 顺带给最新几条补详情。
// 由 resign-sidecar 定时调用；手动跑也一样：node refresh-latest.mjs
const NODE = process.execPath;
const DIR = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const LOG = process.env.QTOOLS_LOG || DIR + '../../../data/logs/refresh-latest.log';
const DETAIL_N = Number(process.env.DETAIL_N || 30);

const log = (msg) => {
  const line = new Date().toISOString() + ' ' + msg;
  console.log(line);
  try { fs.appendFileSync(LOG, line + '\n'); } catch (e) { /* 日志失败不影响主流程 */ }
};

const run = (script, env) => {
  try {
    const out = execFileSync(NODE, [DIR + script], {
      cwd: DIR,
      env: { ...process.env, ...env },
      encoding: 'utf8',
      windowsHide: true,
      maxBuffer: 1 << 26,
      timeout: 10 * 60 * 1000,
    });
    return out.trim().split('\n').slice(-2).join(' | ');
  } catch (e) {
    return 'FAILED: ' + String((e && e.message) || e).slice(0, 160);
  }
};

log('refresh start');
// 归档页抓"全量历史"，分类页抓"最新若干条 + 置顶/精选"（两边集合不同，都必须跑）
log('archives: ' + run('crawl-archives.mjs', { FROM: '1', TO: '2' }));
log('feeds:    ' + run('crawl-feeds.mjs', { PAGES: '1' }));
if (DETAIL_N > 0) log('detail:   ' + run('refetch-images.mjs', { ONLY_NO_DETAIL: '1', LIMIT: String(DETAIL_N) }));
log('featured: ' + run('refetch-featured.mjs', {}));
// 源站面（归档/分类/详情/精选）到此结束，后面只剩 clean-noise / trim 这类纯库内维护。
// sidecar 的「连续排空」靠这个标记判断：源站面做完了就可以开始排空，不必等整轮刷新退出
// —— 否则每 20 分钟里有 5~10 分钟白等（实测 clean-noise + trim 占 3~5 分钟）。
try { fs.writeFileSync(DIR + '../../../data/refresh-source-done', String(Date.now())); } catch (e) { /* 标记失败不影响刷新 */ }
// 清理站点样板行（按文档频率自动发现，写盘给爬虫用）
log('noise:    ' + run('clean-noise.mjs', { DRY: '0' }));
  // 正文结构清理：关键词行之后、纯链接行、推广话术
  log('trim:     ' + run('trim-body.mjs', { DRY: '0' }));
log('refresh done');