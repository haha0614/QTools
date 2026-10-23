#!/usr/bin/env node
'use strict';
/**
 * 媒体库便携包 · 导出（设计见 doc/媒体库可移植性方案.md §3 P3）
 *
 * 把一个媒体库打成「可打包搬运」的目录（--out 下）：
 *   manifest.json     schemaVersion / generatedAt / tool / sourceRoot / dbName / counts / options / tableOrder
 *   media.sql.gz      mysqldump --single-transaction --default-character-set=utf8mb4 --no-tablespaces 的 gzip
 *   user-state.json   用户资产（收藏 / 最近浏览 / 播放进度），**按 content_hash 组织**：
 *                     hash 缺失时同时带出 targetId / albumId / seq 作为回退，导入端优先用 hash 解析
 *   thumbs/           仅 --with-thumbs：复制缩略图根（--thumbs 或 media_root 里 kind='thumbs' 的 path）
 *
 * 身份以内容为准（配合 doc/media_portability.sql）：收藏/进度先写行内 content_hash，
 * 行内为空时用 media_video.id / media_photo(album_id,seq) 反查当前记录的 content_hash。
 *
 * 只读源库（SELECT + mysqldump）；--dry-run 连 mysqldump 都不跑，只打条数与计划。
 * 密码只从 MYSQL_PWD 或 --secret 取，只经子进程环境变量传给 mysql/mysqldump，不打印、不进命令行。
 *
 * 用法：node export-bundle.js --root=<媒体根> --out=<输出目录> [--db=qtools] [--port=3306]
 *        [--mysql=<exe>] [--dump=<exe>] [--secret=<yml>] [--with-thumbs] [--thumbs=<dir>]
 *        [--with-stats] [--dry-run] [--help]
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { spawnSync } = require('child_process');

const TOOL = 'qtools-media-bundle';
const SCHEMA_VERSION = 1;

/** 便携包内的表：先字典表、后内容表；导入端按这个顺序 source/import */
const BASE_TABLES = [
  'media_person', 'media_root', 'media_album', 'media_photo', 'media_video',
  'media_video_tag', 'media_video_actor', 'media_owner', 'media_favorite', 'media_recent',
];
/** 统计表：默认不导（--with-stats 才带上）；media_scan_job 是运行时状态，永远不导 */
const STAT_TABLES = ['media_event', 'media_event_archive', 'media_stats_daily'];

const DEFAULTS = { db: 'qtools', user: 'root', host: '127.0.0.1', port: 3306 };

const HELP = [
  '',
  '用法：node export-bundle.js --root=<媒体根> --out=<输出目录> [选项]',
  '',
  '  --root=<dir>        必填，媒体根目录（只写进 manifest.sourceRoot，不遍历、不读取文件）',
  '  --out=<dir>         必填，便携包输出目录（不存在则创建；同名文件会被覆盖）',
  '  --db=<name>         默认 qtools',
  '  --user=<name>       默认 root',
  '  --host=<host>       默认 127.0.0.1',
  '  --port=<n>          默认 3306',
  '  --mysql=<exe>       mysql 客户端，默认从 PATH 找 mysql',
  '  --dump=<exe>        mysqldump（也可写 --mysqldump=），默认从 PATH 或 mysql 客户端同目录找',
  "  --secret=<file>     密码来源②：secret.yml 里的 spring.datasource.druid.master.password",
  '  --with-thumbs       同时复制缩略图目录到 <out>/thumbs/（缺了也能重建，属可选产物）',
  "  --thumbs=<dir>      缩略图目录；不传则取 media_root 表 kind='thumbs' 的 path（给出即隐含 --with-thumbs）",
  '  --with-stats        额外导出统计表 media_event / media_event_archive / media_stats_daily',
  '  --dry-run           只打印将要产出的内容与条数，不写任何文件（不跑 mysqldump）',
  '  --help              显示本帮助',
  '',
  '密码优先级：',
  '  1) 环境变量 MYSQL_PWD',
  '  2) --secret=<secret.yml 路径>，取其中 spring.datasource.druid.master.password',
  '  3) 两样都没有 -> 报错退出',
  '  取到后只通过子进程环境变量交给 mysql / mysqldump：不打印、不落盘、不进命令行参数。',
  '',
  '示例（完整）：',
  "  node export-bundle.js --root='E:/111/视频' --out=D:/bundle/qtools --port=3307 --with-thumbs --secret=D:/cfg/secret.yml",
  '',
  '产物：',
  '  manifest.json      schemaVersion=1 / generatedAt / sourceRoot / dbName / counts / options / tableOrder',
  '  media.sql.gz       媒体表 mysqldump（--single-transaction --default-character-set=utf8mb4 --no-tablespaces）',
  '  user-state.json    owners / favorites / recent，按 content_hash 组织',
  '  thumbs/            仅 --with-thumbs',
  '',
  '汇总行（机器可解析）：',
  '  export ok videos=N albums=N photos=N favorites=N recent=N out=<绝对路径>',
  '  export dry-run videos=N albums=N photos=N favorites=N recent=N out=<绝对路径>',
  '',
].join('\n');

