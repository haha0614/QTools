#!/usr/bin/env node
'use strict';
/**
 * 媒体库便携包 · 导入（设计见 doc/媒体库可移植性方案.md §3 P3）
 *
 * 行为（顺序固定）：
 *   1) 读 manifest.json，校验 schemaVersion === 1（不支持 -> 明确报错，退出码 1）
 *   2) --create-schema：先 CREATE DATABASE IF NOT EXISTS，再 source doc/media_schema.sql 与
 *      doc/media_portability.sql（用 mysql 客户端执行，DELIMITER 一并生效）
 *   3) 解压 media.sql.gz 并导入（dump 自带 DROP TABLE IF EXISTS，**表级替换**；重复导入行数不翻倍）
 *   4) --merge-user-state：把 user-state.json 合并进 media_owner / media_favorite / media_recent
 *      · 以 content_hash 为准：先按 hash 解析目标库里的 target_id / album_id / seq（media_video / media_photo），
 *        解析不到才退回包里的 targetId / albumId / seq
 *      · 唯一键冲突用 INSERT ... AS n ON DUPLICATE KEY UPDATE（收藏去重、最近刷新 pos/dur/updated_at）
 *      · 不删除目标库已有行（合并语义）
 *   5) 打印 import ok videos=N albums=N photos=N（count(*) 实时回读）
 *
 * 安全：目标库 media_video 已有行时先告警，必须加 --force 才继续（避免误覆盖）。
 * 零依赖；密码只从 MYSQL_PWD 或 --secret 取，只经子进程环境变量传给 mysql 客户端，不打印。
 *
 * 用法：node import-bundle.js --in=<便携包目录> [--db=qtools] [--port=3306] [--mysql=<exe>]
 *        [--secret=<yml>] [--create-schema] [--merge-user-state] [--force] [--dry-run] [--help]
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { spawnSync } = require('child_process');

const TOOL = 'qtools-media-bundle';
const SCHEMA_VERSION = 1;
/** 建表脚本在 doc/ 下（本脚本在 doc/media-import/ 下） */
const DOC_DIR = path.resolve(__dirname, '..');
const SCHEMA_FILES = ['media_schema.sql', 'media_portability.sql'];

const DEFAULTS = { db: 'qtools', user: 'root', host: '127.0.0.1', port: 3306 };

const HELP = [
  '',
  '用法：node import-bundle.js --in=<便携包目录> [选项]',
  '',
  '  --in=<dir>          必填，export-bundle.js 产出的便携包目录（读 manifest.json / media.sql.gz / user-state.json）',
  '  --db=<name>         目标库，默认 qtools（只允许字母数字下划线，避免拼接成怪 SQL）',
  '  --user=<name>       默认 root',
  '  --host=<host>       默认 127.0.0.1',
  '  --port=<n>          默认 3306',
  '  --mysql=<exe>       mysql 客户端，默认从 PATH 找 mysql',
  "  --secret=<file>     密码来源②：secret.yml 里的 spring.datasource.druid.master.password",
  '  --create-schema     先建库，再 source doc/media_schema.sql 与 doc/media_portability.sql',
  '  --merge-user-state  合并 user-state.json 到 media_owner / media_favorite / media_recent（按 content_hash 解析身份）',
  '  --force             目标库 media_video 已有行时确认继续（否则直接报错退出，避免误覆盖）',
  '  --dry-run           只校验便携包并打印将要做的事，不建库、不导入、不写任何数据',
  '  --help              显示本帮助',
  '',
  '密码优先级：',
  '  1) 环境变量 MYSQL_PWD',
  '  2) --secret=<secret.yml 路径>，取其中 spring.datasource.druid.master.password',
  '  3) 两样都没有 -> 报错退出',
  '  取到后只通过子进程环境变量交给 mysql 客户端：不打印、不落盘、不进命令行参数。',
  '',
  '示例（完整）：',
  "  node import-bundle.js --in=D:/bundle/qtools --db=qtools_new --port=3307 --create-schema --merge-user-state --secret=D:/cfg/secret.yml",
  '',
  '输出（机器可解析）：',
  '  user-state ok owners=R favorites=F recent=C',
  '  import ok videos=N albums=N photos=N',
  '',
  '语义提醒：',
  '  · 表数据来自 dump（含 DROP TABLE IF EXISTS），属**表级替换**；用户资产单独做**合并**，不删除目标库已有行。',
  '  · 导入是合并而非替换：目标库已有的收藏/最近不会因为包里没有而被删掉。',
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
    in: null, mysql: null, secret: null, createSchema: false, mergeUserState: false,
    force: false, dryRun: false, help: false, ignored: [],
  });
  for (const a of argv) {
    if (a === '--create-schema') o.createSchema = true;
    else if (a === '--merge-user-state') o.mergeUserState = true;
    else if (a === '--force') o.force = true;
    else if (a === '--dry-run') o.dryRun = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--in=')) o.in = a.slice('--in='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--host=')) o.host = a.slice('--host='.length);
    else if (a.startsWith('--port=')) o.port = a.slice('--port='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
    else o.ignored.push(a);
  }
  const port = Number(o.port);
  if (!Number.isFinite(port) || port <= 0) throw new Error('--port 必须是正整数（收到：' + o.port + '）');
  o.port = Math.trunc(port);
  if (!/^[A-Za-z0-9_]+$/.test(o.db)) throw new Error('--db 只允许字母/数字/下划线（收到：' + o.db + '）');
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
    maxBuffer: 128 * 1024 * 1024,
  });
  if (r.error) throw new Error('调用 mysql 失败：' + r.error.message);
  return r;
}

