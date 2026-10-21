#!/usr/bin/env node
'use strict';
/**
 * 媒体站 data/user.json -> MySQL qtools.media_owner / media_favorite / media_recent 迁移脚本（M2）
 *
 * 特性：零依赖（只用 node: 内置模块）、可重复执行（幂等）、--dry-run 只打印行数、
 *       导入后用 API（X-Ms-Owner: legacy）抽检。用法：node import-user.js --help
 *
 * 幂等做法：把 owner_key='legacy' 名下的收藏/最近浏览整体 DELETE 再按源数据重插
 * （这两张表的唯一键含 NULL 列，MySQL 不判 NULL 重复 -> ON DUPLICATE KEY 无法去重）。
 * 因此重跑后条数恒定；legacy 这个 owner 只属于迁移脚本，不存在覆盖用户手写数据的风险。
 */

const fs = require('fs');
const path = require('path');
const http = require('http');
const { spawnSync } = require('child_process');

const SCRIPT_DIR = __dirname;

const DEFAULTS = {
  source: 'E:\\111\\视频\\_媒体站\\data\\user.json',
  owner: 'legacy',
  mysql: 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
  db: 'qtools',
  user: 'root',
  secret: 'E:\\111\\project\\QTools\\QTools\\qtools-admin\\src\\main\\resources\\application-secret.yml',
  api: 'http://127.0.0.1:8080/api/media',
  out: SCRIPT_DIR,
  recentMax: 50,
  dryRun: false,
  skipVerify: false,
  skipApi: false,
};

const HELP = `
用法：node import-user.js [选项]

  迁移 E:\\111\\视频\\_媒体站\\data\\user.json 到
  media_owner(owner_key='legacy') / media_favorite / media_recent。

  --dry-run          只解析源文件并打印将写入的行数，不连库、不写文件
  --skip-verify      跳过导入后的条数/重复/悬空校验
  --skip-api         跳过 X-Ms-Owner 的 API 抽检
  --source=<file>    user.json 路径
  --owner=<key>      owner_key，默认 legacy
  --mysql=<exe>      mysql 客户端路径
  --db=<name> --user=<name> --secret=<yml>
  --api=<url>        API 根，默认 http://127.0.0.1:8080/api/media
  --out=<dir>        生成的 .sql 输出目录（默认本目录）

MySQL 密码：优先 $env:MYSQL_PWD，否则从 --secret 指定的 yml 的
spring.datasource.druid.master.password 读取，仅通过子进程环境变量传递，不落盘、不打印。
`;

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS);
  for (const a of argv) {
    if (a === '--dry-run') o.dryRun = true;
    else if (a === '--skip-verify') o.skipVerify = true;
    else if (a === '--skip-api') o.skipApi = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--source=')) o.source = a.slice('--source='.length);
    else if (a.startsWith('--owner=')) o.owner = a.slice('--owner='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
    else if (a.startsWith('--api=')) o.api = a.slice('--api='.length).replace(/\/+$/, '');
    else if (a.startsWith('--out=')) o.out = a.slice('--out='.length);
    else throw new Error('未知参数：' + a);
  }
  return o;
}

// ---------------------------------------------------------------- 工具

function log(msg) {
  process.stdout.write(msg + '\n');
}

function sqlStr(v) {
  if (v === null || v === undefined) return 'NULL';
  const s = String(v).replace(/[\\'\u0000\n\r\u001a]/g, (c) => ({
    '\\': '\\\\', "'": "\\'", '\u0000': '\\0', '\n': '\\n', '\r': '\\r', '\u001a': '\\Z',
  }[c]));
  return "'" + s + "'";
}

function sqlInt(v) {
  if (v === null || v === undefined || v === '') return 'NULL';
  const n = Number(v);
  return Number.isFinite(n) ? String(Math.trunc(n)) : 'NULL';
}

function sqlDec(v) {
  if (v === null || v === undefined || v === '') return 'NULL';
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(2) : 'NULL';
}

/** 源数据的 t（毫秒时间戳）落成 DATETIME(3)，保留原始先后顺序 */
function sqlTime(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return 'current_timestamp(3)';
  return 'from_unixtime(' + (n / 1000).toFixed(3) + ')';
}

function readPassword(o) {
  if (process.env.MYSQL_PWD) return process.env.MYSQL_PWD;
  const raw = fs.readFileSync(o.secret, 'utf8');
  const m = raw.match(/master:\s*\r?\n\s+password:\s*(\S+)/);
  if (!m) throw new Error('无法从 ' + o.secret + ' 的 spring.datasource.druid.master 读取 password');
  return m[1];
}

