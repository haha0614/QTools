#!/usr/bin/env node
'use strict';
/**
 * 通用媒体目录 -> MySQL qtools.media_* 扫描导入（零依赖，仅 node: 内置模块）
 *
 * 与同目录 import.js 的区别：import.js 只认旧 Node 站导出的 index.json（原作者专用，保留作历史迁移）；
 * 本脚本只读遍历任意媒体根目录，把「目录结构」直接变成媒体站内容，任何人把自己的媒体目录变成站点内容。
 *
 * 目录约定（详见 README「换成你自己的媒体内容（通用导入）」）：
 *   视频  .mp4 .webm .mkv .mov .m4v .avi  -> media_video
 *         id = sha1(rel_path) 前 12 位（与 Java 端一致）
 *         folder = rel_path 第一段（根目录下直接放文件时用「默认」）
 *         rel_path = 正斜杠相对路径；title = 文件名去扩展名
 *         browser_ok = .mp4/.webm -> 1，其它 -> 0
 *         poster_path = thumbs/v/<id>.jpg（文件由 build-thumbs.js 生成）
 *         dur/w/h/vcodec 留 NULL（不跑 ffprobe），且 upsert 时不覆盖已有值
 *   图片  .jpg .jpeg .png .webp .gif .bmp  -> 每个「所在目录」算一本相册
 *         album.id = 'a' + 3 位序号（目录相对路径按自然序排序后从 1 递增，如 a001）
 *         album.title = 目录名（根目录散图用根目录名，取不到则「未分组」）
 *         album.dir = 目录相对路径（根目录为空串）
 *         photo.seq = 目录内文件名自然序（数字感知）从 0 递增
 *         photo.is_big = bytes >= --big-bytes（默认 2MB）
 *         album.photo_count / album.bytes 累加；cover_path = thumbs/p/<albumId>/0000.jpg
 *   people 恒为 0：通用扫描不生成人物（media_album.person_id 留 NULL）
 *
 * 写库方式复用 import.js 已验证的做法：内存拼一个 SQL 文本，单事务，用 mysql 客户端经 stdin 灌入，
 * 固定 --default-character-set=utf8mb4；密码只从 MYSQL_PWD 环境变量读（不打印、不落盘）。
 *
 * 幂等：默认 INSERT ... AS n ON DUPLICATE KEY UPDATE（视频按 id、相册按 id、图片按 (album_id,seq)），
 *       且不覆盖 hidden / person_id / dur / w / h / vcodec。
 *       --truncate --yes 才先清空 media_photo / media_album / media_video。
 *
 * 安全：对 --root 只读（readdirSync / statSync），任何情况下不写媒体目录。
 *
 * MySQL 版本要求：8.0.19+（使用了 `INSERT ... AS alias ON DUPLICATE KEY UPDATE` 语法）。
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const VIDEO_EXT = new Set(['.mp4', '.webm', '.mkv', '.mov', '.m4v', '.avi']);
const PHOTO_EXT = new Set(['.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp']);
const BROWSER_OK_EXT = new Set(['.mp4', '.webm']);

/** media_*.rel_path VARCHAR(700)，超长直接跳过（写入会报 1406） */
const MAX_REL = 700;

const DEFAULTS = {
  db: 'qtools',
  user: 'root',
  host: '127.0.0.1',
  port: 3306,
  bigBytes: 2097152,
  batch: 400,
  exclude: ['_媒体站', 'trash', '.git', 'node_modules', 'thumbs', 'pv'],
};

