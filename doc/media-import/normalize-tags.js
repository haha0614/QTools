#!/usr/bin/env node
'use strict';
/**
 * 标签体系原子化迁移（P6.03）
 * ------------------------------------------------------------
 * 把 media_video_tag 里「非原子」的标签按 tag-map.json 的映射重建成原子标签，
 * 并把 media_tag 字典收敛到「一个标签 = 一种含义 = 一个 facet」。
 *
 * 为什么不是简单的「改名 + 合并」而是「按视频重算目标态」：
 *   现状同一个概念同时挂在多个 facet 下（ASMR 同时是 分类/类型/题材，写真 同时是 分类/类型，
 *   跳舞 同时是 分类/标签/类型），而 media_tag.use_count 的口径是 tag_id —— 历史 facet 行 tag_id 全是
 *   NULL，字典里的 use_count 只是回填时按 name 算的旧快照。只改名会留下「同一个视频里同一个概念
 *   出现 2~3 行」；只删除又会把 use_count 永久打歪（下次管理端增删标签会按 tag_id 重算 → 归零）。
 *   因此本脚本对**每个视频**算出唯一的「目标行集合」（原子名 + 规范 facet + 字典 id），只重建有差异的
 *   视频，没差异的视频一行都不碰 —— 这也让脚本天然幂等：第二遍目标态 = 现状 → 0 变更。
 *
 * 数据口径（实测自 qtools 库）：
 *   media_tag        id / name(唯一) / parent_id / color / use_count
 *   media_video_tag  (video_id, facet, name, tag_id)，唯一键 (video_id, tag_id)
 *
 * 用法：
 *   node normalize-tags.js --dry-run           # 默认就是 dry-run，只打印不写库
 *   node normalize-tags.js --apply             # 先 mysqldump 备份，再单事务写库
 *   node normalize-tags.js --dry-run --json-out=stats.json
 *
 * 密码优先级：MYSQL_PWD 环境变量 > --secret=<yml>；取到后只通过子进程的 MYSQL_PWD 传，
 * 不打印、不落盘、不进命令行参数（与 scan-media.js 同口径）。
 *
 * 退出码：0 成功；1 参数 / 校验 / 写库失败。
 */

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

/** 没有 facet 归属时的兜底值：与后端 MediaOrganizeServiceImpl.FACET_TAG 一致 */
const FACET_FALLBACK = '标签';
/** media_tag.name 是 varchar(32)；media_video_tag.name 是 varchar(120) */
const NAME_MAX = 32;
const ROW_NAME_MAX = 120;
const SCHEMA_ID = 'qtools-tag-map/1';
/** 裸映射写法里不允许当成标签名的键（它们本身就是扩展段） */
const RESERVED_SECTIONS = new Set(['facets', 'drop', 'keep', 'mapping']);

const DEFAULTS = {
  host: '127.0.0.1',
  port: 3307,
  db: 'qtools',
  user: 'root',
  mysql: null,
  mysqldump: null,
  secret: null,
  mapPath: path.join(__dirname, 'tag-map.json'),
  backupDir: path.join(__dirname, '..', 'backup'),
  jsonOut: null,
  apply: false,
};

const HELP = '标签体系原子化迁移（P6.03）\n' +
  '\n' +
  '  node normalize-tags.js [--dry-run | --apply] [选项]\n' +
  '\n' +
  '  --dry-run            只统计并打印（默认，不写库不备份）\n' +
  '  --apply              先备份再单事务写库\n' +
  '  --map=<file>         映射表（默认同目录 tag-map.json）\n' +
  '  --backup-dir=<dir>   备份目录（默认 doc/backup）\n' +
  '  --json-out=<file>    统计 JSON 输出路径\n' +
  '  --mysql=<exe>        mysql 客户端（默认环境变量 MS_MYSQL，再退回 PATH）\n' +
  '  --mysqldump=<exe>    mysqldump（默认与 mysql 同目录，再退回 PATH）\n' +
  '  --host=<h> --port=<p> --db=<d> --user=<u>\n' +
  '  --secret=<yml>       从 secret.yml 读密码（spring.datasource.druid.master.password）\n' +
  '  --root-from-db       无作用（保留参数面一致性）\n' +
  '  -h, --help\n' +
  '\n' +
  '退出码：0 成功；1 失败。\n';

// ------------------------------------------------------------------ 参数

function parseArgs(argv)
{
  const o = Object.assign({}, DEFAULTS, { help: false });
  for (const a of argv)
  {
    if (a === '-h' || a === '--help') o.help = true;
    else if (a === '--dry-run') o.apply = false;
    else if (a === '--apply') o.apply = true;
    else if (a === '--root-from-db') { /* 无作用：标签迁移与库根无关 */ }
    else if (a.startsWith('--host=')) o.host = a.slice('--host='.length);
    else if (a.startsWith('--port=')) o.port = Number(a.slice('--port='.length));
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--mysqldump=')) o.mysqldump = a.slice('--mysqldump='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
    else if (a.startsWith('--map=')) o.mapPath = a.slice('--map='.length);
    else if (a.startsWith('--backup-dir=')) o.backupDir = a.slice('--backup-dir='.length);
    else if (a.startsWith('--json-out=')) o.jsonOut = a.slice('--json-out='.length);
    else throw new Error('不认识的参数：' + a + '（看 --help）');
  }
  if (!Number.isInteger(o.port) || o.port <= 0 || o.port > 65535) throw new Error('--port 非法：' + o.port);
  if (!o.db) throw new Error('--db 不能为空');
  return o;
}