function runMysqlOrThrow(o, mysqlExe, password, sql, what, withDb) {
  const r = runMysql(o, mysqlExe, password, sql, withDb);
  if (r.status !== 0) {
    throw new Error(what + '失败（mysql 退出码 ' + r.status + '）：\n' + String(r.stderr || '').trim());
  }
  return r;
}

function unescapeField(s) {
  return String(s).replace(/\\([0tn\\])/g, (_, c) => ({
    '0': '\u0000', t: '\t', n: '\n', '\\': '\\',
  }[c]));
}

function queryRows(o, mysqlExe, password, sql, withDb) {
  const r = runMysqlOrThrow(o, mysqlExe, password, sql, '查询', withDb);
  const rows = [];
  for (const line of String(r.stdout || '').replace(/\r\n/g, '\n').split('\n')) {
    if (line === '') continue;
    rows.push(line.split('\t').map(unescapeField));
  }
  return rows;
}

function schemaExists(o, mysqlExe, password) {
  const rows = queryRows(o, mysqlExe, password,
    'SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ' + sqlStr(o.db) + ';', false);
  return Number(rows[0][0] || 0) > 0;
}

function tableExists(o, mysqlExe, password, table) {
  const rows = queryRows(o, mysqlExe, password,
    'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ' + sqlStr(o.db) +
    ' AND table_name = ' + sqlStr(table) + ';', false);
  return Number(rows[0][0] || 0) > 0;
}

function countTable(o, mysqlExe, password, table) {
  if (!tableExists(o, mysqlExe, password, table)) return 0;
  const rows = queryRows(o, mysqlExe, password, 'SELECT COUNT(*) FROM ' + table + ';');
  return Number(rows[0][0] || 0);
}

// ---------------------------------------------------------------- 便携包

function readManifest(dir) {
  const file = path.join(dir, 'manifest.json');
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    throw new Error('读不到便携包清单：' + file + '（' + (e.code || e.message) + '）');
  }
  let m;
  try {
    m = JSON.parse(raw.replace(/^\uFEFF/, ''));
  } catch (e) {
    throw new Error('manifest.json 不是合法 JSON：' + e.message);
  }
  if (!m || typeof m !== 'object') throw new Error('manifest.json 内容异常（不是对象）');
  if (Number(m.schemaVersion) !== SCHEMA_VERSION) {
    throw new Error('不支持的 schemaVersion=' + JSON.stringify(m.schemaVersion) +
      '（本脚本只支持 ' + SCHEMA_VERSION + '，请用与便携包配套的 import-bundle.js）');
  }
  if (m.tool && m.tool !== TOOL) warn('便携包 tool=' + m.tool + '（不是 ' + TOOL + '），继续尝试导入');
  return m;
}

