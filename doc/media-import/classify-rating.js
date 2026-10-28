#!/usr/bin/env node
'use strict';
/**
 * 内容自动分级（P6.02）—— 一次性跑批脚本
 *
 * 重要：本脚本**不实现任何分级规则**。规则词表 + 匹配逻辑 + 模型兜底全部在后端
 * （qtools-media 的 MediaRatingRules + POST /api/media/admin/rating/classify），
 * 脚本只是这个接口的命令行外壳：把参数发过去、把报告打印成人能读的样子。
 * 因此「脚本判定」与「管理端维护页自动分级按钮的判定」永远是同一份结果 ——
 * 不存在「脚本跑出来 A、按钮跑出来 B」的漂移。
 *
 * 两条入口的分工：按钮给「点一下看分布」；脚本给「部署机上跑一次、把输出贴进工单」。
 *
 * 用法（默认 dry-run，不写库）：
 *   node classify-rating.js --token=<管理端 JWT>
 *   node classify-rating.js --token=<jwt> --use-llm         让本地模型兜底不确定条目
 *   node classify-rating.js --token=<jwt> --apply           确认后写库（默认不覆盖已分级）
 *   node classify-rating.js --token=<jwt> --apply --force   连人工设过的分级也重判
 *
 * 认证（二选一）：
 *   --token=<jwt>        直接给管理端 token（推荐：CI / 脚本场景）
 *   --user= --pass=      走 POST /login 换 token（后端开图形验证码时会失败，此时请改用 --token）
 *   也可以走环境变量 QTOOLS_TOKEN / QTOOLS_USER / QTOOLS_PASS。
 *
 * 连接：
 *   --api=<url>          后端地址，默认 http://127.0.0.1:8080（接口前缀由脚本补 /api）
 *   --timeout=<ms>       单次 HTTP 超时，默认 600000（开了 --use-llm 时一轮可能要几分钟）
 *
 * 过滤：
 *   --only=video|album|all   只判视频 / 只判相册 / 都判（默认 all）
 *   --limit=<n>              每类最多审视多少条（默认 0 = 全量）
 *   --dry-run / --apply      预览（默认）/ 写库
 *   --use-llm                对「规则一条未命中」的条目调本地模型兜底
 *   --force                  覆盖已经是 guidance / restricted 的条目（默认只升级 general）
 *
 * 数据库（可选，只用于核对与 --root-from-db，不参与判定）：
 *   --mysql= --host= --port= --db= --user= --secret=
 *   给了这些参数（或环境变量 MYSQL_PWD）脚本会在跑完后多查一条
 *   select content_rating, count(*) group by，把库里的真实分布贴在报告末尾 ——
 *   这是 --apply 之后唯一能证明「写进去了」的独立证据（报告里的计数只是后端算出来的预期）。
 *   --secret=<secret.yml> 取 spring.datasource.druid.master.password；密码只走子进程 MYSQL_PWD。
 *   --root-from-db           列出 media_root 里登记的库根（分级用不到物理根，只为日志可追溯）
 *
 * 输出（默认人读；--json 打整份后端报告，便于落盘比对）：
 *   START ...                本次参数
 *   RULES ...                词表来源与条数
 *   TOTALS / COUNTS / EXISTING / SOURCES    计数
 *   HITS TOP 20              命中词统计（哪个词命中多少次）
 *   SAMPLE 20                前 20 条样例：标题 → 判定 → 理由
 *   LLM ...                  模型兜底条数 / 耗时 / 10 条样例
 *   APPLIED ...              --apply 时的写入结果
 *   VERIFY ...               从库里独立核对的分布（需要 mysql 参数）
 *
 * 退出码：0 成功；1 参数/连接/接口错误；2 后端返回业务错误（权限不足、词表加载失败等）。
 */

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const DEFAULTS = {
  api: process.env.QTOOLS_API || 'http://127.0.0.1:8080',
  token: process.env.QTOOLS_TOKEN || '',
  user: process.env.QTOOLS_USER || 'admin',
  pass: process.env.QTOOLS_PASS || '',
  timeoutMs: 600000,
  dryRun: true,
  only: 'all',
  limit: 0,
  useLlm: false,
  force: false,
  json: false,
  rootFromDb: false,
  mysql: null,
  host: '127.0.0.1',
  port: 3307,
  db: 'qtools',
  dbUser: 'root',
  secret: null,
};