// ------------------------------------------------------------------ 映射表（schema 校验）

function isPlainObject(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }
function jsonOf(v) { try { return JSON.stringify(v); } catch (e) { return String(v); } }
/** 校验失败一律 throw：错误信息带上具体键名 / 下标 / 实际值，别让用户猜 */
function bad(msg) { throw new Error('tag-map 校验失败：' + msg); }

/**
 * 读取并校验映射表。两种写法都收：
 *   1) 裸映射（最简）：{ "cos热舞": ["cos","热舞"], "跳舞": ["热舞"] }
 *   2) 扩展式：{ "$schema":"qtools-tag-map/1", facets:{...}, mapping:{...}, drop:[...], keep:[...] }
 * 扩展式同时定义 facet 归属、删除清单与保留白名单；裸映射只做改名 / 拆分。
 */
function loadMap(file)
{
  const abs = path.resolve(file);
  let text;
  try { text = fs.readFileSync(abs, 'utf8'); }
  catch (e) { throw new Error('映射表读不到：' + abs + '（' + (e.code || e.message) + '）'); }
  let raw;
  try { raw = JSON.parse(text); }
  catch (e) { throw new Error('tag-map.json 解析失败（' + abs + '）：' + e.message); }
  if (!isPlainObject(raw)) bad('顶层必须是对象，实际是 ' + (Array.isArray(raw) ? 'array' : typeof raw));

  const extended = Object.prototype.hasOwnProperty.call(raw, 'mapping') && isPlainObject(raw.mapping);
  const mappingIn = extended ? raw.mapping : raw;
  if (!isPlainObject(mappingIn)) bad(extended ? 'mapping 必须是对象' : '顶层必须是对象');
  if (raw['$schema'] !== undefined && raw['$schema'] !== SCHEMA_ID)
  {
    bad('$schema 必须是 ' + jsonOf(SCHEMA_ID) + '，实际是 ' + jsonOf(raw['$schema']));
  }

  const mapping = Object.create(null);
  for (const key of Object.keys(mappingIn))
  {
    // 裸映射写法下：$ / _ 开头当注释键；facets / drop / keep 是保留段名，仍按段解析
    if (!extended && (key.charAt(0) === '$' || key.charAt(0) === '_' || RESERVED_SECTIONS.has(key)))
    {
      continue;
    }

    if (key.trim() === '') bad('映射键不能为空字符串');
    if (key.length > ROW_NAME_MAX) bad('映射键 ' + jsonOf(key) + ' 超过 media_video_tag.name 的 ' + ROW_NAME_MAX + ' 字符上限');
    const val = mappingIn[key];
    if (!Array.isArray(val)) bad('映射 ' + jsonOf(key) + ' 的值必须是字符串数组，实际是 ' + typeof val);
    if (val.length === 0) bad('映射 ' + jsonOf(key) + ' 的数组为空：要删除标签请写进 drop 列表');
    const atoms = [];
    for (let i = 0; i < val.length; i++)
    {
      const a = val[i];
      if (typeof a !== 'string') bad('映射 ' + jsonOf(key) + ' 的第 ' + (i + 1) + ' 项必须是字符串，实际是 ' + jsonOf(a));
      if (a.trim() === '') bad('映射 ' + jsonOf(key) + ' 的第 ' + (i + 1) + ' 项是空字符串');
      if (a !== a.trim()) bad('映射 ' + jsonOf(key) + ' 的第 ' + (i + 1) + ' 项首尾有空格：' + jsonOf(a));
      if (a.length > NAME_MAX) bad('映射 ' + jsonOf(key) + ' 的目标 ' + jsonOf(a) + ' 超过 media_tag.name 的 ' + NAME_MAX + ' 字符上限');
      if (atoms.indexOf(a) < 0) atoms.push(a);
    }
    mapping[key] = atoms;
  }

  const drop = [];
  if (raw.drop !== undefined)
  {
    if (!Array.isArray(raw.drop)) bad('drop 必须是字符串数组，实际是 ' + typeof raw.drop);
    for (let i = 0; i < raw.drop.length; i++)
    {
      const d = raw.drop[i];
      if (typeof d !== 'string' || d.trim() === '') bad('drop 的第 ' + (i + 1) + ' 项必须是非空字符串，实际是 ' + jsonOf(d));
      if (mapping[d]) bad(jsonOf(d) + ' 同时出现在 mapping 与 drop 里，二选一');
      drop.push(d);
    }
  }

  const keep = [];
  if (raw.keep !== undefined)
  {
    if (!Array.isArray(raw.keep)) bad('keep 必须是字符串数组，实际是 ' + typeof raw.keep);
    for (let i = 0; i < raw.keep.length; i++)
    {
      const k = raw.keep[i];
      if (typeof k !== 'string' || k.trim() === '') bad('keep 的第 ' + (i + 1) + ' 项必须是非空字符串，实际是 ' + jsonOf(k));
      if (drop.indexOf(k) >= 0) bad(jsonOf(k) + ' 同时在 keep 与 drop 里，二选一');
      keep.push(k);
    }
  }

  const facets = Object.create(null);
  if (raw.facets !== undefined)
  {
    if (!isPlainObject(raw.facets)) bad('facets 必须是对象，实际是 ' + typeof raw.facets);
    for (const name of Object.keys(raw.facets))
    {
      const f = raw.facets[name];
      if (typeof f !== 'string' || f.trim() === '') bad('facets[' + jsonOf(name) + '] 必须是非空字符串');
      if (f.length > NAME_MAX) bad('facets[' + jsonOf(name) + '] 超过 ' + NAME_MAX + ' 字符上限');
      if (name.length > NAME_MAX) bad('facets 的键 ' + jsonOf(name) + ' 超过 ' + NAME_MAX + ' 字符上限');
      facets[name] = f;
    }
  }

  return { file: abs, schema: extended ? SCHEMA_ID : 'bare', mapping, drop, keep, facets };
}

