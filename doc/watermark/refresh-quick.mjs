import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

// 快刷：页面刷新时用，约 20 秒。只碰最新那一层：
//   归档页第 1 页（全量历史的最新层） + 每日大赛分类第 1 页（当天新帖与置顶） + 最新 3 条补详情
// 完整轮次（21 个分类 + 详情 6 条）交给 refresh-latest.mjs，每 20 分钟一次。
const NODE = process.execPath;
const DIR = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const LOG = 'E:/111/project/QTools/data/logs/refresh-latest.log';

const log = (msg) => {
  const line = new Date().toISOString() + ' [quick] ' + msg;
  console.log(line);
  try { fs.appendFileSync(LOG, line + '\n'); } catch (e) { /* 日志失败不影响主流程 */ }
};

const run = (script, env) => {
  try {
    const out = execFileSync(NODE, [DIR + script], { cwd: DIR, env: { ...process.env, ...env }, encoding: 'utf8', maxBuffer: 1 << 26, timeout: 5 * 60 * 1000 });
    return out.trim().split('\n').slice(-1)[0];
  } catch (e) {
    return 'FAILED: ' + String((e && e.message) || e).slice(0, 140);
  }
};

log('start');
log('archives: ' + run('crawl-archives.mjs', { FROM: '1', TO: '1' }));
log('mrds cat: ' + run('crawl-feeds.mjs', { PAGES: '1', ONLY: '每日大赛' }));
log('detail:   ' + run('refetch-images.mjs', { ONLY_NO_DETAIL: '1', LIMIT: '3' }));
log('featured: ' + run('refetch-featured.mjs', {}));
// 清理站点样板行（按文档频率自动发现，写盘给爬虫用）
log('noise:    ' + run('clean-noise.mjs', { DRY: '0' }));
  // 正文结构清理：关键词行之后、纯链接行、推广话术
  log('trim:     ' + run('trim-body.mjs', { DRY: '0' }));
log('done');