// ---------------------------------------------------------------- 工具

function log(msg) {
  process.stdout.write(msg + '\n');
}

function warn(msg) {
  process.stderr.write('WARN ' + msg + '\n');
}

function sqlStr(v) {
  if (v === null || v === undefined) return 'NULL';
  const s = String(v).replace(/[\\'\u0000\n\r\u001a]/g, (c) => ({
    '\\': '\\\\', "'": "\\'", '\u0000': '\\0', '\n': '\\n', '\r': '\\r', '\u001a': '\\Z',
  }[c]));
  return "'" + s + "'";
}

function sqlInt(v) {
  const n = Number(v);
  return Number.isFinite(n) ? String(Math.trunc(n)) : 'NULL';
}

/** DECIMAL / 小数字段：NaN 写 NULL */
function sqlNum(v) {
  if (v === null || v === undefined || v === '') return 'NULL';
  const n = Number(v);
  return Number.isFinite(n) ? String(n) : 'NULL';
}

function chunks(arr, size) {
  const out = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

/** 统一成正斜杠，便携包不绑定盘符风格 */
function slash(p) {
  return String(p).replace(/\\/g, '/');
}

function findInPath(name) {
  const exts = process.platform === 'win32'
    ? (process.env.PATHEXT || '.EXE;.CMD;.BAT').split(';').filter(Boolean)
    : [''];
  const dirs = (process.env.PATH || '').split(path.delimiter).filter(Boolean);
  for (const dir of dirs) {
    for (const ext of exts) {
      const candidate = path.join(dir, name + ext);
      try {
        if (fs.statSync(candidate).isFile()) return candidate;
      } catch (e) { /* 不存在，继续找 */ }
    }
  }
  return null;
}

function resolveExe(explicit, name, label) {
  if (explicit) {
    if (!fs.existsSync(explicit)) throw new Error(label + ' 不存在：' + explicit);
    return explicit;
  }
  const found = findInPath(name);
  if (!found) throw new Error('PATH 里找不到 ' + name + '，请用 --' + name + '=<exe> 显式指定');
  return found;
}

// ---------------------------------------------------------------- 密码

function readPassword(o) {
  if (process.env.MYSQL_PWD) return process.env.MYSQL_PWD;
  if (o.secret) return readSecretPassword(o.secret);
  throw new Error('未提供数据库密码：请设置环境变量 MYSQL_PWD，' +
    '或用 --secret=<secret.yml 路径> 指定私密配置');
}

function readSecretPassword(file) {
  const abs = path.resolve(file);
  let raw;
  try {
    raw = fs.readFileSync(abs, 'utf8');
  } catch (e) {
    throw new Error('--secret 指定的文件读不到：' + abs + '（' + (e.code || e.message) + '）');
  }
  const pwd = extractMasterPassword(raw);
  if (!pwd) throw new Error('--secret=' + abs + ' 里找不到 spring.datasource.druid.master.password');
  return pwd;
}

/** 从 YAML 文本取 spring.datasource.druid.master.password（缩进扫描 + 正则，零依赖） */
function extractMasterPassword(text) {
  const flat = text.match(/^[ \t]*spring\.datasource\.druid\.master\.password[ \t]*:[ \t]*(.*)$/m);
  if (flat) return yamlScalar(flat[1]);

  const want = ['spring', 'datasource', 'druid', 'master', 'password'].join('.');
  const stack = [];
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.replace(/^\uFEFF/, '');
    if (!line.trim() || /^[ \t]*#/.test(line)) continue;
    const m = line.match(/^([ \t]*)([A-Za-z0-9_.-]+):(?:[ \t]+(.*))?$/);
    if (!m) continue;
    const indent = m[1].replace(/\t/g, '  ').length;
    const value = m[3] === undefined ? '' : m[3];
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    const trail = stack.map((s) => s.key).concat(m[2]).join('.');
    if (value !== '' && m[2] === 'password' && trail.slice(-want.length) === want) {
      return yamlScalar(value);
    }
    stack.push({ indent, key: m[2] });
  }
  return null;
}

function yamlScalar(raw) {
  const value = String(raw).trim();
  const quote = value[0];
  if (quote === '"' || quote === "'") {
    const end = value.indexOf(quote, 1);
    return end < 0 ? value.slice(1) : value.slice(1, end);
  }
  return value.replace(/[ \t]+#.*$/, '').trim();
}

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS, {
    root: null, out: null, mysql: null, dump: null, secret: null, thumbs: null,
    withThumbs: false, withStats: false, dryRun: false, help: false, ignored: [],
  });
  for (const a of argv) {
    if (a === '--with-thumbs') o.withThumbs = true;
    else if (a === '--with-stats') o.withStats = true;
    else if (a === '--dry-run') o.dryRun = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--root=')) o.root = a.slice('--root='.length);
    else if (a.startsWith('--out=')) o.out = a.slice('--out='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--host=')) o.host = a.slice('--host='.length);
    else if (a.startsWith('--port=')) o.port = a.slice('--port='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--dump=')) o.dump = a.slice('--dump='.length);
    else if (a.startsWith('--mysqldump=')) o.dump = a.slice('--mysqldump='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
    else if (a.startsWith('--thumbs=')) {
      o.thumbs = a.slice('--thumbs='.length);
      o.withThumbs = true;
    } else {
      o.ignored.push(a);
    }
  }
  const port = Number(o.port);
  if (!Number.isFinite(port) || port <= 0) throw new Error('--port 必须是正整数（收到：' + o.port + '）');
  o.port = Math.trunc(port);
  return o;
}

// ---------------------------------------------------------------- mysql

function mysqlArgs(o, withDb) {
  const args = ['-u', o.user, '--host=' + o.host, '--port=' + String(o.port),
    '--default-character-set=utf8mb4'];
  if (withDb !== false) args.push('-D', o.db);
  args.push('--batch', '-N');
  return args;
}

function runMysql(o, mysqlExe, password, sql, withDb) {
  const r = spawnSync(mysqlExe, mysqlArgs(o, withDb), {
    env: Object.assign({}, process.env, { MYSQL_PWD: password }),
    input: sql,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (r.error) throw new Error('调用 mysql 失败：' + r.error.message);
  return r;
}

function unescapeField(s) {
  return String(s).replace(/\\([0tn\\])/g, (_, c) => ({
    '0': '\u0000', t: '\t', n: '\n', '\\': '\\',
  }[c]));
}

function queryRows(o, mysqlExe, password, sql, withDb) {
  const r = runMysql(o, mysqlExe, password, sql, withDb);
  if (r.status !== 0) {
    throw new Error('查询失败（mysql 退出码 ' + r.status + '）：\n' + String(r.stderr || '').trim() +
      '\nSQL：' + sql.split('\n')[0].slice(0, 200));
  }
  const rows = [];
  for (const line of String(r.stdout || '').replace(/\r\n/g, '\n').split('\n')) {
    if (line === '') continue;
    rows.push(line.split('\t').map(unescapeField));
  }
  return rows;
}

/** 库内实际存在哪些 media_* 表（老库可能没有 media_root / 统计表） */
function loadTables(o, mysqlExe, password) {
  const rows = queryRows(o, mysqlExe, password,
    'SELECT table_name FROM information_schema.tables WHERE table_schema = ' + sqlStr(o.db) +
    " AND table_name LIKE 'media%';", false);
  return new Set(rows.map((r) => r[0]));
}

function loadColumns(o, mysqlExe, password, tables) {
  const map = new Map();
  if (!tables.length) return map;
  const rows = queryRows(o, mysqlExe, password,
    'SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = ' +
    sqlStr(o.db) + ' AND table_name IN (' + tables.map(sqlStr).join(',') + ');', false);
  for (const r of rows) {
    if (!map.has(r[0])) map.set(r[0], new Set());
    map.get(r[0]).add(r[1]);
  }
  return map;
}

function hasCol(columns, table, col) {
  const set = columns.get(table);
  return !!set && set.has(col);
}

// ---------------------------------------------------------------- 统计 / 用户资产

function countMedia(o, mysqlExe, password, present) {
  const defs = [
    ['videos', 'media_video'], ['albums', 'media_album'], ['photos', 'media_photo'],
    ['favorites', 'media_favorite'], ['recent', 'media_recent'], ['owners', 'media_owner'],
  ];
  const cols = defs.map((d) => (present.has(d[1]) ? '(SELECT COUNT(*) FROM ' + d[1] + ')' : '0'));
  const row = queryRows(o, mysqlExe, password, 'SELECT ' + cols.join(',') + ';')[0] || [];
  const out = {};
  defs.forEach((d, i) => { out[d[0]] = Number(row[i] || 0); });
  return out;
}

function seqOf(v) {
  return v === '' || v === null || v === undefined || !Number.isFinite(Number(v)) ? null : Math.trunc(Number(v));
}

function numOrNull(v) {
  return v === '' || v === null || v === undefined || !Number.isFinite(Number(v)) ? null : Number(v);
}

/**
 * 用户资产导出。
 * 收藏 / 最近的 content_hash 取值顺序：行内 content_hash -> 目标的当前 content_hash -> NULL（导入端回退 targetId）。
 */
function loadUserState(o, mysqlExe, password, present, columns) {
  const state = { owners: [], favorites: [], recent: [] };

  if (present.has('media_owner')) {
    const rows = queryRows(o, mysqlExe, password,
      "SELECT owner_key, IFNULL(display_name,''), IFNULL(user_id,'') FROM media_owner ORDER BY id;");
    for (const r of rows) {
      state.owners.push({ ownerKey: r[0], displayName: r[1] || null, userId: r[2] === '' ? null : Number(r[2]) });
    }
  }

  const vHash = hasCol(columns, 'media_video', 'content_hash') ? "IFNULL(v.content_hash,'')" : "''";
  const pHash = hasCol(columns, 'media_photo', 'content_hash') ? "IFNULL(p.content_hash,'')" : "''";
  const joinVideo = " LEFT JOIN media_video v ON f.kind = 'video' AND v.id = f.target_id";
  const joinPhoto = " LEFT JOIN media_photo p ON f.kind = 'photo' AND p.album_id = f.album_id AND p.seq = f.seq";

  if (present.has('media_favorite')) {
    const sql = "SELECT f.owner_key, f.kind, IFNULL(f.content_hash,''), IFNULL(f.target_id,'')," +
      " IFNULL(f.album_id,''), IFNULL(f.seq,'')," +
      " IFNULL(DATE_FORMAT(f.created_at,'%Y-%m-%d %H:%i:%s.%f'),'')," +
      ' ' + vHash + ', ' + pHash +
      ' FROM media_favorite f' + joinVideo + joinPhoto + ' ORDER BY f.owner_key, f.id;';
    for (const r of queryRows(o, mysqlExe, password, sql)) {
      state.favorites.push({
        ownerKey: r[0],
        kind: r[1],
        contentHash: r[2] || r[7] || r[8] || null,
        albumId: r[4] || null,
        seq: seqOf(r[5]),
        targetId: r[3] || null,
        createdAt: r[6] || null,
      });
    }
  }

  if (present.has('media_recent')) {
    const sql = "SELECT f.owner_key, f.kind, IFNULL(f.content_hash,''), IFNULL(f.target_id,'')," +
      " IFNULL(f.album_id,''), IFNULL(f.seq,''), IFNULL(f.pos,''), IFNULL(f.dur,'')," +
      " IFNULL(DATE_FORMAT(f.updated_at,'%Y-%m-%d %H:%i:%s.%f'),'')," +
      ' ' + vHash + ', ' + pHash +
      ' FROM media_recent f' + joinVideo + joinPhoto + ' ORDER BY f.owner_key, f.id;';
    for (const r of queryRows(o, mysqlExe, password, sql)) {
      state.recent.push({
        ownerKey: r[0],
        kind: r[1],
        contentHash: r[2] || r[9] || r[10] || null,
        albumId: r[4] || null,
        seq: seqOf(r[5]),
        targetId: r[3] || null,
        pos: numOrNull(r[6]),
        dur: numOrNull(r[7]),
        updatedAt: r[8] || null,
      });
    }
  }

  return state;
}

// ---------------------------------------------------------------- 缩略图

function resolveThumbs(o, mysqlExe, password, present) {
  if (o.thumbs) return path.resolve(o.thumbs);
  if (!present.has('media_root')) return null;
  const rows = queryRows(o, mysqlExe, password,
    "SELECT path FROM media_root WHERE kind = 'thumbs' ORDER BY id LIMIT 1;");
  return rows.length && rows[0][0] ? rows[0][0] : null;
}

function dirStat(dir) {
  let files = 0;
  let bytes = 0;
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(cur, { withFileTypes: true });
    } catch (e) {
      continue;
    }
    for (const e of entries) {
      const abs = path.join(cur, e.name);
      if (e.isDirectory()) stack.push(abs);
      else if (e.isFile()) {
        files += 1;
        try { bytes += fs.statSync(abs).size; } catch (err) { /* 并发删除，忽略 */ }
      }
    }
  }
  return { files, bytes };
}

/** 缩略图是派生资源：节点幂等（有则覆盖），但**不删除**源目录里没有的东西 */
function copyTree(src, dest) {
  if (typeof fs.cpSync === 'function') {
    fs.cpSync(src, dest, { recursive: true, force: true });
    return true;
  }
  const stack = [{ from: src, to: dest }];
  while (stack.length) {
    const cur = stack.pop();
    fs.mkdirSync(cur.to, { recursive: true });
    for (const e of fs.readdirSync(cur.from, { withFileTypes: true })) {
      const from = path.join(cur.from, e.name);
      const to = path.join(cur.to, e.name);
      if (e.isDirectory()) stack.push({ from, to });
      else if (e.isFile()) fs.copyFileSync(from, to);
    }
  }
  return true;
}

// ---------------------------------------------------------------- mysqldump

/** mysqldump：--dump= > PATH > 与 mysql 客户端同目录（Windows 安装包总是并排放） */
function resolveDumpExe(o) {
  if (o.dump) {
    if (!fs.existsSync(o.dump)) throw new Error('mysqldump 不存在：' + o.dump);
    return o.dump;
  }
  const found = findInPath('mysqldump');
  if (found) return found;
  if (o.mysql) {
    const dir = path.dirname(path.resolve(o.mysql));
    for (const ext of (process.platform === 'win32' ? ['.exe', ''] : [''])) {
      const cand = path.join(dir, 'mysqldump' + ext);
      if (fs.existsSync(cand)) return cand;
    }
  }
  throw new Error('找不到 mysqldump：用 --dump=<exe> 指定，或把它放在 mysql 客户端同目录 / PATH 里');
}

function dumpBundle(o, dumpExe, password, tables) {
  const args = ['-u', o.user, '--host=' + o.host, '--port=' + String(o.port),
    '--default-character-set=utf8mb4', '--single-transaction', '--no-tablespaces',
    '--no-create-db', '--skip-add-locks', '--add-drop-table', o.db].concat(tables);
  const r = spawnSync(dumpExe, args, {
    env: Object.assign({}, process.env, { MYSQL_PWD: password }),
    encoding: 'buffer',
    maxBuffer: 512 * 1024 * 1024,
  });
  if (r.error) throw new Error('调用 mysqldump 失败：' + r.error.message);
  if (r.status !== 0) {
    throw new Error('mysqldump 失败（退出码 ' + r.status + '）：\n' +
      Buffer.from(r.stderr || []).toString('utf8').trim());
  }
  const sqlBuf = r.stdout || Buffer.alloc(0);
  return { sqlBuf, gz: zlib.gzipSync(sqlBuf, { level: 9 }) };
}

// ---------------------------------------------------------------- 主流程

function summaryLine(tag, counts, out) {
  return tag + ' videos=' + counts.videos + ' albums=' + counts.albums + ' photos=' + counts.photos +
    ' favorites=' + counts.favorites + ' recent=' + counts.recent + ' out=' + out;
}

function buildManifest(o, root, counts, tables) {
  return {
    schemaVersion: SCHEMA_VERSION,
    generatedAt: new Date().toISOString(),
    tool: TOOL,
    sourceRoot: slash(root),
    dbName: o.db,
    counts,
    options: { withThumbs: o.withThumbs, withStats: o.withStats },
    tableOrder: tables,
  };
}

function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.help) {
    log(HELP);
    return 0;
  }
  if (!o.root) throw new Error('缺少必填参数 --root=<媒体根>（--help 看用法）');
  if (!o.out) throw new Error('缺少必填参数 --out=<输出目录>（--help 看用法）');
  const root = path.resolve(o.root);
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    throw new Error('--root 不是已存在的目录：' + root);
  }
  const out = path.resolve(o.out);
  for (const a of o.ignored) warn('已忽略无法识别的参数：' + a);

  const mysqlExe = resolveExe(o.mysql, 'mysql', 'mysql 客户端');
  const dumpExe = resolveDumpExe(o);
  const password = readPassword(o);

  const present = loadTables(o, mysqlExe, password);
  if (!present.size) throw new Error('库 ' + o.db + ' 里没有 media_* 表，检查 --db / --port / --host');

  const tables = BASE_TABLES.concat(o.withStats ? STAT_TABLES : []).filter((t) => present.has(t));
  const skipped = BASE_TABLES.concat(STAT_TABLES).filter((t) => !present.has(t));
  const columns = loadColumns(o, mysqlExe, password, tables);
  const counts = countMedia(o, mysqlExe, password, present);
  const state = loadUserState(o, mysqlExe, password, present, columns);

  const thumbsSrc = o.withThumbs ? resolveThumbs(o, mysqlExe, password, present) : null;
  if (o.withThumbs && !thumbsSrc) {
    warn("media_root 里没有 kind='thumbs' 的行，也没有给 --thumbs=<dir>：跳过缩略图");
  }
  if (o.withThumbs && thumbsSrc && (!fs.existsSync(thumbsSrc) || !fs.statSync(thumbsSrc).isDirectory())) {
    warn('缩略图目录不存在，跳过：' + thumbsSrc);
  }
  const thumbsDir = thumbsSrc && fs.existsSync(thumbsSrc) && fs.statSync(thumbsSrc).isDirectory()
    ? thumbsSrc : null;
  const thumbsStat = thumbsDir ? dirStat(thumbsDir) : { files: 0, bytes: 0 };

  log('== 便携包导出 ' + o.db + '@' + o.host + ':' + o.port + ' root=' + slash(root));
  log('tables=' + tables.join(','));
  if (skipped.length) log('库内缺失、已跳过：' + skipped.join(','));
  log('user-state owners=' + state.owners.length + ' favorites=' + state.favorites.length +
    ' recent=' + state.recent.length);
  if (thumbsDir) log('thumbs src=' + slash(thumbsDir) + ' files=' + thumbsStat.files + ' bytes=' + thumbsStat.bytes);

  if (o.dryRun) {
    log('== dry-run：不写任何文件（不跑 mysqldump）');
    log('out=' + out);
    log('will-write manifest.json media.sql.gz user-state.json' + (thumbsDir ? ' thumbs/' : ''));
    log(summaryLine('export dry-run', counts, out));
    return 0;
  }

  fs.mkdirSync(out, { recursive: true });

  const manifest = buildManifest(o, root, counts, tables);
  const manifestFile = path.join(out, 'manifest.json');
  fs.writeFileSync(manifestFile, JSON.stringify(manifest, null, 2) + '\n', 'utf8');

  const { sqlBuf, gz } = dumpBundle(o, dumpExe, password, tables);
  const gzFile = path.join(out, 'media.sql.gz');
  fs.writeFileSync(gzFile, gz);
  log('media.sql.gz sql=' + sqlBuf.length + 'B gz=' + gz.length + 'B');

  const stateFile = path.join(out, 'user-state.json');
  fs.writeFileSync(stateFile, JSON.stringify({
    schemaVersion: SCHEMA_VERSION,
    generatedAt: manifest.generatedAt,
    dbName: o.db,
    owners: state.owners,
    favorites: state.favorites,
    recent: state.recent,
  }, null, 2) + '\n', 'utf8');

  if (thumbsDir) {
    const dest = path.join(out, 'thumbs');
    copyTree(thumbsDir, dest);
    log('thumbs/ files=' + thumbsStat.files + ' bytes=' + thumbsStat.bytes);
  }

  log(summaryLine('export ok', counts, out));
  return 0;
}

try {
  process.exitCode = main();
} catch (e) {
  process.stderr.write('ERROR ' + (e && e.message ? e.message : String(e)) + '\n');
  process.exitCode = 1;
}