/**
 * 名字 → 原子名。映射可以指向另一个映射键（逐层解析，检测环）。
 * 例如 黑丝高跟 → 黑丝 + 高跟；cos热舞 → cos + 热舞。
 */
function atomResolver(map)
{
  const memo = Object.create(null);
  const dropSet = new Set(map.drop);
  function resolve(name, stack)
  {
    if (memo[name]) return memo[name];
    if (dropSet.has(name)) { memo[name] = []; return []; }
    const direct = map.mapping[name];
    if (!direct) { memo[name] = [name]; return [name]; }
    if (stack.indexOf(name) >= 0) throw new Error('映射存在环：' + stack.concat(name).join(' -> '));
    const out = [];
    const next = stack.concat(name);
    for (const a of direct)
    {
      for (const atom of resolve(a, next)) if (out.indexOf(atom) < 0) out.push(atom);
    }
    memo[name] = out;
    return out;
  }
  return (name) => resolve(name, []);
}

// ------------------------------------------------------------------ 数据库

function findInPath(name)
{
  const exts = process.platform === 'win32' ? ['.exe', '.cmd', '.bat', ''] : [''];
  for (const dir of (process.env.PATH || '').split(path.delimiter))
  {
    if (!dir) continue;
    for (const ext of exts)
    {
      const p = path.join(dir, name + ext);
      try { if (fs.existsSync(p) && fs.statSync(p).isFile()) return p; } catch (e) { /* 不可读就跳过 */ }
    }
  }
  return null;
}

/** 优先级：显式参数 > 环境变量 > PATH；显式/环境变量指向不存在直接报错，不回退 */
function resolveExe(explicit, envName, name, label)
{
  if (explicit)
  {
    const abs = path.resolve(explicit);
    if (!fs.existsSync(abs)) throw new Error(label + ' 不存在：' + abs);
    return abs;
  }
  const env = process.env[envName];
  if (env)
  {
    const abs = path.resolve(env);
    if (!fs.existsSync(abs)) throw new Error('环境变量 ' + envName + ' 指向的 ' + label + ' 不存在：' + abs);
    return abs;
  }
  const found = findInPath(name);
  if (!found) throw new Error('找不到 ' + label + '：请用 --' + name + '=<exe> 或环境变量 ' + envName + ' 指定');
  return found;
}