const HELP = [
  '用法：node classify-rating.js [选项]',
  '',
  '  --dry-run            只预览不写库（默认）',
  '  --apply              写库（等价于 dryRun=false；看过报告再开）',
  '  --only=<v>           video | album | all（默认 all）',
  '  --limit=<n>          每类最多审视多少条（默认 0 = 全量）',
  '  --use-llm            对规则未命中的不确定条目调本地模型兜底（并发 2、单条 15s）',
  '  --force              覆盖已经是 guidance / restricted 的条目（默认不覆盖，只升级 general）',
  '  --token=<jwt>        管理端 token（也可用环境变量 QTOOLS_TOKEN）',
  '  --user=<name>        登录用户名（配合 --pass；后端开验证码时会失败，建议用 --token）',
  '  --pass=<pwd>         登录密码（也可用环境变量 QTOOLS_PASS）',
  '  --api=<url>          后端地址，默认 http://127.0.0.1:8080',
  '  --timeout=<ms>       单次 HTTP 超时，默认 600000',
  '',
  '  数据库（可选，仅用于跑完核对 + --root-from-db）：',
  '  --mysql=<exe>        mysql 客户端路径（也可用环境变量 MS_MYSQL / PATH）',
  '  --host= --port= --db= --user= --secret=<secret.yml>',
  '  --root-from-db       列出 media_root 里登记的库根',
  '',
  '  --json               把后端原始报告整份打印（便于落盘 / diff）',
  '  --help               显示本帮助',
  '',
  '示例：',
  '  node classify-rating.js --token=$TOKEN --use-llm --secret=QTools/qtools-admin/src/main/resources/secret.yml',
  '  node classify-rating.js --token=$TOKEN --apply --use-llm',
  '  node classify-rating.js --token=$TOKEN --only=album --apply',
  ''
].join('\n');

function parseArgs(argv) {
  const opts = Object.assign({}, DEFAULTS);
  for (const raw of argv) {
    const a = raw.trim();
    if (!a) continue;
    if (a === '--help' || a === '-h') { opts.help = true; continue; }
    if (a === '--dry-run' || a === '--dryRun') { opts.dryRun = true; continue; }
    if (a === '--apply') { opts.dryRun = false; continue; }
    if (a === '--use-llm' || a === '--useLlm') { opts.useLlm = true; continue; }
    if (a === '--force') { opts.force = true; continue; }
    if (a === '--json') { opts.json = true; continue; }
    if (a === '--root-from-db') { opts.rootFromDb = true; continue; }

    const m = /^--([a-zA-Z-]+)=(.*)$/.exec(a);
    if (!m) throw new Error('无法识别的参数：' + a + '（--help 看用法）');
    const key = m[1].toLowerCase();
    const value = m[2];
    switch (key) {
      case 'api': opts.api = value; break;
      case 'token': opts.token = value; break;
      case 'user': opts.user = value; break;
      case 'pass': opts.pass = value; break;
      case 'only': opts.only = value; break;
      case 'limit': opts.limit = toInt(value, '--limit'); break;
      case 'timeout': opts.timeoutMs = toInt(value, '--timeout'); break;
      case 'mysql': opts.mysql = value; break;
      case 'host': opts.host = value; break;
      case 'port': opts.port = toInt(value, '--port'); break;
      case 'db': opts.db = value; break;
      case 'secret': opts.secret = value; break;
      case 'dbuser': opts.dbUser = value; break;
      default: throw new Error('未知参数 --' + m[1] + '（--help 看用法）');
    }
  }
  if (opts.only === 'videos') opts.only = 'video';
  if (opts.only === 'albums') opts.only = 'album';
  if (['all', 'video', 'album'].indexOf(opts.only) < 0) {
    throw new Error('--only 只能是 video / album / all，本次: ' + opts.only);
  }
  if (opts.limit < 0) throw new Error('--limit 不能为负');
  if (!/^https?:\/\//.test(opts.api)) throw new Error('--api 必须是 http(s):// 开头的地址：' + opts.api);
  return opts;
}

function toInt(text, label) {
  if (!/^\d+$/.test(String(text).trim())) throw new Error(label + ' 必须是整数，本次: ' + text);
  return Number(text);
}

// ------------------------------------------------------------------ HTTP

function apiUrl(opts, pathname) {
  const base = opts.api.replace(/\/+$/, '');
  return base + (pathname.indexOf('/api/') === 0 ? pathname : '/api' + pathname);
}

async function fetchJson(url, init, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, Object.assign({}, init, { signal: controller.signal }));
    const text = await response.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch (e) { /* 非 JSON（网关错误页）原样带回 */ }
    return { status: response.status, json, text, url };
  } finally {
    clearTimeout(timer);
  }
}

function authedHeaders(token, withBody) {
  const headers = { Accept: 'application/json' };
  if (withBody) headers['Content-Type'] = 'application/json;charset=utf-8';
  if (token) headers.Authorization = 'Bearer ' + token;
  return headers;
}

function classifyRequest(opts, token, body) {
  return fetchJson(apiUrl(opts, '/media/admin/rating/classify'), {
    method: 'POST',
    headers: authedHeaders(token, true),
    body: JSON.stringify(body),
  }, opts.timeoutMs);
}