function mysqlRun(o, password, sql, stdinFd) {
  const args = ['-u', o.user, '--default-character-set=utf8mb4', '-D', o.db, '--batch', '-N'];
  if (!stdinFd) args.push('-e', sql);
  const env = Object.assign({}, process.env, { MYSQL_PWD: password });
  const r = spawnSync(o.mysql, args, {
    env,
    encoding: 'utf8',
    stdio: [stdinFd === undefined ? 'ignore' : stdinFd, 'pipe', 'pipe'],
    maxBuffer: 32 * 1024 * 1024,
  });
  if (r.error) throw new Error('调用 mysql 失败：' + r.error.message);
  return r;
}

function query(o, password, sql) {
  const r = mysqlRun(o, password, sql);
  if (r.status !== 0) {
    throw new Error('查询失败：\n' + (r.stderr || '').trim() + '\nSQL: ' + sql.slice(0, 300));
  }
  const text = (r.stdout || '').replace(/\r/g, '').replace(/\n+$/, '');
  if (!text) return [];
  return text.split('\n').map((line) => line.split('\t'));
}

// ---------------------------------------------------------------- 源数据

function loadSource(o) {
  const raw = fs.readFileSync(o.source, 'utf8').replace(/^\uFEFF/, '');
  const data = JSON.parse(raw);
  const warn = [];
  const profile = data.profile && typeof data.profile === 'object' ? data.profile : {};
  const nick = typeof profile.nick === 'string' && profile.nick.trim() ? profile.nick.trim() : '我';
  if (!Array.isArray(data.favorites)) {
    warn.push('源文件缺少 favorites[]，按空数组处理');
  }
  const recent = data.recent && typeof data.recent === 'object' ? data.recent : {};
  if (!Array.isArray(recent.videos)) warn.push('源文件缺少 recent.videos[]，按空数组处理');
  if (!Array.isArray(recent.photos)) warn.push('源文件缺少 recent.photos[]，按空数组处理');
  // profile.avatar 是 data URI，原样进 media_owner.avatar(MEDIUMTEXT)；空/非字符串一律当没有
  const avatar = typeof profile.avatar === 'string' && profile.avatar.trim() ? profile.avatar : null;
  return {
    nick,
    avatar,
    favorites: Array.isArray(data.favorites) ? data.favorites : [],
    recentVideos: Array.isArray(recent.videos) ? recent.videos : [],
    recentPhotos: Array.isArray(recent.photos) ? recent.photos : [],
    warn,
  };
}

// 唯一键：video=id，photo=album#i（与 media_favorite/media_recent 的业务唯一键一致）
function itemKey(it) {
  if (!it || typeof it !== 'object') return null;
  if (it.kind === 'video') return typeof it.id === 'string' && it.id ? 'v:' + it.id : null;
  if (it.kind === 'photo') {
    if (typeof it.album !== 'string' || !it.album) return null;
    if (!Number.isInteger(it.i) || it.i < 0) return null;
    return 'p:' + it.album + '#' + it.i;
  }
  return null;
}

/** 去重 + 形状校验，保留源文件顺序（新 -> 旧） */
function collect(list, opts) {
  const out = [];
  const seen = new Set();
  const stats = { invalid: 0, dup: 0, beyond: 0, untyped: 0 };
  for (const it of list) {
    let item = it;
    if (item && typeof item === 'object' && !item.kind) {
      // recent.videos[] / recent.photos[] 里可能不带 kind，按所属数组补上
      item = Object.assign({ kind: opts.kind }, item);
      stats.untyped += 1;
    }
    const key = itemKey(item);
    if (!key) {
      stats.invalid += 1;
      continue;
    }
    if (seen.has(key)) {
      stats.dup += 1;
      continue;
    }
    if (opts.max && out.length >= opts.max) {
      stats.beyond += 1;
      continue;
    }
    seen.add(key);
    out.push(item);
  }
  return { items: out, stats };
}

// ---------------------------------------------------------------- 生成 SQL