const HELP = `
用法：node scan-media.js --root=<媒体根> [选项]

  --root=<dir>        必填，媒体根目录（只读遍历）
  --db=<name>         默认 qtools
  --user=<name>       默认 root
  --host=<host>       默认 127.0.0.1
  --port=<n>          默认 3306
  --mysql=<exe>       mysql 客户端，默认从 PATH 找 mysql
  --truncate          先 DELETE FROM media_photo/media_album/media_video（危险，必须同时给 --yes）
  --yes               确认 --truncate（缺它会直接报错退出，不会写库）
  --dry-run           只统计并打印，不写库（不需要数据库/mysql 客户端）
  --dump-sql=<file>   把生成的 SQL 写入该文件并打印前 20 行，**不执行**（可与 --dry-run 同用）
  --big-bytes=<n>     判定 is_big=1 的字节阈值，默认 2097152（2MB）
  --exclude=<a,b>     按目录名排除（任意层级），默认 _媒体站,trash,.git,node_modules,thumbs,pv
  --help              显示本帮助

密码：只从环境变量 MYSQL_PWD 读取（不打印、不落盘）：
  PowerShell:  $env:MYSQL_PWD = '<root 密码>'; node scan-media.js --root=D:\\我的媒体

输出（管理端 MediaScanServiceImpl 按这些行解析，改动前先看它）：
  videos=<n> albums=<n> photos=<n> people=0            扫描到的源数据条数
  条数 videos=<n> albums=<n> photos=<n> people=<n>    写库后回读的库内条数
`;

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS, {
    root: null, mysql: null, dumpSql: null, truncate: false, yes: false, dryRun: false, ignored: [],
  });
  for (const a of argv) {
    if (a === '--truncate') o.truncate = true;
    else if (a === '--yes' || a === '-y') o.yes = true;
    else if (a === '--dry-run') o.dryRun = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--root=')) o.root = a.slice('--root='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--host=')) o.host = a.slice('--host='.length);
    else if (a.startsWith('--port=')) o.port = a.slice('--port='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--dump-sql=')) o.dumpSql = a.slice('--dump-sql='.length);
    else if (a.startsWith('--big-bytes=')) o.bigBytes = Number(a.slice('--big-bytes='.length));
    else if (a.startsWith('--exclude=')) {
      o.exclude = a.slice('--exclude='.length).split(',').map((s) => s.trim()).filter(Boolean);
    } else {
      // 管理端会传它自己的参数（例如 --out=<dir>、旧 import.js 的 --dir-mode=raw）：
      // 认不出的忽略并告警，绝不因为多一个参数就不干活
      o.ignored.push(a);
    }
  }
  if (!Number.isFinite(o.bigBytes) || o.bigBytes <= 0) throw new Error('--big-bytes 必须是正整数');
  o.port = String(o.port);
  o.excludeSet = new Set(o.exclude.map((s) => s.toLowerCase()));
  return o;
}

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
  return Number.isFinite(n) ? String(Math.trunc(n)) : '0';
}

function chunks(arr, size) {
  const out = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

/** 自然序（数字感知）：a2 < a10；逐段比较数字/非数字，避免 Intl.Collator 的平台差异 */
function naturalCompare(a, b) {
  const re = /(\d+)|(\D+)/g;
  const pa = String(a).match(re) || [];
  const pb = String(b).match(re) || [];
  for (let i = 0; i < Math.min(pa.length, pb.length); i += 1) {
    const x = pa[i];
    const y = pb[i];
    if (/^\d/.test(x) && /^\d/.test(y)) {
      const nx = Number(x);
      const ny = Number(y);
      if (nx !== ny) return nx - ny;
      if (x.length !== y.length) return x.length - y.length; // 0002 vs 2 的稳定次序
    } else if (x !== y) {
      return x < y ? -1 : 1;
    }
  }
  return pa.length - pb.length;
}

/** 在 PATH 里找可执行文件（Windows 需要拼 PATHEXT）；找不到返回 null */
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
  if (!found) {
    throw new Error('PATH 里找不到 ' + name + '，请用 --' + name + '=<exe> 显式指定');
  }
  return found;
}

function readPassword() {
  const pwd = process.env.MYSQL_PWD;
  if (!pwd) {
    throw new Error('未设置 MYSQL_PWD 环境变量。密码只从环境变量读取（不落盘、不打印）：\n' +
      '  PowerShell:  $env:MYSQL_PWD = \'<密码>\'; node scan-media.js --root=<媒体根>');
  }
  return pwd;
}

// ---------------------------------------------------------------- 遍历（只读）