function stripQuotes(s)
{
  const t = String(s).replace(/\s+#.*$/, '');
  if ((t.charAt(0) === "'" && t.charAt(t.length - 1) === "'") || (t.charAt(0) === '"' && t.charAt(t.length - 1) === '"'))
  {
    return t.slice(1, -1);
  }
  return t;
}

function readSecretPassword(file)
{
  const abs = path.resolve(file);
  let text;
  try { text = fs.readFileSync(abs, 'utf8'); }
  catch (e) { throw new Error('--secret 指定的文件读不到：' + abs + '（' + (e.code || e.message) + '）'); }
  const lines = text.split(/\r?\n/);
  let inDatasource = false;
  let inMaster = false;
  let indentDatasource = -1;
  for (const line of lines)
  {
    if (!line.trim() || line.trim().charAt(0) === '#') continue;
    const indent = line.length - line.replace(/^\s+/, '').length;
    const kv = /^\s*([A-Za-z0-9_.-]+)\s*:\s*(.*)$/.exec(line);
    if (!kv) continue;
    const key = kv[1];
    const val = kv[2];
    if (key === 'datasource') { inDatasource = true; indentDatasource = indent; inMaster = false; continue; }
    if (inDatasource && indent <= indentDatasource) { inDatasource = false; inMaster = false; }
    if (!inDatasource) continue;
    if (key === 'master') { inMaster = true; continue; }
    if (inMaster && key === 'password' && val !== '') return stripQuotes(val);
  }
  throw new Error('--secret=' + abs + ' 里找不到 spring.datasource.druid.master.password');
}

function dbPassword(o)
{
  if (process.env.MYSQL_PWD) return process.env.MYSQL_PWD;
  if (o.secret) return readSecretPassword(o.secret);
  throw new Error('未提供数据库密码：请设置环境变量 MYSQL_PWD，或用 --secret=<secret.yml> 指定私密配置');
}

function mysqlArgs(o)
{
  // --batch 负责制表符分隔；--skip-column-names 关掉表头（本机 mysql 8 的 --batch 不带这个默认）；
  // 不加 --raw：让 mysql 自己转义 tab / 换行 / 反斜杠 / NUL，再由 unescapeField 还原，
  // 字段里真出现制表符也不会错位（实测现状没有，但别把解析建立在「数据一定干净」上）
  return ['--host=' + o.host, '--port=' + o.port, '--user=' + o.user,
    '--default-character-set=utf8mb4', '--batch', '--skip-column-names', o.db];
}

function runMysql(o, exe, password, sql)
{
  const r = spawnSync(exe, mysqlArgs(o), {
    input: sql,
    env: Object.assign({}, process.env, { MYSQL_PWD: password }),
    encoding: 'utf8',
    maxBuffer: 1024 * 1024 * 512,
  });
  if (r.error) throw new Error('调用 mysql 失败：' + r.error.message);
  return r;
}

/** mysql --batch 的字段反转义（\0 \t \n \\）：\t 是列分隔，\\ 是字面反斜杠 */
function unescapeField(s)
{
  return s.replace(/\\(.)/g, (m, c) => (c === 't' ? '\t' : c === 'n' ? '\n' : c === '0' ? '\u0000' : c === '\\' ? '\\' : c));
}

function queryRows(o, exe, password, sql, label)
{
  const r = runMysql(o, exe, password, sql);
  if (r.status !== 0)
  {
    throw new Error((label || '查询') + '失败（mysql 退出码 ' + r.status + '）：\n' + String(r.stderr || '').trim());
  }
  const out = [];
  for (const line of String(r.stdout || '').split(/\r?\n/))
  {
    if (!line) continue;
    out.push(line.split('\t').map(unescapeField));
  }
  return out;
}

function execSql(o, exe, password, sql, label)
{
  const r = runMysql(o, exe, password, sql);
  if (r.status !== 0)
  {
    throw new Error((label || '写库') + '失败（mysql 退出码 ' + r.status + '）：\n' + String(r.stderr || '').trim());
  }
  return r;
}

function sqlStr(v)
{
  if (v === null || v === undefined) return 'NULL';
  return "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\u0000/g, '\\0')
    .replace(/\n/g, '\\n').replace(/\r/g, '\\r') + "'";
}

// ------------------------------------------------------------------ 读现状

function loadState(o, exe, password)
{
  const tagRows = queryRows(o, exe, password,
    'select id, name, ifnull(parent_id, 0), use_count from media_tag order by id asc', '读 media_tag');
  const dict = tagRows.map((r) => ({
    id: Number(r[0]), name: r[1], parentId: Number(r[2]) || null, useCount: Number(r[3]),
  }));

  const rowRows = queryRows(o, exe, password,
    'select video_id, facet, name, ifnull(tag_id, 0) from media_video_tag order by video_id asc, name asc', '读 media_video_tag');
  const rows = rowRows.map((r) => ({ videoId: r[0], facet: r[1], name: r[2], tagId: Number(r[3]) || null }));

  const t = queryRows(o, exe, password,
    "select count(*) from information_schema.tables where table_schema = database() and table_name = 'media_album_tag'",
    '检查 media_album_tag');
  const albumTagReady = t.length > 0 && Number(t[0][0]) > 0;

  const v = queryRows(o, exe, password, 'select count(*) from media_video', '读 media_video');
  const videoTotal = v.length ? Number(v[0][0]) : null;

  return { dict, rows, albumTagReady, videoTotal };
}

// ------------------------------------------------------------------ 计划

/** facet 归属：映射表 facets > 现状里该名字出现最多的 facet > 兜底 '标签' */
function buildFacetIndex(rows, facets)
{
  const count = new Map();
  for (const r of rows)
  {
    let m = count.get(r.name);
    if (!m) { m = new Map(); count.set(r.name, m); }
    m.set(r.facet, (m.get(r.facet) || 0) + 1);
  }
  const dominant = new Map();
  for (const [name, m] of count)
  {
    let best = null;
    let bestN = -1;
    for (const [facet, n] of m)
    {
      if (n > bestN || (n === bestN && facet < best)) { best = facet; bestN = n; }
    }
    dominant.set(name, best);
  }
  return (name) => facets[name] || dominant.get(name) || FACET_FALLBACK;
}

function cmpStr(a, b) { return a < b ? -1 : a > b ? 1 : 0; }

function buildPlan(state, map, resolve)
{
  const facetOf = buildFacetIndex(state.rows, map.facets);

  const byVideo = new Map();
  for (const r of state.rows)
  {
    let list = byVideo.get(r.videoId);
    if (!list) { list = []; byVideo.set(r.videoId, list); }
    list.push(r);
  }

  const changedVideos = [];
  const targetByVideo = new Map();
  let deleteRows = 0;
  let insertRows = 0;
  for (const [videoId, current] of byVideo)
  {
    const atoms = [];
    for (const r of current)
    {
      for (const a of resolve(r.name)) if (atoms.indexOf(a) < 0) atoms.push(a);
    }
    atoms.sort((x, y) => (facetOf(x) === facetOf(y) ? cmpStr(x, y) : cmpStr(facetOf(x), facetOf(y))));
    const target = atoms.map((name) => ({ name, facet: facetOf(name) }));
    targetByVideo.set(videoId, target);

    const cur = new Set(current.map((r) => r.facet + '\u0000' + r.name));
    const tgt = new Set(target.map((t) => t.facet + '\u0000' + t.name));
    let same = cur.size === tgt.size;
    if (same)
    {
      for (const k of tgt) if (!cur.has(k)) { same = false; break; }
    }
    if (!same)
    {
      deleteRows += cur.size;
      insertRows += tgt.size;
      changedVideos.push(videoId);
    }
  }

  const needTagNames = new Set();
  for (const [, target] of targetByVideo) for (const t of target) needTagNames.add(t.name);

  // ---- 字典侧的工作量 ----
  // 不能只看 changedVideos：库里完全可能「标签行一行都不用改，但字典里躺着一个 0 引用的孤儿标签」，
  // 或者历史行的 tag_id 还是 NULL（那样 use_count 永远算不准）。这几种情况也必须进写库流程，
  // 否则「连跑两遍」的第一遍会空转、孤儿标签永远删不掉。
  // 名字比较统一按小写：MySQL 侧是 utf8mb4_0900_ai_ci（大小写不敏感），JS 侧必须同口径，
  // 否则 ASMR / asmr 会被当成两个标签（实测库里就有一对）。
  const lower = (s) => String(s).toLowerCase();
  const dictByLower = new Map(state.dict.map((d) => [lower(d.name), d]));
  const keepLower = new Set(map.keep.map(lower));
  const referencedLower = new Set(state.rows.map((r) => lower(r.name)));
  const changedSet = new Set(changedVideos);
  const createTags = Array.from(needTagNames).filter((n) => !dictByLower.has(lower(n))).sort(cmpStr);
  const deleteTags = state.dict
    .filter((d) => !referencedLower.has(lower(d.name)) && !keepLower.has(lower(d.name)))
    .map((d) => d.name).sort(cmpStr);
  const backfillRows = state.rows.filter((r) =>
  {
    if (changedSet.has(r.videoId)) return false; // 这些行会被整块重建，不算「补 tag_id」
    const d = dictByLower.get(lower(r.name));
    return Boolean(d) ? r.tagId !== d.id : true;
  }).length;
  const hasWork = changedVideos.length > 0 || createTags.length > 0 || deleteTags.length > 0 || backfillRows > 0;

  // 迁移「之后」会变成孤儿的旧标签：字典里有、但任何视频的目标集合里都不再出现。
  // 与 deleteTags 分开算：deleteTags 是「现在就没引用」，这一项是「重建完才会没引用」——
  // 预览里不把它算出来的话，dry-run 会说「删除 0 个」，实际会删 30 多个，这正是最该提前看到的数字。
  const targetLower = new Set();
  for (const [, target] of targetByVideo) for (const t of target) targetLower.add(lower(t.name));
  const projectedDeleteTags = state.dict
    .filter((d) => !targetLower.has(lower(d.name)) && !keepLower.has(lower(d.name)))
    .map((d) => d.name).sort(cmpStr);

  return { facetOf, targetByVideo, changedVideos, deleteRows, insertRows, needTagNames,
    createTags, deleteTags, projectedDeleteTags, backfillRows, hasWork };
}

/** 每个旧标签的处置：keep / merge / split / drop，带影响面（视频数 / 行数 / facet 前后） */
function describeOldTags(state, map, resolve, facetOf)
{
  const stat = new Map();
  for (const r of state.rows)
  {
    let s = stat.get(r.name);
    if (!s) { s = { name: r.name, rows: 0, videos: new Set(), facets: new Set() }; stat.set(r.name, s); }
    s.rows += 1;
    s.videos.add(r.videoId);
    s.facets.add(r.facet);
  }
  const dropSet = new Set(map.drop);
  const out = [];
  for (const [name, s] of stat)
  {
    const atoms = resolve(name);
    let disposition = 'keep';
    if (dropSet.has(name)) disposition = 'drop';
    else if (map.mapping[name] && atoms.length !== 1) disposition = 'split';
    else if (map.mapping[name] && atoms[0] !== name) disposition = 'merge';
    else if (atoms.length === 1 && atoms[0] !== name) disposition = 'merge';
    out.push({
      name,
      disposition,
      to: atoms,
      rows: s.rows,
      videos: s.videos.size,
      fromFacets: Array.from(s.facets).sort(),
      toFacets: Array.from(new Set(atoms.map(facetOf))).sort(),
    });
  }
  out.sort((a, b) => (b.rows - a.rows) || cmpStr(a.name, b.name));

  // 映射表里还在、库里已经没有的老标签也列出来（第二遍跑就是这一堆「已完成」）
  const present = new Set(out.map((x) => x.name));
  for (const key of Object.keys(map.mapping))
  {
    if (!present.has(key)) out.push({ name: key, disposition: 'done', to: resolve(key), rows: 0, videos: 0, fromFacets: [], toFacets: [] });
  }
  for (const key of map.drop)
  {
    if (!present.has(key)) out.push({ name: key, disposition: 'done', to: [], rows: 0, videos: 0, fromFacets: [], toFacets: [] });
  }
  return out;
}

// ------------------------------------------------------------------ 备份

function mysqldumpPath(o, mysqlExe)
{
  if (o.mysqldump) return resolveExe(o.mysqldump, 'MS_MYSQLDUMP', 'mysqldump', 'mysqldump');
  const sibling = path.join(path.dirname(mysqlExe), process.platform === 'win32' ? 'mysqldump.exe' : 'mysqldump');
  if (fs.existsSync(sibling)) return sibling;
  return resolveExe(null, 'MS_MYSQLDUMP', 'mysqldump', 'mysqldump');
}

function stampNow()
{
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}

/**
 * 备份 media_tag / media_video_tag（存在 media_album_tag 也带上）到 --backup-dir。
 * 失败直接 throw：宁可不动库，也不能没备份就写。
 */
function backup(o, mysqlExe, password, albumTagReady)
{
  const dump = mysqldumpPath(o, mysqlExe);
  const dir = path.resolve(o.backupDir);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'tags_before_' + stampNow() + '.sql');
  const tables = ['media_tag', 'media_video_tag'];
  if (albumTagReady) tables.push('media_album_tag');
  const args = ['--host=' + o.host, '--port=' + o.port, '--user=' + o.user,
    '--default-character-set=utf8mb4', '--single-transaction', '--no-tablespaces',
    '--skip-add-locks', '--hex-blob',
    '--result-file=' + file, o.db].concat(tables);
  const r = spawnSync(dump, args, { env: Object.assign({}, process.env, { MYSQL_PWD: password }), encoding: 'utf8' });
  if (r.error) throw new Error('调用 mysqldump 失败：' + r.error.message);
  if (r.status !== 0) throw new Error('备份失败（mysqldump 退出码 ' + r.status + '）：\n' + String(r.stderr || '').trim());
  const bytes = fs.existsSync(file) ? fs.statSync(file).size : 0;
  if (bytes <= 0) throw new Error('备份文件为空：' + file);
  return { file, bytes, tables };
}