function readUserState(dir) {
  const file = path.join(dir, 'user-state.json');
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    warn('读不到 user-state.json（' + (e.code || e.message) + '）：跳过用户资产合并');
    return null;
  }
  let s;
  try {
    s = JSON.parse(raw.replace(/^\uFEFF/, ''));
  } catch (e) {
    throw new Error('user-state.json 不是合法 JSON：' + e.message);
  }
  return {
    owners: Array.isArray(s.owners) ? s.owners : [],
    favorites: Array.isArray(s.favorites) ? s.favorites : [],
    recent: Array.isArray(s.recent) ? s.recent : [],
  };
}

/** 列出候选表里含 content_hash 列的那些（老库没有该迁移时不能按 hash 解析） */
function hashCapable(o, mysqlExe, password, tables) {
  const out = new Set();
  if (!tables.length) return out;
  const rows = queryRows(o, mysqlExe, password,
    'SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema = ' + sqlStr(o.db) +
    " AND column_name = 'content_hash' AND table_name IN (" + tables.map(sqlStr).join(',') + ');', false);
  for (const r of rows) out.add(r[0]);
  return out;
}

function bizKey(ownerKey, kind, targetId, albumId, seq) {
  return [
    ownerKey || '', kind || '', targetId || '', albumId || '',
    seq === '' || seq === null || seq === undefined ? '' : String(seq),
  ].join('\u0001');
}

function seqOf(v) {
  return v === '' || v === null || v === undefined || !Number.isFinite(Number(v)) ? null : Math.trunc(Number(v));
}

/** 目标库里这些 owner 已有的业务键 -> id（NULL 参与的唯一键不会触发 ON DUPLICATE，故先查一遍保证幂等） */
function loadExistingKeys(o, mysqlExe, password, table, owners) {
  const map = new Map();
  if (!owners.length) return map;
  for (const part of chunks(owners, 200)) {
    const sql = "SELECT id, owner_key, kind, IFNULL(target_id,''), IFNULL(album_id,''), IFNULL(seq,'') FROM " +
      table + ' WHERE owner_key IN (' + part.map(sqlStr).join(',') + ');';
    for (const r of queryRows(o, mysqlExe, password, sql)) {
      map.set(bizKey(r[1], r[2], r[3], r[4], r[5]), r[0]);
    }
  }
  return map;
}

// ---------------------------------------------------------------- 用户资产合并

function buildOwnerSql(rows) {
  const stmts = [];
  for (const part of chunks(rows, 100)) {
    const values = part.map((r) => '(' + sqlStr(r.ownerKey) + ',' +
      (r.displayName ? sqlStr(r.displayName) : 'NULL') + ',' +
      (r.userId === null || r.userId === undefined ? 'NULL' : sqlInt(r.userId)) + ')').join(',');
    stmts.push('INSERT INTO media_owner (owner_key,display_name,user_id) VALUES ' + values +
      ' AS n ON DUPLICATE KEY UPDATE display_name=COALESCE(n.display_name,media_owner.display_name),' +
      ' user_id=COALESCE(n.user_id,media_owner.user_id);');
  }
  return stmts;
}

function buildFavoriteSql(rows) {
  const stmts = [];
  for (const part of chunks(rows, 100)) {
    const values = part.map((r) => '(' + sqlStr(r.ownerKey) + ',' + sqlStr(r.kind) + ',' +
      (r.targetId ? sqlStr(r.targetId) : 'NULL') + ',' +
      (r.albumId ? sqlStr(r.albumId) : 'NULL') + ',' +
      (r.seq === null ? 'NULL' : sqlInt(r.seq)) + ',' +
      (r.contentHash ? sqlStr(r.contentHash) : 'NULL') + ',' +
      (r.createdAt ? sqlStr(r.createdAt) : 'NOW(3)') + ')').join(',');
    stmts.push('INSERT INTO media_favorite (owner_key,kind,target_id,album_id,seq,content_hash,created_at)' +
      ' VALUES ' + values + ' AS n ON DUPLICATE KEY UPDATE' +
      ' created_at=LEAST(media_favorite.created_at,n.created_at),' +
      ' content_hash=COALESCE(n.content_hash,media_favorite.content_hash);');
  }
  return stmts;
}