async function login(opts) {
  if (opts.token) return opts.token;
  if (!opts.pass) {
    throw new Error('没有 token：请用 --token=<管理端 JWT>，或 --user=/--pass= 登录' +
      '（后端开图形验证码时请改用 --token）');
  }
  const url = opts.api.replace(/\/+$/, '') + '/login';
  const res = await fetchJson(url, {
    method: 'POST',
    headers: authedHeaders('', true),
    body: JSON.stringify({ username: opts.user, password: opts.pass, code: '', uuid: '' }),
  }, opts.timeoutMs);
  const payload = res.json;
  if (!payload || payload.code !== 200 || !payload.token) {
    throw new Error('登录失败：' + ((payload && payload.msg) || ('HTTP ' + res.status)) +
      '（后端开启图形验证码时脚本无法自动登录，请用 --token 直接给管理端 JWT）');
  }
  return payload.token;
}

// ------------------------------------------------------------------ 数据库（只用于核对）

function readSecret(file) {
  const abs = path.resolve(file);
  let text;
  try { text = fs.readFileSync(abs, 'utf8'); } catch (e) {
    throw new Error('--secret 指定的文件读不到：' + abs + '（' + (e.code || e.message) + '）');
  }
  const match = /master:\s*\n(?:.*\n)*?\s*password:\s*['"]?([^'"\n]+)['"]?/.exec(text);
  if (!match) throw new Error('--secret=' + abs + ' 里找不到 spring.datasource.druid.master.password');
  return match[1].trim();
}

function dbPassword(opts) {
  if (process.env.MYSQL_PWD) return process.env.MYSQL_PWD;
  if (opts.secret) return readSecret(opts.secret);
  return null;
}

function mysqlQuery(opts, sql) {
  const password = dbPassword(opts);
  if (!password) throw new Error('未提供数据库密码：请设置 MYSQL_PWD 或用 --secret=<secret.yml>');
  const clients = [];
  if (opts.mysql) clients.push(opts.mysql);
  if (process.env.MS_MYSQL) clients.push(process.env.MS_MYSQL);
  clients.push('mysql');
  const args = ['-u' + opts.dbUser, '-h' + opts.host, '-P' + opts.port, '--default-character-set=utf8mb4',
    '-N', '-B', opts.db, '-e', sql];
  let lastError = null;
  for (const client of clients) {
    const result = spawnSync(client, args, {
      encoding: 'utf8',
      env: Object.assign({}, process.env, { MYSQL_PWD: password }),
    });
    if (!result.error && result.status === 0) return (result.stdout || '').trim();
    lastError = result.error ? (result.error.code || result.error.message) : (result.stderr || '').trim();
  }
  throw new Error('mysql 不可用：' + lastError);
}

function verifyDistribution(opts) {
  if (!opts.secret && !process.env.MYSQL_PWD) return null;
  try {
    const rows = [];
    const tables = [['media_video', '视频'], ['media_album', '相册']];
    for (const pair of tables) {
      const text = mysqlQuery(opts, 'select content_rating, count(*) from ' + pair[0] +
        ' group by content_rating order by content_rating');
      const map = {};
      for (const line of text.split(/\r?\n/)) {
        if (!line.trim()) continue;
        const cells = line.split('\t');
        map[cells[0]] = Number(cells[1]);
      }
      rows.push({ table: pair[0], label: pair[1], map: map });
    }
    return rows;
  } catch (e) {
    return { error: e.message };
  }
}

function listRoots(opts) {
  try {
    const text = mysqlQuery(opts, "select id, path from media_root where kind = 'root' order by id");
    return text ? text.split(/\r?\n/).filter(Boolean) : [];
  } catch (e) {
    return [];
  }
}

// ------------------------------------------------------------------ 输出

/** 中文字符按两格宽对齐（报告是给人看的，列歪了就没法快速扫） */
function pad(text, width) {
  const value = String(text == null ? '' : text);
  const widthDelta = width - value.replace(/[^\x00-\xff]/g, 'xx').length;
  return value + ' '.repeat(Math.max(0, widthDelta));
}

/** 提示行（AUTH / ROOTS / VERIFY）：--json 时改走 stderr，保证 stdout 是一份可直接 JSON.parse 的报告 */
function info(opts, text) {
  if (opts.json) {
    console.error(text);
  } else {
    console.log(text);
  }
}