// ------------------------------------------------------------------ 写库

function chunk(arr, size)
{
  const out = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

function buildCreateTagsSql(names)
{
  if (!names.length) return '';
  const vals = names.map((n) => '(' + sqlStr(n) + ', NULL, 0)').join(',');
  return 'INSERT IGNORE INTO media_tag (name, color, use_count) VALUES ' + vals + ';\n';
}

/** 目标行整块插入；tag_id 用 JOIN media_tag 按名字取，所以不需要先把 id 查出来 */
function buildTargetRowsSql(videoId, target)
{
  const parts = target.map((t) => 'SELECT ' + sqlStr(videoId) + ' AS video_id, ' + sqlStr(t.facet) + ' AS facet, ' + sqlStr(t.name) + ' AS name');
  return 'INSERT INTO media_video_tag (video_id, facet, name, tag_id)\n' +
    'SELECT x.video_id, x.facet, x.name, t.id FROM (\n  ' + parts.join('\n  UNION ALL ') + '\n) x\n' +
    'JOIN media_tag t ON t.name = x.name;\n';
}

/**
 * 写库两步（都被包在同一个 mysql 会话里）：
 *   A) 补齐字典：目标里出现的原子标签缺失就建（不建的话 B 的 JOIN 会静默丢行）
 *   B) 单事务：重建有差异视频的标签行 → 所有行补 tag_id → 重算 use_count → 删无引用标签
 * B 失败时 A 可能已提交（多出几个空标签），下一次 apply 的「删 use_count=0」会自动收回去 —— 自愈。
 */
function applyPlan(o, exe, password, map, plan)
{
  const names = Array.from(plan.needTagNames).sort(cmpStr);
  const created = [];
  if (names.length)
  {
    const existing = new Set(queryRows(o, exe, password, 'select name from media_tag').map((r) => r[0]));
    const missing = names.filter((n) => !existing.has(n));
    for (const c of chunk(missing, 500))
    {
      execSql(o, exe, password, 'SET NAMES utf8mb4;\n' + buildCreateTagsSql(c), '补齐字典');
      created.push.apply(created, c);
    }
  }

  const statements = ['SET NAMES utf8mb4;', 'START TRANSACTION;'];
  for (const videoId of plan.changedVideos)
  {
    const target = plan.targetByVideo.get(videoId);
    statements.push('DELETE FROM media_video_tag WHERE video_id = ' + sqlStr(videoId) + ';');
    if (target.length) statements.push(buildTargetRowsSql(videoId, target).trim());
  }
  // 1) 所有行都指向字典：历史 facet 行 tag_id IS NULL，补上之后 use_count 的口径才和行数一致
  statements.push('UPDATE media_video_tag v JOIN media_tag t ON t.name = v.name SET v.tag_id = t.id WHERE v.tag_id IS NULL OR v.tag_id <> t.id;');
  // 2) 重算 use_count（与后端 MediaOrganizeMapper.recountTagUseCount 同口径：按 tag_id 计数）
  statements.push('UPDATE media_tag t SET t.use_count = (SELECT COUNT(*) FROM media_video_tag v WHERE v.tag_id = t.id);');
  // 3) 删无引用标签（白名单除外）：先摘掉它们的子标签，免得留下指向已删父的孤儿
  const keepClause = map.keep.length ? ' AND name NOT IN (' + map.keep.map(sqlStr).join(',') + ')' : '';
  statements.push('UPDATE media_tag SET parent_id = NULL WHERE parent_id IS NOT NULL AND parent_id IN ' +
    '(SELECT id FROM (SELECT id FROM media_tag t WHERE t.use_count = 0' + keepClause + ') x);');
  statements.push('DELETE FROM media_tag WHERE use_count = 0' + keepClause + ';');
  statements.push('COMMIT;');

  execSql(o, exe, password, statements.join('\n') + '\n', '写库');
  return { created };
}

// ------------------------------------------------------------------ 报告

function pct(a, b) { return b > 0 ? Math.round((a / b) * 1000) / 10 + '%' : '-'; }

/** 终端对齐用：CJK 占两列 */
function pad(s, w)
{
  const str = String(s);
  let width = w;
  for (const ch of str) if (/[\u4e00-\u9fff\uff00-\uffef]/.test(ch)) width -= 1;
  return str + ' '.repeat(Math.max(0, width - str.length));
}

function dispositionText(d)
{
  if (d === 'keep') return '保留';
  if (d === 'merge') return '合并';
  if (d === 'split') return '拆分';
  if (d === 'drop') return '删除';
  return '已完成';
}

function printReport(o, map, state, plan, oldTags, backupInfo, applyResult)
{
  const facetOf = plan.facetOf;
  console.log('');
  console.log('=== 标签原子化迁移 ' + (o.apply ? '[APPLY]' : '[DRY-RUN]') + ' ===');
  console.log('库：' + o.user + '@' + o.host + ':' + o.port + '/' + o.db);
  console.log('映射表：' + map.file);
  console.log('  schema=' + map.schema + '  映射 ' + Object.keys(map.mapping).length + ' 条  facet ' +
    Object.keys(map.facets).length + ' 条  drop ' + map.drop.length + ' 个  keep ' + map.keep.length + ' 个');
  if (backupInfo) console.log('备份：' + backupInfo.file + '（' + backupInfo.bytes + ' 字节；表 ' + backupInfo.tables.join(' ') + '）');
  else console.log('备份：dry-run 不写库、不备份（--apply 会先备份到 ' + path.resolve(o.backupDir) + '）');

  const facetCount = new Map();
  for (const r of state.rows) facetCount.set(r.facet, (facetCount.get(r.facet) || 0) + 1);
  console.log('');
  console.log('--- 现状 ---');
  console.log('media_tag 行数 ' + state.dict.length +
    '   media_video_tag 行数 ' + state.rows.length +
    '   distinct 标签名 ' + new Set(state.rows.map((r) => r.name)).size +
    '   facet 种类 ' + facetCount.size +
    (state.videoTotal !== null ? '   视频总数 ' + state.videoTotal : ''));
  console.log('facet 分布：' + Array.from(facetCount).sort((a, b) => b[1] - a[1]).map((x) => x[0] + '=' + x[1]).join('  '));

  console.log('');
  console.log('--- 每个旧标签的处置（按影响面排序）---');
  const head = ['旧标签', '处置', '映射到', '视频数', '行数', 'facet 前 → 后'];
  const body = oldTags.map((x) => [
    x.name,
    dispositionText(x.disposition),
    x.to.join(' + ') || '-',
    String(x.videos),
    String(x.rows),
    (x.fromFacets.join(',') || '-') + ' -> ' + (x.toFacets.join(',') || '-'),
  ]);
  const width = head.map((h, i) => Math.max(h.length, ...body.map((r) => r[i].length)));
  const fmt = (arr) => arr.map((c, i) => pad(c, width[i])).join('  ');
  console.log(fmt(head));
  console.log(width.map((w) => '-'.repeat(w)).join('  '));
  for (const r of body) console.log(fmt(r));

  console.log('');
  console.log('--- 本次变更 ---');
  console.log('需要重建标签行的视频：' + plan.changedVideos.length +
    (state.videoTotal ? ' / ' + state.videoTotal + '（' + pct(plan.changedVideos.length, state.videoTotal) + '）' : ''));
  console.log('删除行 ' + plan.deleteRows + '   插入行 ' + plan.insertRows + '   净变化 ' + (plan.insertRows - plan.deleteRows));
  console.log('补 tag_id 的遗留行 ' + plan.backfillRows + '   新建字典标签 ' + plan.createTags.length +
    '   现在就没引用的标签 ' + plan.deleteTags.length);
  if (plan.deleteTags.length) console.log('当前无引用、本次会删除的标签：' + plan.deleteTags.join(' '));
  if (plan.projectedDeleteTags.length)
  {
    console.log('迁移后将删除的旧标签 ' + plan.projectedDeleteTags.length + ' 个：' + plan.projectedDeleteTags.join(' '));
  }
  if (!plan.hasWork) console.log('目标态与现状一致：0 变更（幂等）');
  if (applyResult) console.log('新建字典标签：' + (applyResult.created.length ? applyResult.created.join(' ') : '无'));
  else console.log('（dry-run：未写库、未备份）');

  console.log('');
  console.log('--- 迁移后标签集合（原子标签 ' + plan.needTagNames.size + ' 个，按 facet 分组）---');
  const byFacet = new Map();
  for (const name of Array.from(plan.needTagNames).sort(cmpStr))
  {
    const f = facetOf(name);
    if (!byFacet.has(f)) byFacet.set(f, []);
    byFacet.get(f).push(name);
  }
  for (const [f, list] of Array.from(byFacet).sort((a, b) => b[1].length - a[1].length))
  {
    console.log('[' + f + '] ' + list.length + ' 个：' + list.join(' '));
  }

  const merged = oldTags.filter((x) => x.disposition === 'merge' || x.disposition === 'split');
  const dropped = oldTags.filter((x) => x.disposition === 'drop');
  console.log('');
  console.log('合并/拆分掉的旧标签 ' + merged.length + ' 个：' + (merged.map((x) => x.name + '→' + x.to.join('+')).join('，') || '无'));
  console.log('删除的旧标签 ' + dropped.length + ' 个：' + (dropped.map((x) => x.name).join('，') || '无'));
}

// ------------------------------------------------------------------ main

function snapshotOf(state)
{
  return {
    tags: state.dict.length,
    videoTagRows: state.rows.length,
    distinctNames: new Set(state.rows.map((r) => r.name)).size,
    facets: new Set(state.rows.map((r) => r.facet)).size,
    videos: state.videoTotal,
  };
}

function main()
{
  const o = parseArgs(process.argv.slice(2));
  if (o.help) { process.stdout.write(HELP); return 0; }

  const map = loadMap(o.mapPath);
  const resolve = atomResolver(map);
  const mysqlExe = resolveExe(o.mysql, 'MS_MYSQL', 'mysql', 'mysql 客户端');
  const password = dbPassword(o);

  const state = loadState(o, mysqlExe, password);
  const plan = buildPlan(state, map, resolve);
  const oldTags = describeOldTags(state, map, resolve, plan.facetOf);
  const before = snapshotOf(state);

  const missingFacet = Array.from(plan.needTagNames).filter((n) => !map.facets[n]).sort(cmpStr);
  if (missingFacet.length)
  {
    console.log('提示：这些原子标签没在 facets 里声明归属，按现状最多的 facet 兜底：' + missingFacet.join(' '));
  }

  let backupInfo = null;
  let applyResult = null;
  let after = null;
  if (o.apply)
  {
    if (!plan.hasWork)
    {
      console.log('');
      console.log('目标态与现状一致：0 变更，不写库、不产生备份（幂等）。');
    }
    else
    {
      backupInfo = backup(o, mysqlExe, password, state.albumTagReady);
      applyResult = applyPlan(o, mysqlExe, password, map, plan);
      const st = loadState(o, mysqlExe, password);
      after = snapshotOf(st);
      after.deletedTags = state.dict.filter((d) => !st.dict.some((x) => x.id === d.id)).map((d) => d.name).sort(cmpStr);
      after.createdTags = st.dict.filter((d) => !state.dict.some((x) => x.id === d.id)).map((d) => d.name).sort(cmpStr);
      after.nullTagIdRows = st.rows.filter((r) => !r.tagId).length;
    }
  }

  printReport(o, map, state, plan, oldTags, backupInfo, applyResult);

  if (after)
  {
    console.log('');
    console.log('--- 迁移后回读 ---');
    console.log('media_tag 行数：' + before.tags + ' -> ' + after.tags);
    console.log('media_video_tag 行数：' + before.videoTagRows + ' -> ' + after.videoTagRows);
    console.log('distinct 标签名：' + before.distinctNames + ' -> ' + after.distinctNames);
    console.log('facet 种类：' + before.facets + ' -> ' + after.facets);
    console.log('新建标签 ' + after.createdTags.length + ' 个：' + (after.createdTags.join(' ') || '无'));
    console.log('删除标签 ' + after.deletedTags.length + ' 个：' + (after.deletedTags.join(' ') || '无'));
    console.log('tag_id 仍为空的行：' + after.nullTagIdRows);
    if (after.nullTagIdRows > 0) throw new Error('写库后仍有 tag_id 为空的行，字典与标签行不一致');
  }

  const stats = {
    ok: true,
    mode: o.apply ? 'apply' : 'dry-run',
    db: { host: o.host, port: o.port, name: o.db, user: o.user },
    map: { file: map.file, schema: map.schema, mapping: Object.keys(map.mapping).length,
      facets: Object.keys(map.facets).length, drop: map.drop, keep: map.keep },
    backup: backupInfo,
    before,
    changes: {
      changedVideos: plan.changedVideos.length,
      deleteRows: plan.deleteRows,
      insertRows: plan.insertRows,
      createdTags: applyResult ? applyResult.created : [],
      plannedCreateTags: plan.createTags,
      plannedDeleteTags: plan.deleteTags,
      projectedDeleteTags: plan.projectedDeleteTags,
      backfillRows: plan.backfillRows,
      hasWork: plan.hasWork,
      applied: Boolean(applyResult),
    },
    after,
    oldTags,
    atomSet: Array.from(plan.needTagNames).sort(cmpStr).map((name) => ({ name, facet: plan.facetOf(name) })),
    facetGroups: (() => {
      const g = {};
      for (const name of Array.from(plan.needTagNames).sort(cmpStr))
      {
        const f = plan.facetOf(name);
        if (!g[f]) g[f] = [];
        g[f].push(name);
      }
      return g;
    })(),
  };

  if (o.jsonOut)
  {
    const abs = path.resolve(o.jsonOut);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, JSON.stringify(stats, null, 2) + '\n', 'utf8');
    console.log('');
    console.log('统计 JSON：' + abs);
  }
  return 0;
}

// 作为命令行运行时才执行 main；被 require（单测）时只暴露纯函数
if (require.main === module)
{
  try
  {
    process.exitCode = main();
  }
  catch (e)
  {
    console.error('失败：' + (e && e.message ? e.message : e));
    process.exitCode = 1;
  }
}

module.exports = { loadMap, atomResolver, buildPlan, buildFacetIndex, describeOldTags, sqlStr, unescapeField, parseArgs, SCHEMA_ID, FACET_FALLBACK };