function walk(root, excludeSet, sink) {
  const stack = [{ abs: root, rel: '' }];
  while (stack.length) {
    const cur = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(cur.abs, { withFileTypes: true });
    } catch (e) {
      sink.errors.push(cur.abs + '：' + e.message);
      continue;
    }
    entries.sort((a, b) => naturalCompare(a.name, b.name));
    for (const d of entries) {
      const abs = path.join(cur.abs, d.name);
      const rel = cur.rel ? cur.rel + '/' + d.name : d.name;
      if (d.isSymbolicLink()) {
        sink.skippedLinks += 1; // 不跟随符号链接/junction，避免环
      } else if (d.isDirectory()) {
        if (!excludeSet.has(d.name.toLowerCase())) stack.push({ abs, rel });
      } else if (d.isFile()) {
        sink.files.push({ abs, rel, name: d.name, ext: path.extname(d.name).toLowerCase() });
      }
    }
  }
  return sink;
}

// ---------------------------------------------------------------- 建模型

function collect(root, o) {
  const sink = { files: [], errors: [], skippedLinks: 0 };
  walk(root, o.excludeSet, sink);

  const rootTitle = path.basename(root) || '未分组';
  const videos = [];
  const byDir = new Map(); // dirRel -> [{ name, rel, bytes }]
  const warnings = [];
  let longPath = 0;

  for (const f of sink.files) {
    if (!VIDEO_EXT.has(f.ext) && !PHOTO_EXT.has(f.ext)) continue;
    if (f.rel.length > MAX_REL) {
      longPath += 1;
      continue;
    }
    let size = 0;
    try {
      size = fs.statSync(f.abs).size;
    } catch (e) {
      warnings.push('stat 失败已跳过：' + f.rel + '（' + e.message + '）');
      continue;
    }
    if (VIDEO_EXT.has(f.ext)) {
      const slash = f.rel.lastIndexOf('/');
      videos.push({
        id: crypto.createHash('sha1').update(f.rel, 'utf8').digest('hex').slice(0, 12),
        folder: slash < 0 ? '默认' : f.rel.slice(0, f.rel.indexOf('/')),
        rel: f.rel,
        title: f.name.slice(0, f.name.length - f.ext.length),
        size,
        browserOk: BROWSER_OK_EXT.has(f.ext) ? 1 : 0,
      });
    } else {
      const slash = f.rel.lastIndexOf('/');
      const dirRel = slash < 0 ? '' : f.rel.slice(0, slash);
      if (!byDir.has(dirRel)) byDir.set(dirRel, []);
      byDir.get(dirRel).push({ name: f.name, rel: f.rel, bytes: size });
    }
  }

  if (longPath) warnings.push(longPath + ' 个文件 rel_path 超过 ' + MAX_REL + ' 字符，已跳过');
  for (const e of sink.errors) warnings.push('目录读取失败：' + e);
  if (sink.skippedLinks) warnings.push(sink.skippedLinks + ' 个符号链接/联接点已跳过（不跟随）');

  const dirs = [...byDir.keys()].sort(naturalCompare);
  const albums = dirs.map((dirRel, i) => {
    const id = 'a' + String(i + 1).padStart(3, '0');
    const photos = byDir.get(dirRel)
      .slice()
      .sort((a, b) => naturalCompare(a.name, b.name))
      .map((p, seq) => ({ seq, rel: p.rel, bytes: p.bytes, isBig: p.bytes >= o.bigBytes ? 1 : 0 }));
    const bytes = photos.reduce((s, p) => s + p.bytes, 0);
    const dirName = dirRel === '' ? rootTitle : dirRel.slice(dirRel.lastIndexOf('/') + 1);
    return {
      id,
      title: dirName,
      dir: dirRel,
      photoCount: photos.length,
      bytes,
      cover: 'thumbs/p/' + id + '/0000.jpg',
      sort: i,
      photos,
    };
  });

  videos.sort((a, b) => naturalCompare(a.rel, b.rel));
  return { videos, albums, warnings };
}

// ---------------------------------------------------------------- 生成 SQL