function printReport(report) {
  const rules = report.rules || {};
  const totals = report.totals || {};
  const counts = report.counts || {};
  const existing = report.existing || {};
  const sources = report.sources || {};
  const applied = report.applied || {};
  const llm = report.llm || {};

  console.log('START dryRun=' + report.dryRun + ' only=' + report.only +
    ' limit=' + (report.limit == null ? 'all' : report.limit) + ' useLlm=' + report.useLlm +
    ' force=' + report.force + ' elapsedMs=' + report.elapsedMs);
  console.log('RULES source=' + rules.source + ' total=' + rules.total);
  console.log('TOTALS videos=' + totals.videos + ' albums=' + totals.albums + ' items=' + totals.items);
  console.log('COUNTS   general=' + counts.general + ' guidance=' + counts.guidance + ' restricted=' + counts.restricted);
  console.log('EXISTING general=' + existing.general + ' guidance=' + existing.guidance + ' restricted=' + existing.restricted);
  console.log('SOURCES  rule=' + sources.rule + ' llm=' + sources.llm + ' default=' + sources.default);

  const hits = report.hitsTop || [];
  console.log('');
  console.log('HITS TOP ' + hits.length + '  (ruleId / 命中词 / 档位 / 命中字段 / 条目数)');
  for (const hit of hits) {
    console.log('  ' + pad(hit.ruleId, 30) + ' ' + pad(hit.word, 12) + ' ' + pad(hit.rating, 11) +
      ' ' + pad((hit.fields || []).join(','), 10) + ' ' + hit.count);
  }

  const samples = report.samples || [];
  console.log('');
  console.log('SAMPLE ' + samples.length + '  (标题 → 判定 → 理由)');
  for (const s of samples) {
    console.log('  [' + s.kind + '] ' + pad(String(s.title || '').slice(0, 46), 48) + ' → ' +
      pad(s.rating, 11) + ' ← ' + s.reason + (s.changed ? '   *会改库*' : ''));
  }

  console.log('');
  console.log('LLM enabled=' + llm.enabled + ' ready=' + llm.ready + ' model=' + llm.model +
    ' provider=' + llm.provider + ' candidates=' + llm.candidates + ' judged=' + llm.judged +
    ' failed=' + llm.failed + ' truncated=' + llm.truncated + ' elapsedMs=' + llm.elapsedMs);
  if (llm.note) console.log('LLM NOTE ' + llm.note);
  for (const s of llm.samples || []) {
    console.log('  LLM-OK   ' + pad(String(s.title || '').slice(0, 44), 46) + ' → ' + pad(s.rating, 11) +
      ' raw=' + JSON.stringify(s.raw) + ' ' + s.ms + 'ms');
  }
  for (const s of llm.failedSamples || []) {
    console.log('  LLM-FAIL ' + pad(String(s.title || '').slice(0, 44), 46) + ' → 保持原值（' + (s.raw || '') + '）');
  }

  console.log('');
  console.log('APPLIED dryRun=' + applied.dryRun + ' eligible=' + applied.eligible +
    ' protected=' + applied.protected + ' videos=' + applied.videos + ' albums=' + applied.albums +
    ' updated=' + applied.updated);
}

// ------------------------------------------------------------------ 主流程

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(HELP);
    return 0;
  }

  const token = await login(opts);
  info(opts, 'AUTH ok (token ' + token.slice(0, 8) + '...)');

  if (opts.rootFromDb) {
    const roots = listRoots(opts);
    info(opts, 'ROOTS ' + (roots.length ? roots.join(' | ') : '（没查到 / 未提供数据库参数）'));
    info(opts, 'ROOTS NOTE 分级只读库里的标题 / 目录 / rel_path，不依赖物理根；本项仅为日志可追溯');
  }

  const body = { dryRun: opts.dryRun, only: opts.only, useLlm: opts.useLlm, force: opts.force };
  if (opts.limit > 0) body.limit = opts.limit;

  const res = await classifyRequest(opts, token, body);
  const payload = res.json;
  if (!payload || res.status >= 400 || payload.code === 401 || payload.code === 403) {
    console.error('ERR 接口调用失败 HTTP ' + res.status + '：' +
      (payload ? (payload.msg || payload.error) : String(res.text || '').slice(0, 200)));
    return 2;
  }
  if (payload.error) {
    console.error('ERR 后端返回业务错误：' + payload.error);
    return 2;
  }

  if (opts.json) {
    console.log(JSON.stringify(payload, null, 2));
  } else {
    printReport(payload);
  }

  const verify = verifyDistribution(opts);
  if (verify && verify.error) {
    info(opts, '');
    info(opts, 'VERIFY skipped（' + verify.error + '）');
  } else if (verify) {
    info(opts, '');
    for (const row of verify) {
      const keys = Object.keys(row.map).sort();
      const parts = keys.map(function (key) { return key + '=' + row.map[key]; });
      info(opts, 'VERIFY ' + row.label + '(' + row.table + ') ' + (parts.join(' ') || '（空表）'));
    }
  }
  return 0;
}

main().then(function (code) { process.exit(code); }).catch(function (e) {
  console.error('ERR ' + (e && e.message ? e.message : e));
  process.exit(1);
});
