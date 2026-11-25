/**
 * 源站域名轮换同步（sync-source-host.mjs）
 *
 * 背景：源站入口随封锁轮换（见 https://gitlab.com/mrds/mrds 的 README），
 * 文章页主机是形如 able.xxxxx.cc 的随机域名。库里 tool_web_post.url 存绝对地址，
 * 域名一换，重签（真浏览器打开文章页）就全部超时 → 播放全 502。
 *
 * 逻辑（先验证、后迁移）：
 *   0) 主机名不再写死 able.*：源站现在用「任意单词 + 随机串 + TLD」（如 adapt.kzzkhxdjj.cc、
 *      able.kzzkhxdjj.cc），所以候选靠正则广收集、再用文章页实测通过才算数；
 *   1) 用库里当前主域名取一条 url 做健康检查，能 200 就直接结束（绝大多数情况）；
 *   2) 不通才从入口站链路里尽力发现新主机；
 *   3) 找到且与库不同 → 备份后整体替换；找不到就报错退出，等人介入。
 *
 * 用法：node sync-source-host.mjs [--dry]
 */
import { sqlAsync } from '../lib/db.mjs';

// 旧的 able.* 规则保留：优先验证它，能过就少发请求
const HOST_RE = /able\.[a-z0-9]+\.(?:cc|com|net|top|vip|xyz)/i;
// 广收集：<单词>.<随机串>.<tld>，例如 adapt.kzzkhxdjj.cc / block.kzzkhxdjj.cc
const CAND_RE = /\b([a-z][a-z0-9-]{1,20}\.(?:[a-z0-9]{5,24})\.(?:cc|com|net|top|vip|xyz))\b/gi;
const UA = { 'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/157 Safari/537.36' };
// 入口顺序：中转枢纽站优先（它稳定地列出当前文章主机家族），再退回几个短域名入口。
// 实测 mrdst*.com 只会跳到别的枢纽，且每次给的主机家族不同，靠它单打独斗经常发现不到。
const ENTRIES = (process.env.MRDS_ENTRY
  || 'https://www.gkaacdcul.cc,https://mrdst3.com,https://mrdsv8.com,https://mrdst2.com').split(',');
const DRY = process.argv.includes('--dry');

// 密码/客户端路径统一由 lib/db.mjs 解析（环境变量 → secret.yml），定时任务里没有 env 也能自己找到
const sql = (query) => sqlAsync(query, { maxBuffer: 1 << 24, timeout: 120000 }).then((s) => s.trim());

async function head(url) {
  try {
    const res = await fetch(url, { redirect: 'follow', headers: { 'user-agent': 'Mozilla/5.0 Chrome/157 Safari/537.36' } });
    return res.status;
  } catch (e) {
    return 0;
  }
}

/** 从入口站链路里广收集候选主机（最多 3 跳） */
async function collectCandidates() {
  const found = new Set();
  for (const entry of ENTRIES) {
    let urls = [entry.trim()];
    const seen = new Set();
    for (let hop = 0; hop < 3 && urls.length; hop += 1) {
      const nextUrls = [];
      for (const u of urls) {
        if (!u || seen.has(u)) continue;
        seen.add(u);
        let text = '';
        let finalUrl = u;
        try {
          const res = await fetch(u, { redirect: 'follow', headers: UA, signal: AbortSignal.timeout(20000) });
          text = await res.text();
          finalUrl = res.url || u;
        } catch (e) {
          continue;
        }
        for (const m of text.matchAll(CAND_RE)) found.add(m[1].toLowerCase());
        for (const m of text.matchAll(/https?:\/\/[a-z0-9.-]+\.(?:cc|com|net|top|vip|xyz)\b/gi)) nextUrls.push(m[0]);
        if (finalUrl) nextUrls.push(finalUrl);
      }
      urls = [...new Set(nextUrls)].slice(0, 6);
    }
  }
  // 同一随机段的 able.* 也补进来：文章主机历史上一直是 able.<随机串>.<tld>，
  // 而入口页里出现的可能是 adapt./block./api. 等同段兄弟域名
  for (const h of [...found]) {
    const parts = h.split('.');
    if (parts.length === 3) {
      const able = 'able.' + parts[1] + '.' + parts[2];
      found.add(able);
    }
  }
  return [...found];
}