function buildSql(model, o) {
  const lines = [
    '-- 由 media-import/scan-media.js 生成，请勿手工编辑',
    'SET NAMES utf8mb4;',
    'SET SESSION sql_mode = DEFAULT;',
    'START TRANSACTION;',
  ];

  if (o.truncate) {
    lines.push('DELETE FROM media_photo;');
    lines.push('DELETE FROM media_album;');
    lines.push('DELETE FROM media_video;');
  }

  const videoRows = model.videos.map((v) => '(' + [
    sqlStr(v.id), sqlStr(v.folder), sqlStr(v.rel), sqlStr(v.title), sqlInt(v.size),
    'NULL', 'NULL', 'NULL', 'NULL', sqlInt(v.browserOk), sqlStr('thumbs/v/' + v.id + '.jpg'), '0',
  ].join(',') + ')');
  for (const part of chunks(videoRows, o.batch)) {
    lines.push('INSERT INTO media_video (id,folder,rel_path,title,size,dur,w,h,vcodec,browser_ok,poster_path,hidden) VALUES ' +
      part.join(',') +
      // dur/w/h/vcodec 不覆盖：本脚本不跑 ffprobe，别把别的工具填好的元数据清空
      ' AS n ON DUPLICATE KEY UPDATE folder=n.folder,rel_path=n.rel_path,title=n.title,' +
      'size=n.size,browser_ok=n.browser_ok,poster_path=n.poster_path;');
  }

  const albumRows = model.albums.map((a) => '(' + [
    sqlStr(a.id), 'NULL', sqlStr(a.title), sqlStr(a.dir), sqlInt(a.photoCount),
    sqlInt(a.bytes), sqlStr(a.cover), "''", sqlInt(a.sort), '0',
  ].join(',') + ')');
  for (const part of chunks(albumRows, o.batch)) {
    lines.push('INSERT INTO media_album (id,person_id,title,dir,photo_count,bytes,cover_path,note,sort,hidden) VALUES ' +
      part.join(',') +
      // person_id / note / hidden 不覆盖：管理端的人工归类不能被重扫冲掉
      ' AS n ON DUPLICATE KEY UPDATE title=n.title,dir=n.dir,photo_count=n.photo_count,' +
      'bytes=n.bytes,cover_path=n.cover_path,sort=n.sort;');
  }

  const photoRows = [];
  for (const a of model.albums) {
    for (const p of a.photos) {
      photoRows.push('(' + [
        sqlStr(a.id), sqlInt(p.seq), sqlStr(p.rel), sqlInt(p.bytes), 'NULL', 'NULL',
        sqlInt(p.isBig), '0',
      ].join(',') + ')');
    }
  }
  for (const part of chunks(photoRows, o.batch)) {
    lines.push('INSERT INTO media_photo (album_id,seq,rel_path,bytes,w,h,is_big,hidden) VALUES ' + part.join(',') +
      // w/h 不覆盖：本脚本不探测图片尺寸，别把别的工具填好的值清空
      ' AS n ON DUPLICATE KEY UPDATE rel_path=n.rel_path,bytes=n.bytes,is_big=n.is_big;');
  }

  // 目录里删过图：把每本相册 seq >= 当前张数 的残留行清掉，否则相册会越重扫越多
  for (const a of model.albums) {
    lines.push('DELETE FROM media_photo WHERE album_id = ' + sqlStr(a.id) +
      ' AND seq >= ' + sqlInt(a.photoCount) + ';');
  }
  // 孤儿图（相册已被删掉）：保证「每张图都能找到相册」恒成立
  lines.push('DELETE p FROM media_photo p LEFT JOIN media_album a ON a.id = p.album_id WHERE a.id IS NULL;');

  lines.push('COMMIT;');
  return lines.join('\n') + '\n';
}

// ---------------------------------------------------------------- mysql

function mysqlArgs(o) {
  return ['-u', o.user, '--host=' + o.host, '--port=' + o.port,
    '--default-character-set=utf8mb4', '-D', o.db, '--batch', '-N'];
}