function buildSql(src, o) {
  const fav = collect(src.favorites, { max: 0 });
  const rv = collect(src.recentVideos, { kind: 'video', max: o.recentMax });
  const rp = collect(src.recentPhotos, { kind: 'photo', max: o.recentMax });

  const lines = [];
  lines.push('-- 由 media-import/import-user.js 生成，请勿手工编辑');
  lines.push('SET NAMES utf8mb4;');
  lines.push('SET SESSION sql_mode = DEFAULT;');
  lines.push('START TRANSACTION;');
  lines.push(
    'INSERT INTO media_owner (owner_key, display_name, avatar, last_seen) VALUES (' +
    sqlStr(o.owner) + ', ' + sqlStr(src.nick) + ', ' + sqlStr(src.avatar) + ', now()) AS n ' +
    'ON DUPLICATE KEY UPDATE display_name = n.display_name, avatar = n.avatar, last_seen = n.last_seen;'
  );
  // 整体重建：唯一键含 NULL 列 -> 无法 upsert 去重，删除后重插才能保证重跑幂等
  lines.push('DELETE FROM media_favorite WHERE owner_key = ' + sqlStr(o.owner) + ';');
  lines.push('DELETE FROM media_recent WHERE owner_key = ' + sqlStr(o.owner) + ';');

  if (fav.items.length) {
    const rows = fav.items.map((it) => '(' + [
      sqlStr(o.owner),
      sqlStr(it.kind),
      it.kind === 'video' ? sqlStr(String(it.id)) : 'NULL',
      it.kind === 'photo' ? sqlStr(String(it.album)) : 'NULL',
      it.kind === 'photo' ? sqlInt(it.i) : 'NULL',
      sqlTime(it.t),
    ].join(',') + ')');
    lines.push('INSERT INTO media_favorite (owner_key,kind,target_id,album_id,seq,created_at) VALUES ' +
      rows.join(',') + ';');
  }
  const recentRows = (items, kind) => items.map((it) => '(' + [
    sqlStr(o.owner),
    sqlStr(kind),
    kind === 'video' ? sqlStr(String(it.id)) : 'NULL',
    kind === 'photo' ? sqlStr(String(it.album)) : 'NULL',
    kind === 'photo' ? sqlInt(it.i) : 'NULL',
    kind === 'video' ? sqlDec(it.pos) : 'NULL',
    kind === 'video' ? sqlDec(it.dur) : 'NULL',
    sqlTime(it.t),
  ].join(',') + ')');
  const recentSql = [];
  if (rv.items.length) recentSql.push(...recentRows(rv.items, 'video'));
  if (rp.items.length) recentSql.push(...recentRows(rp.items, 'photo'));
  if (recentSql.length) {
    lines.push('INSERT INTO media_recent (owner_key,kind,target_id,album_id,seq,pos,dur,updated_at) VALUES ' +
      recentSql.join(',') + ';');
  }
  lines.push('COMMIT;');

  return {
    sql: lines.join('\n') + '\n',
    stats: {
      favorites: fav.items.length,
      recentVideos: rv.items.length,
      recentPhotos: rp.items.length,
      total: fav.items.length + rv.items.length + rp.items.length,
      dropped: fav.stats.invalid + rv.stats.invalid + rp.stats.invalid,
      duplicates: fav.stats.dup + rv.stats.dup + rp.stats.dup,
      beyond: rv.stats.beyond + rp.stats.beyond,
    },
  };
}

// ---------------------------------------------------------------- 校验