/** 文章页实测：200 且正文够长才算可用 */
async function verifyHost(host, sourceId) {
  try {
    const res = await fetch('https://' + host + '/archives/' + sourceId + '/',
      { redirect: 'follow', headers: UA, signal: AbortSignal.timeout(15000) });
    if (res.status !== 200) return false;
    const text = await res.text();
    // 光有 200 不够：广告跳转页/中转页也返回 200，但它们没有文章结构。
    // xqbj-breadcrumb-container 是文章页独有容器（refetch-images.mjs 也用它定位正文）。
    return text.includes('xqbj-breadcrumb-container') && text.length > 20000;
  } catch (e) {
    return false;
  }
}

async function discoverHost(sourceId, currentHost) {
  const candidates = (await collectCandidates()).filter((h) => h !== (currentHost || '').toLowerCase());
  console.log('[host] 候选主机:', candidates.length, '个', candidates.slice(0, 8).join(', '));
  // 先试 able.*（历史上最常见的形态），再逐个实测
  const ordered = [
    ...candidates.filter((h) => /^able\./.test(h)),
    ...candidates.filter((h) => !/^able\./.test(h)),
  ];
  for (let i = 0; i < ordered.length; i += 6) {
    const batch = ordered.slice(i, i + 6);
    const results = await Promise.all(batch.map(async (h) => ((await verifyHost(h, sourceId)) ? h : null)));
    const hit = results.find(Boolean);
    if (hit) return { host: hit, via: 'article-page-check' };
  }
  return null;
}