function runMysql(o, mysqlExe, password, sql) {
  const r = spawnSync(mysqlExe, mysqlArgs(o), {
    env: Object.assign({}, process.env, { MYSQL_PWD: password }),
    input: sql,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (r.error) throw new Error('调用 mysql 失败：' + r.error.message);
  return r;
}

function queryCounts(o, mysqlExe, password) {
  const sql = 'SELECT (SELECT COUNT(*) FROM media_video),(SELECT COUNT(*) FROM media_album),' +
    '(SELECT COUNT(*) FROM media_photo),(SELECT COUNT(*) FROM media_person);';
  const r = runMysql(o, mysqlExe, password, sql);
  if (r.status !== 0) throw new Error('回读条数失败：\n' + (r.stderr || '').trim());
  const cols = (r.stdout || '').replace(/\r/g, '').trim().split('\t');
  if (cols.length !== 4) throw new Error('回读条数格式异常：' + JSON.stringify(r.stdout));
  return '条数 videos=' + cols[0] + ' albums=' + cols[1] + ' photos=' + cols[2] + ' people=' + cols[3];
}

// ---------------------------------------------------------------- 主流程

function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.help) {
    log(HELP);
    return 0;
  }
  if (!o.root) throw new Error('缺少必填参数 --root=<媒体根>（--help 看用法）');
  const root = path.resolve(o.root);
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    throw new Error('--root 不是已存在的目录：' + root);
  }
  if (o.truncate && !o.yes) {
    throw new Error('--truncate 会清空 media_photo/media_album/media_video，必须同时给出 --yes 才会执行');
  }
  for (const a of o.ignored) warn('已忽略无法识别的参数（管理端/旧脚本兼容）：' + a);

  const model = collect(root, o);
  log('videos=' + model.videos.length + ' albums=' + model.albums.length +
    ' photos=' + model.albums.reduce((s, a) => s + a.photoCount, 0) + ' people=0');
  log('root=' + root + ' big-bytes=' + o.bigBytes +
    ' exclude=' + o.exclude.join(',') + (o.truncate ? ' truncate=1' : ''));
  for (const a of model.albums.slice(0, 20)) {
    log('  album ' + a.id + ' dir="' + a.dir + '" photos=' + a.photoCount +
      ' bytes=' + a.bytes + ' title="' + a.title + '"');
  }
  if (model.albums.length > 20) log('  …还有 ' + (model.albums.length - 20) + ' 本相册');
  for (const w of model.warnings) warn(w);

  if (o.dryRun) {
    log('== dry-run：未写库');
  }
  if (o.dumpSql) {
    const file = path.resolve(o.dumpSql);
    const sql = buildSql(model, o);
    fs.writeFileSync(file, sql, 'utf8');
    const all = sql.split('\n');
    log('== SQL 已写入 ' + file + '（' + Buffer.byteLength(sql, 'utf8') + ' 字节 / ' + (all.length - 1) + ' 行），未执行');
    for (const line of all.slice(0, 20)) log('  | ' + line);
    if (all.length - 1 > 20) log('  | …还有 ' + (all.length - 1 - 20) + ' 行，见文件');
  }
  if (o.dryRun || o.dumpSql) return 0;

  const mysqlExe = resolveExe(o.mysql, 'mysql', 'mysql 客户端');
  const password = readPassword();
  const sql = buildSql(model, o);
  const r = runMysql(o, mysqlExe, password, sql);
  if (r.status !== 0) {
    throw new Error('导入失败（mysql 退出码 ' + r.status + '）：\n' + (r.stderr || '').trim() +
      '\n提示：SQL 已在内存生成，未落盘；修好数据后用 --dry-run 复核。');
  }
  log('== 写库完成 ' + o.db + '@' + o.host + ':' + o.port + '（' + model.videos.length + ' 视频 / ' +
    model.albums.length + ' 相册 / ' + model.albums.reduce((s, a) => s + a.photoCount, 0) + ' 图）');
  log(queryCounts(o, mysqlExe, password));
  log('== 下一步：node build-thumbs.js --root=' + root + ' --thumbs=<缩略图根>');
  return 0;
}

try {
  process.exitCode = main();
} catch (e) {
  process.stderr.write('ERROR ' + (e && e.message ? e.message : String(e)) + '\n');
  process.exitCode = 1;
}