function verify(o, password, src, stats) {
  const checks = [];
  const add = (name, ok, expect, actual) => checks.push({ name, ok, expect: String(expect), actual: String(actual) });

  const favRows = query(o, password,
    'SELECT kind, COUNT(*) FROM media_favorite WHERE owner_key = ' + sqlStr(o.owner) + ' GROUP BY kind');
  const recentRows = query(o, password,
    'SELECT kind, COUNT(*) FROM media_recent WHERE owner_key = ' + sqlStr(o.owner) + ' GROUP BY kind');
  const fav = { video: 0, photo: 0 };
  for (const [kind, n] of favRows) fav[kind] = Number(n);
  const recent = { video: 0, photo: 0 };
  for (const [kind, n] of recentRows) recent[kind] = Number(n);

  const srcFav = { video: 0, photo: 0 };
  for (const it of collect(src.favorites, { max: 0 }).items) srcFav[it.kind] += 1;
  const srcRv = collect(src.recentVideos, { kind: 'video', max: o.recentMax }).items.length;
  const srcRp = collect(src.recentPhotos, { kind: 'photo', max: o.recentMax }).items.length;

  add('media_favorite video 条数', fav.video === srcFav.video, srcFav.video, fav.video);
  add('media_favorite photo 条数', fav.photo === srcFav.photo, srcFav.photo, fav.photo);
  add('media_favorite 合计', fav.video + fav.photo === stats.favorites, stats.favorites, fav.video + fav.photo);
  add('media_recent video 条数', recent.video === srcRv, srcRv, recent.video);
  add('media_recent photo 条数', recent.photo === srcRp, srcRp, recent.photo);
  add('media_recent 合计', recent.video + recent.photo === stats.recentVideos + stats.recentPhotos,
    stats.recentVideos + stats.recentPhotos, recent.video + recent.photo);

  const owner = query(o, password,
    'SELECT owner_key, COALESCE(display_name,\'\'), COALESCE(CHAR_LENGTH(avatar),0) ' +
    'FROM media_owner WHERE owner_key = ' + sqlStr(o.owner));
  add('media_owner 行存在且昵称对齐源 profile.nick',
    owner.length === 1 && owner[0][1] === src.nick, src.nick, owner.length ? owner[0][1] : '(缺失)');
  const srcAvatarLen = src.avatar ? Array.from(src.avatar).length : 0;
  const dbAvatarLen = owner.length ? Number(owner[0][2]) : -1;
  add('media_owner.avatar 与源 profile.avatar 一致（按字符数）',
    dbAvatarLen === srcAvatarLen, srcAvatarLen + ' 字符', dbAvatarLen + ' 字符');

  // 重复检测：唯一键含 NULL 时 MySQL 不判重，这里用业务键自己查
  const dupFav = query(o, password,
    'SELECT kind, COALESCE(target_id, album_id), COALESCE(seq,-1), COUNT(*) c FROM media_favorite ' +
    'WHERE owner_key = ' + sqlStr(o.owner) + ' GROUP BY 1,2,3 HAVING c > 1');
  const dupRecent = query(o, password,
    'SELECT kind, COALESCE(target_id, album_id), COALESCE(seq,-1), COUNT(*) c FROM media_recent ' +
    'WHERE owner_key = ' + sqlStr(o.owner) + ' GROUP BY 1,2,3 HAVING c > 1');
  add('media_favorite 无重复业务键', dupFav.length === 0, 0, dupFav.length);
  add('media_recent 无重复业务键', dupRecent.length === 0, 0, dupRecent.length);

  // 悬空引用：指向 media_video / media_photo 里不存在的媒体
  const danglingFav = query(o, password,
    'SELECT COUNT(*) FROM media_favorite f WHERE f.owner_key = ' + sqlStr(o.owner) + ' AND (' +
    '(f.kind=\'video\' AND NOT EXISTS (SELECT 1 FROM media_video v WHERE v.id=f.target_id)) OR ' +
    '(f.kind=\'photo\' AND NOT EXISTS (SELECT 1 FROM media_photo p WHERE p.album_id=f.album_id AND p.seq=f.seq)))');
  const danglingRecent = query(o, password,
    'SELECT COUNT(*) FROM media_recent r WHERE r.owner_key = ' + sqlStr(o.owner) + ' AND (' +
    '(r.kind=\'video\' AND NOT EXISTS (SELECT 1 FROM media_video v WHERE v.id=r.target_id)) OR ' +
    '(r.kind=\'photo\' AND NOT EXISTS (SELECT 1 FROM media_photo p WHERE p.album_id=r.album_id AND p.seq=r.seq)))');
  add('收藏无悬空媒体引用', Number(danglingFav[0][0]) === 0, 0, danglingFav[0][0]);
  add('最近浏览无悬空媒体引用', Number(danglingRecent[0][0]) === 0, 0, danglingRecent[0][0]);

  return { checks, failed: checks.filter((c) => !c.ok), fav, recent };
}

// ---------------------------------------------------------------- API 抽样

function httpGetJson(url, headers) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, { headers, timeout: 8000 }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode !== 200) return reject(new Error('HTTP ' + res.statusCode + ' ' + text.slice(0, 200)));
        try {
          resolve(JSON.parse(text));
        } catch (e) {
          reject(new Error('响应不是 JSON：' + text.slice(0, 200)));
        }
      });
    });
    req.on('timeout', () => { req.destroy(new Error('请求超时')); });
    req.on('error', reject);
  });
}

async function apiCheck(o) {
  const url = o.api + '/user';
  const data = await httpGetJson(url, { 'X-Ms-Owner': o.owner });
  const pick = (list) => (list || []).slice(0, 3);
  return { url, data, spotVideos: pick(data.recentVideos), spotPhotos: pick(data.recentPhotos) };
}

// ---------------------------------------------------------------- main