function buildRecentInsertSql(rows) {
  const stmts = [];
  for (const part of chunks(rows, 100)) {
    const values = part.map((r) => '(' + sqlStr(r.ownerKey) + ',' + sqlStr(r.kind) + ',' +
      (r.targetId ? sqlStr(r.targetId) : 'NULL') + ',' +
      (r.albumId ? sqlStr(r.albumId) : 'NULL') + ',' +
      (r.seq === null ? 'NULL' : sqlInt(r.seq)) + ',' +
      sqlNum(r.pos) + ',' + sqlNum(r.dur) + ',' +
      (r.contentHash ? sqlStr(r.contentHash) : 'NULL') + ',' +
      (r.updatedAt ? sqlStr(r.updatedAt) : 'NOW(3)') + ')').join(',');
    stmts.push('INSERT INTO media_recent (owner_key,kind,target_id,album_id,seq,pos,dur,content_hash,updated_at)' +
      ' VALUES ' + values + ' AS n ON DUPLICATE KEY UPDATE' +
      ' pos=n.pos, dur=n.dur,' +
      ' content_hash=COALESCE(n.content_hash,media_recent.content_hash),' +
      ' updated_at=GREATEST(media_recent.updated_at,n.updated_at);');
  }
  return stmts;
}

function buildRecentUpdateSql(row, id) {
  const sets = ['pos=' + sqlNum(row.pos), 'dur=' + sqlNum(row.dur)];
  if (row.contentHash) sets.push('content_hash=' + sqlStr(row.contentHash));
  sets.push('updated_at=GREATEST(updated_at,' + (row.updatedAt ? sqlStr(row.updatedAt) : 'NOW(3)') + ')');
  return 'UPDATE media_recent SET ' + sets.join(', ') + ' WHERE id = ' + sqlInt(id) + ';';
}

/**
 * 合并 user-state：
 *   · content_hash 优先：先按 hash 在目标库解析出当前的 target_id / album_id / seq，
 *     解析不到才退回包里记录的 targetId / albumId / seq；
 *   · 收藏去重（已存在即跳过），最近刷新 pos/dur/updated_at；
 *   · 不删除目标库已有行。
 */