async function discoverHostLegacy() {
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

/**
 * 残党迁移：历次轮换漏掉的旧主机（每个只剩几十行）不会跟着主域名一起迁走，
 * 它们留着死域名 → 重签（真浏览器打开文章页）必定 ERR_CONNECTION_TIMED_OUT。
 * 判据逐主机实测：跟随 301 后落到活着的主机，就把这一批整批换掉。
 * @returns 迁移（或预演）的行数
 */
async function migrateStragglers(current) {
  let groups = [];
  try {
    const raw = await sql("select substring_index(substring_index(url,'//',-1),'/',1) as h, count(*) c, min(url) u from qtools.tool_web_post group by h having h <> '" + current + "' order by c desc;");
    groups = (raw || '').split('\n').map((line) => line.split('\t')).filter((c) => c[0]);
  } catch (e) {
    console.log('[host] 残党统计失败:', String(e.message).slice(0, 100));
    return 0;
  }
  if (!groups.length) return 0;
  console.log('[host] 残党主机:', groups.length, '个，共', groups.reduce((n, c) => n + Number(c[1] || 0), 0), '条');
  let moved = 0;
  for (const [host, count, sampleUrl] of groups) {
    let finalHost = '';
    let status = 0;
    try {
      const res = await fetch(sampleUrl, { redirect: 'follow', headers: UA, signal: AbortSignal.timeout(15000) });
      status = res.status;
      finalHost = new URL(res.url).host.toLowerCase();
    } catch (e) {
      status = 0;
    }
    // 兜底：主机整台连不上时（实测 able.akmevtunk.cc 直接超时），
    // 源站还给同一前缀在新家族上的兄弟主机（able.akmevtunk.cc → able.nfuwinwd.cc）
    if (status !== 200 || !finalHost || finalHost === host.toLowerCase()) {
      const parts = host.split('.');
      const fam = current.split('.').slice(-2).join('.');
      const sibling = parts.length === 3 ? parts[0] + '.' + fam : '';
      if (sibling && sibling !== host && (await verifyHost(sibling, sampleSid))) {
        finalHost = sibling;
        status = 200;
        console.log('[host] ' + host + ' 连不上，兄弟主机 ' + sibling + ' 可用');
      }
    }
    if (status !== 200 || !finalHost || finalHost.toLowerCase() === host.toLowerCase()) {
      console.log('[host] 跳过', host, '(' + count + ' 条)', status ? '最终主机 ' + (finalHost || '?') : '连不上');
      continue;
    }
    if (DRY) {
      console.log('[host] --dry，将把', count, '条', host, '改成', finalHost);
      moved += Number(count);
      continue;
    }
    const stmt = "update qtools.tool_web_post set url = replace(url, '" + host + "', '" + finalHost + "') where url like '%" + host + "%';";
    await sql("insert into qtools.tool_web_post_url_bak select id, url, now() from qtools.tool_web_post where url like '%" + host + "%';");
    await sql(stmt);
    console.log('[host] 残党迁移', host, '->', finalHost, count, '条');
    moved += Number(count);
  }
  return moved;
}

const FROM = (() => {
  const i = process.argv.indexOf('--from');
  return i > 0 ? String(process.argv[i + 1] || '').trim() : '';
})();

// 样本文章的 source_id：验证候选主机时直接打开它
const sidRow = await sql("select source_id from qtools.tool_web_post where source_id is not null order by id desc limit 1;");
const sampleSid = (sidRow || '').split('\t')[0];

// 1) 当前主域名
const row = await sql("select substring_index(substring_index(url,'//',-1),'/',1) as h, count(*) c from qtools.tool_web_post group by h order by c desc limit 1;");
const [current, count] = (row || '').split('\t');
if (!current) {
  console.log('[host] 库里没有可用的 url');
  process.exit(1);
}
console.log('[host] 库里主域名:', current, '共', count, '条');

// 2) 健康检查 —— 关键：跟随跳转后看"最终主机"，源站换域名就是靠 301 链暴露的
const sample = await sql("select url from qtools.tool_web_post where url like '%" + current + "%' order by id desc limit 1;");
let finalHost = '';
let status = 0;
try {
  const res = await fetch(sample, { redirect: 'follow', headers: { 'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/157 Safari/537.36' } });
  status = res.status;
  finalHost = new URL(res.url).host.toLowerCase();
} catch (e) {
  console.log('[host] 请求失败:', String(e.message).slice(0, 100));
}
console.log('[host] 健康检查:', status, '  最终主机:', finalHost || '(取不到)', '  ←', sample);

// 主域名自己没问题也要顺手清一遍残党：轮换是一个主机一个主机死的，
// 主域名早就迁好了，残党还停在多年前的主机上（实测 able.akmevtunk.cc 30 条、h4ppz1.dcupekeza.cc 30 条）
const stragglers = await migrateStragglers(current);

if (status === 200 && finalHost && finalHost === current.toLowerCase()) {
  console.log('[host] 已是最新' + (stragglers ? '（顺带迁走残党 ' + stragglers + ' 条）' : '') + '，无需迁移');
  process.exit(0);
}

// 3) 换域名了：优先用 301 链的最终主机；取不到才去入口站尽力发现
let target = finalHost && status === 200 ? finalHost : '';
if (!target) {
  const found = await discoverHost();
  if (!found) {
    console.log('[host] 当前域名不可用，且未能从入口站发现新主机 → 需要人工确认（见 gitlab.com/mrds/mrds 的 README）');
    process.exit(2);
  }
  target = found.host;
  console.log('[host] 入口站发现新主机:', target, '（来自', found.via + '）');
} else {
  console.log('[host] 由 301 链发现新主机:', target);
}

if (target.toLowerCase() === current.toLowerCase()) {
  console.log('[host] 与库里一致但健康检查未通过 → 属于站点故障，不是域名轮换');
  process.exit(2);
}

const stmt = "update qtools.tool_web_post set url = replace(url, '" + current + "', '" + target + "') where url like '%" + current + "%';";
if (DRY) {
  console.log('[host] --dry，将执行:', stmt);
  process.exit(0);
}
await sql("create table if not exists qtools.tool_web_post_url_bak as select id, url, now() as bak_at from qtools.tool_web_post where 1=0;");
await sql("insert into qtools.tool_web_post_url_bak select id, url, now() from qtools.tool_web_post where url like '%" + current + "%';");
await sql(stmt);
const after = await sql("select substring_index(substring_index(url,'//',-1),'/',1) as h, count(*) c from qtools.tool_web_post group by h order by c desc limit 1;");
console.log('[host] 迁移完成 ->', after.replace('\t', ' 条 '));
// 换完主域名再清一遍：刚才可能还有主机正在轮换中途，现在应该都指到新主机了
const rest = await migrateStragglers(target);
if (rest) console.log('[host] 顺带迁走残党', rest, '条');