function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.help) {
    log(HELP);
    return 0;
  }
  o.out = path.resolve(o.out);

  log('== 读取源数据 ' + o.source);
  const src = loadSource(o);
  const { sql, stats } = buildSql(src, o);
  log('   profile.nick=' + src.nick + ' (avatar ' +
    (src.avatar ? Array.from(src.avatar).length + ' 字符' : '无') + ')');
  log('   收藏 ' + stats.favorites + ' 条 / 最近视频 ' + stats.recentVideos + ' 条 / 最近图片 ' +
    stats.recentPhotos + ' 条 = 共 ' + stats.total + ' 条');
  if (stats.duplicates) log('   [warn] 源内有 ' + stats.duplicates + ' 条重复业务键，已按首条去重');
  if (stats.dropped) log('   [warn] 源内有 ' + stats.dropped + ' 条形状非法（缺 kind/id/album/i），已丢弃');
  if (stats.beyond) log('   [warn] 最近浏览超出每类 50 条上限，丢弃最旧 ' + stats.beyond + ' 条');
  for (const w of src.warn) log('   [warn] ' + w);

  if (o.dryRun) {
    log('');
    log('== --dry-run：不连库、不写文件');
    log('将写入 media_owner 1 行（owner_key=' + o.owner + '）');
    log('将先删除 media_favorite / media_recent 中 owner_key=' + o.owner + ' 的旧行，再插入：');
    log('  media_favorite   ' + stats.favorites + ' 行');
    log('  media_recent     ' + (stats.recentVideos + stats.recentPhotos) + ' 行（video ' +
      stats.recentVideos + ' + photo ' + stats.recentPhotos + '）');
    log('SQL 预览（前 8 行）：');
    for (const line of sql.split('\n').slice(0, 8)) log('  ' + line);
    log('结果：dry-run 通过 ✅');
    return 0;
  }

  fs.mkdirSync(o.out, { recursive: true });
  const sqlPath = path.join(o.out, 'import-user.sql');
  fs.writeFileSync(sqlPath, sql, 'utf8');
  log('== SQL 已写入 ' + sqlPath + '  ' + (Buffer.byteLength(sql) / 1024).toFixed(1) + ' KB');

  const password = readPassword(o);
  log('== mysql 导入 ...');
  const fd = fs.openSync(sqlPath, 'r');
  let run;
  try {
    run = mysqlRun(o, password, null, fd);
  } finally {
    fs.closeSync(fd);
  }
  if (run.status !== 0) {
    log('导入失败（exit=' + run.status + '）：');
    log((run.stderr || '').trim().slice(0, 4000));
    return 1;
  }
  if ((run.stderr || '').trim()) log('   [mysql stderr] ' + run.stderr.trim().slice(0, 1000));
  log('   导入完成');

  let exit = 0;
  if (!o.skipVerify) {
    log('== 校验 ...');
    const result = verify(o, password, src, stats);
    for (const c of result.checks) {
      log('   ' + (c.ok ? '[ok]  ' : '[FAIL]') + ' ' + c.name + '：期望 ' + c.expect + ' / 实际 ' + c.actual);
    }
    if (result.failed.length) exit = 1;
  }

  if (!o.skipApi) {
    log('== API 抽样（X-Ms-Owner: ' + o.owner + '）');
    return apiCheck(o).then((r) => {
      log('   GET ' + r.url + ' -> 200');
      log('   ownerKey=' + r.data.ownerKey + ' profile=' + JSON.stringify(r.data.profile));
      log('   favorites=' + (r.data.favorites || []).length +
        ' recentVideos=' + (r.data.recentVideos || []).length +
        ' recentPhotos=' + (r.data.recentPhotos || []).length);
      for (const it of r.spotVideos.slice(0, 2)) log('   [video] ' + JSON.stringify(it));
      for (const it of r.spotPhotos.slice(0, 1)) log('   [photo] ' + JSON.stringify(it));
      log(exit === 0 ? '结果：通过 ✅' : '结果：失败 ❌');
      return exit;
    }).catch((e) => {
      log('   [FAIL] API 抽样失败：' + e.message + '（后端未启动时可加 --skip-api）');
      return 1;
    });
  }

  log(exit === 0 ? '结果：通过 ✅' : '结果：失败 ❌');
  return exit;
}

Promise.resolve()
  .then(main)
  .then((code) => { process.exitCode = code; })
  .catch((e) => {
    process.stderr.write('ERROR: ' + (e && e.stack ? e.stack : e) + '\n');
    process.exitCode = 1;
  });