function mergeUserState(o, mysqlExe, password, state) {
  const owners = state.owners.filter((r) => r && r.ownerKey);
  const favorites = state.favorites.filter((r) => r && r.ownerKey && (r.kind === 'video' || r.kind === 'photo'));
  const recent = state.recent.filter((r) => r && r.ownerKey && (r.kind === 'video' || r.kind === 'photo'));
  const capable = hashCapable(o, mysqlExe, password, ['media_video', 'media_photo']);

  const hashes = new Set();
  for (const r of favorites.concat(recent)) if (r.contentHash) hashes.add(String(r.contentHash));

  const videoMap = new Map(); // hash -> media_video.id
  const photoMap = new Map(); // hash -> { albumId, seq }
  const list = Array.from(hashes);
  if (list.length && capable.has('media_video')) {
    for (const part of chunks(list, 200)) {
      for (const r of queryRows(o, mysqlExe, password,
        'SELECT content_hash, id FROM media_video WHERE content_hash IN (' + part.map(sqlStr).join(',') + ');')) {
        videoMap.set(r[0], r[1]);
      }
    }
  }
  if (list.length && capable.has('media_photo')) {
    for (const part of chunks(list, 200)) {
      for (const r of queryRows(o, mysqlExe, password,
        'SELECT content_hash, album_id, seq FROM media_photo WHERE content_hash IN (' +
        part.map(sqlStr).join(',') + ') ORDER BY content_hash, album_id, seq;')) {
        if (!photoMap.has(r[0])) photoMap.set(r[0], { albumId: r[1], seq: Number(r[2]) });
      }
    }
  }
  if (list.length && !videoMap.size && !photoMap.size) {
    warn('目标库里没解析到任何 content_hash，退回包里的 targetId / albumId / seq');
  }

  const skipped = { video: 0, photo: 0 };
  const favRows = [];
  for (const f of favorites) {
    const kind = f.kind;
    const hash = f.contentHash ? String(f.contentHash) : null;
    let targetId = f.targetId ? String(f.targetId) : null;
    let albumId = f.albumId ? String(f.albumId) : null;
    let seq = seqOf(f.seq);
    if (hash && kind === 'video' && videoMap.has(hash)) targetId = videoMap.get(hash);
    if (hash && kind === 'photo' && photoMap.has(hash)) {
      albumId = photoMap.get(hash).albumId;
      seq = photoMap.get(hash).seq;
    }
    if (kind === 'video' && !targetId) { skipped.video += 1; continue; }
    if (kind === 'photo' && (!albumId || seq === null)) { skipped.photo += 1; continue; }
    favRows.push({
      ownerKey: f.ownerKey, kind,
      targetId: kind === 'video' ? targetId : null,
      albumId: kind === 'photo' ? albumId : null,
      seq: kind === 'photo' ? seq : null,
      contentHash: hash,
      createdAt: f.createdAt ? String(f.createdAt) : null,
    });
  }

  const recentRows = [];
  for (const r of recent) {
    const kind = r.kind;
    const hash = r.contentHash ? String(r.contentHash) : null;
    let targetId = r.targetId ? String(r.targetId) : null;
    let albumId = r.albumId ? String(r.albumId) : null;
    let seq = seqOf(r.seq);
    if (hash && kind === 'video' && videoMap.has(hash)) targetId = videoMap.get(hash);
    if (hash && kind === 'photo' && photoMap.has(hash)) {
      albumId = photoMap.get(hash).albumId;
      seq = photoMap.get(hash).seq;
    }
    if (kind === 'video' && !targetId) { skipped.video += 1; continue; }
    if (kind === 'photo' && (!albumId || seq === null)) { skipped.photo += 1; continue; }
    recentRows.push({
      ownerKey: r.ownerKey, kind,
      targetId: kind === 'video' ? targetId : null,
      albumId: kind === 'photo' ? albumId : null,
      seq: kind === 'photo' ? seq : null,
      pos: r.pos === undefined ? null : r.pos,
      dur: r.dur === undefined ? null : r.dur,
      contentHash: hash,
      updatedAt: r.updatedAt ? String(r.updatedAt) : null,
    });
  }

  const ownerKeys = Array.from(new Set(owners.map((r) => r.ownerKey)));
  const favExisting = loadExistingKeys(o, mysqlExe, password, 'media_favorite', ownerKeys);
  const recentExisting = loadExistingKeys(o, mysqlExe, password, 'media_recent', ownerKeys);

  const favNew = [];
  let favDup = 0;
  for (const r of favRows) {
    const key = bizKey(r.ownerKey, r.kind, r.targetId, r.albumId, r.seq);
    if (favExisting.has(key)) { favDup += 1; continue; }
    favExisting.set(key, 'pending');
    favNew.push(r);
  }
  const recentIns = [];
  const recentUpd = [];
  for (const r of recentRows) {
    const key = bizKey(r.ownerKey, r.kind, r.targetId, r.albumId, r.seq);
    const id = recentExisting.get(key);
    if (id && id !== 'pending') recentUpd.push({ row: r, id });
    else { recentExisting.set(key, 'pending'); recentIns.push(r); }
  }

  const stmts = []
    .concat(buildOwnerSql(owners))
    .concat(buildFavoriteSql(favNew))
    .concat(buildRecentInsertSql(recentIns))
    .concat(recentUpd.map((u) => buildRecentUpdateSql(u.row, u.id)));

  if (stmts.length) {
    runMysqlOrThrow(o, mysqlExe, password, stmts.join('\n') + '\n', '合并用户资产');
  }

  log('user-state ok owners=' + owners.length + ' favorites=' + favNew.length + ' recent=' +
    (recentIns.length + recentUpd.length));
  log('== user-state 细节 收藏已存在=' + favDup + ' 最近新增=' + recentIns.length +
    ' 最近刷新=' + recentUpd.length + ' 跳过=' + (skipped.video + skipped.photo) +
    '（video=' + skipped.video + ' photo=' + skipped.photo + '）');
}

// ---------------------------------------------------------------- 建库建表

function ensureDatabase(o, mysqlExe, password) {
  runMysqlOrThrow(o, mysqlExe, password,
    'CREATE DATABASE IF NOT EXISTS ' + o.db +
    ' DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;\n', '建库', false);
  log('== 建库 ' + o.db + '（IF NOT EXISTS）');
}

function sourceSqlFile(o, mysqlExe, password, file) {
  const abs = path.resolve(file);
  if (!fs.existsSync(abs)) throw new Error('找不到建表脚本：' + abs);
  const r = runMysql(o, mysqlExe, password, 'source ' + slash(abs) + '\n');
  if (r.status !== 0) {
    throw new Error('执行 ' + path.basename(abs) + ' 失败（mysql 退出码 ' + r.status + '）：\n' +
      String(r.stderr || '').trim());
  }
  log('== source ' + path.basename(abs));
}

