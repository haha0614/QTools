/**
 * 源站域名轮换同步（sync-source-host.mjs）
 *
 * 背景：源站入口随封锁轮换（见 https://gitlab.com/mrds/mrds 的 README），
 * 文章页主机是形如 able.xxxxx.cc 的随机域名。库里 tool_web_post.url 存绝对地址，
 * 域名一换，重签（真浏览器打开文章页）就全部超时 → 播放全 502。
 *
 * 逻辑（先验证、后迁移）：
 *   1) 用库里当前主域名取一条 url 做健康检查，能 200 就直接结束（绝大多数情况）；
 *   2) 不通才从入口站链路里尽力发现新主机；
 *   3) 找到且与库不同 → 备份后整体替换；找不到就报错退出，等人介入。
 *
 * 用法：node sync-source-host.mjs [--dry]
 */
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);
const MYSQL = process.env.MYSQL || 'D:/project/QTools-All/tools/mysql/bin/mysql.exe';
const HOST_RE = /able\.[a-z0-9]+\.(?:cc|com|net|top|vip|xyz)/i;
const ENTRIES = (process.env.MRDS_ENTRY || 'https://mrdst3.com,https://mrdsv8.com,https://mrdst2.com').split(',');
const DRY = process.argv.includes('--dry');

function sql(query) {
  return run(MYSQL, ['-h127.0.0.1', '-P3307', '-uroot', '--protocol=TCP', '-N', '-B', '-e', query], {
    env: { ...process.env }, maxBuffer: 1 << 24, timeout: 120000,
  }).then((r) => r.stdout.trim());
}

async function head(url) {
  try {
    const res = await fetch(url, { redirect: 'follow', headers: { 'user-agent': 'Mozilla/5.0 Chrome/157 Safari/537.36' } });
    return res.status;
  } catch (e) {
    return 0;
  }
}

async function discoverHost() {
  for (const entry of ENTRIES) {
    let url = entry.trim();
    for (let hop = 0; hop < 4; hop += 1) {
      let text = '';
      try {
        const res = await fetch(url, { redirect: 'follow', headers: { 'user-agent': 'Mozilla/5.0 Chrome/157 Safari/537.36' } });
        text = await res.text();
      } catch (e) {
        break;
      }
      const m = HOST_RE.exec(text);
      if (m) return { host: m[0].toLowerCase(), via: url };
      const next = /https?:\/\/[a-z0-9.-]+\.(?:cc|com|net|top|vip|xyz)\b/i.exec(text);
      if (!next || next[0] === url) break;
      url = next[0];
    }
  }
  return null;
}

// 1) 当前主域名
const row = await sql("select substring_index(substring_index(url,'//',-1),'/',1) as h, count(*) c from qtools.tool_web_post group by h order by c desc limit 1;");
const [current, count] = (row || '').split('\t');
if (!current) {
  console.log('[host] 库里没有可用的 url');
  process.exit(1);
}
console.log('[host] 库里主域名:', current, '共', count, '条');

// 2) 健康检查
const sample = await sql("select url from qtools.tool_web_post where url like '%" + current + "%' order by id desc limit 1;");
const status = await head(sample);
console.log('[host] 健康检查:', status, '←', sample);
if (status === 200) {
  console.log('[host] 当前域名可用，无需迁移');
  process.exit(0);
}

// 3) 发现新主机
const found = await discoverHost();
if (!found) {
  console.log('[host] 当前域名不可用，且未能从入口站发现新主机 → 需要人工确认（见 gitlab.com/mrds/mrds 的 README）');
  process.exit(2);
}
console.log('[host] 发现新主机:', found.host, '（来自', found.via + '）');
if (found.host.toLowerCase() === current.toLowerCase()) {
  console.log('[host] 与库里一致，但健康检查失败 → 属于站点故障，不是域名轮换');
  process.exit(2);
}

const stmt = "update qtools.tool_web_post set url = replace(url, '" + current + "', '" + found.host + "') where url like '%" + current + "%';";
if (DRY) {
  console.log('[host] --dry，将执行:', stmt);
  process.exit(0);
}
await sql("create table if not exists qtools.tool_web_post_url_bak as select id, url, now() as bak_at from qtools.tool_web_post where 1=0;");
await sql("insert into qtools.tool_web_post_url_bak select id, url, now() from qtools.tool_web_post where url like '%" + current + "%';");
await sql(stmt);
const after = await sql("select substring_index(substring_index(url,'//',-1),'/',1) as h, count(*) c from qtools.tool_web_post group by h order by c desc limit 1;");
console.log('[host] 迁移完成 ->', after.replace('\t', ' 条 '));