// ---------------------------------------------------------------- 主流程

function targetCounts(o, mysqlExe, password) {
  return {
    videos: countTable(o, mysqlExe, password, 'media_video'),
    albums: countTable(o, mysqlExe, password, 'media_album'),
    photos: countTable(o, mysqlExe, password, 'media_photo'),
  };
}

function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.help) {
    log(HELP);
    return 0;
  }
  if (!o.in) throw new Error('缺少必填参数 --in=<便携包目录>（--help 看用法）');
  const dir = path.resolve(o.in);
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    throw new Error('--in 不是已存在的目录：' + dir);
  }
  for (const a of o.ignored) warn('已忽略无法识别的参数：' + a);

  const manifest = readManifest(dir);
  log('== 便携包 ' + slash(dir) + ' schemaVersion=' + manifest.schemaVersion +
    ' tool=' + (manifest.tool || '?') + ' generatedAt=' + (manifest.generatedAt || '?'));
  log('manifest counts=' + JSON.stringify(manifest.counts || {}) +
    ' sourceRoot=' + (manifest.sourceRoot || '?') + ' dbName=' + (manifest.dbName || '?'));

  const gzFile = path.join(dir, 'media.sql.gz');
  if (!fs.existsSync(gzFile)) throw new Error('便携包里没有 media.sql.gz：' + gzFile);

  const mysqlExe = resolveExe(o.mysql, 'mysql', 'mysql 客户端');
  const password = readPassword(o);

  const dbExists = schemaExists(o, mysqlExe, password);
  const existingVideos = dbExists ? countTable(o, mysqlExe, password, 'media_video') : 0;
  if (existingVideos > 0 && !o.force) {
    warn('目标库 ' + o.db + ' 的 media_video 已有 ' + existingVideos + ' 行；' +
      '导入会 DROP 并重建媒体表，必须加 --force 才继续');
    if (!o.dryRun) throw new Error('已中止：目标库 media_video 已有 ' + existingVideos + ' 行，确认覆盖请加 --force');
  } else if (existingVideos > 0) {
    warn('--force：目标库 media_video 已有 ' + existingVideos + ' 行，将被便携包内容替换（用户资产仍为合并）');
  }
  if (!dbExists && !o.createSchema) {
    throw new Error('目标库 ' + o.db + ' 不存在：加 --create-schema 让脚本建库建表，或先手工建库');
  }

  const before = dbExists ? targetCounts(o, mysqlExe, password) : { videos: 0, albums: 0, photos: 0 };

  if (o.dryRun) {
    log('== dry-run：不建库、不导入、不写任何数据');
    log('target db=' + o.db + ' exists=' + (dbExists ? 1 : 0) + ' videos=' + before.videos +
      ' albums=' + before.albums + ' photos=' + before.photos);
    log('will createSchema=' + (o.createSchema ? 1 : 0) + ' import media.sql.gz=' +
      fs.statSync(gzFile).size + 'B mergeUserState=' + (o.mergeUserState ? 1 : 0));
    return 0;
  }

  if (o.createSchema) {
    ensureDatabase(o, mysqlExe, password);
    for (const f of SCHEMA_FILES) sourceSqlFile(o, mysqlExe, password, path.join(DOC_DIR, f));
  }

  const gzBuf = fs.readFileSync(gzFile);
  let sqlBuf;
  try {
    sqlBuf = zlib.gunzipSync(gzBuf);
  } catch (e) {
    throw new Error('media.sql.gz 解压失败：' + e.message);
  }
  log('== 导入 media.sql.gz gz=' + gzBuf.length + 'B sql=' + sqlBuf.length + 'B -> ' + o.db);
  runMysqlOrThrow(o, mysqlExe, password,
    sqlBuf.toString('utf8'), '导入 media.sql.gz');

  if (o.mergeUserState) {
    const state = readUserState(dir);
    if (state) mergeUserState(o, mysqlExe, password, state);
  }

  const counts = targetCounts(o, mysqlExe, password);
  log('import ok videos=' + counts.videos + ' albums=' + counts.albums + ' photos=' + counts.photos);
  return 0;
}

try {
  process.exitCode = main();
} catch (e) {
  process.stderr.write('ERROR ' + (e && e.message ? e.message : String(e)) + '\n');
  process.exitCode = 1;
}
