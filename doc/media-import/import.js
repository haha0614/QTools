#!/usr/bin/env node
'use strict';
/**
 * 媒体站 index.json -> MySQL qtools.media_* 迁移脚本（M1）
 *
 * 特性：可重复执行（幂等）、批量导入（生成 .sql + `mysql < file.sql`）、
 *       导入后自动校验并输出 report.md。零依赖，仅用 node: 内置模块。
 *
 * 用法见同目录 README.md；`node import.js --help` 可看参数。
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const SCRIPT_DIR = __dirname;

const DEFAULTS = {
  source: 'E:\\111\\data\\media\\data\\index.json',
  mediaRoot: 'E:\\111\\视频',
  stationRoot: 'E:\\111\\data\\media', // poster 路径相对此根（CONTRACT §3.1）
  mysql: 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
  db: 'qtools',
  user: 'root',
  secret: 'E:\\111\\project\\QTools\\QTools\\qtools-admin\\src\\main\\resources\\application-secret.yml',
  out: SCRIPT_DIR,
  dirMode: 'mark', // mark: 同一 dir 多册时追加 " #<albumId>"（表结构仍有 dir 唯一键时用）；raw: 原样写入
  allowDupDir: false,  // raw 模式放行：需先确认 media_album 上已无 dir 唯一键（本仓库已改为普通索引 idx_album_dir）
  truncate: false,
  skipVerify: false,
  deepBytes: true, // 额外用磁盘实际字节核对 media_album.bytes / media_video.size（9628+176 次 stat，约 0.6 s）
  batch: 400,
};

const TABLES = [
  'media_video_actor',
  'media_video_tag',
  'media_photo',
  'media_video',
  'media_album',
  'media_person',
];

/**
 * 不是人的「人物名」（P5.62）：相册没识别出人物时，上游 index.json 会写成这些名字。
 *
 * <p>写进 media_person 就会多出一个挂在人物墙 / 人物下拉里的假人物（历史上的「未归类」
 * 113 号就是这么来的：2 本相册 118 张照片，没有一部视频）。所以这类名字一律**不建人物行**，
 * media_album.person_id 落 NULL —— 与 scan-media.js「通用扫描不生成人物、person_id 留 NULL」
 * 同一口径；用户端相册页仍会把这些相册按前端常量 UNGROUPED 分组成「未归类」展示，
 * 那只是分组名，不是库里的人物行。</p>
 *
 * <p>存量清理见 doc/media_person_merge_uncategorized.sql（P5.62 已把 113 并到「素人」）。</p>
 */
const PLACEHOLDER_PERSON_NAMES = new Set(['未归类', '未识别', '未知', '空']);

function isPlaceholderPersonName(name) {
  const n = String(name === undefined || name === null ? '' : name).trim();
  return !n || PLACEHOLDER_PERSON_NAMES.has(n);
}

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS);
  for (const a of argv) {
    if (a === '--truncate') o.truncate = true;
    else if (a === '--skip-verify') o.skipVerify = true;
    else if (a === '--no-deep-bytes') o.deepBytes = false;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--dir-mode=')) o.dirMode = a.slice('--dir-mode='.length);
  else if (a === '--allow-dup-dir') o.allowDupDir = true;
    else if (a.startsWith('--source=')) o.source = a.slice('--source='.length);
    else if (a.startsWith('--media-root=')) o.mediaRoot = a.slice('--media-root='.length);
    else if (a.startsWith('--station-root=')) o.stationRoot = a.slice('--station-root='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
    else if (a.startsWith('--out=')) o.out = a.slice('--out='.length);
    else throw new Error('未知参数：' + a);
  }
  if (o.dirMode !== 'mark' && o.dirMode !== 'raw') {
    throw new Error('--dir-mode 只能是 mark 或 raw');
  }
  return o;
}

const HELP = `
用法：node import.js [选项]

  --truncate         先 DELETE FROM 再插入（默认按主键/唯一键 upsert）
  --dir-mode=mark    uk_album_dir 冲突时给 dir 追加 " #<albumId>"（默认，兼容现有表结构）
  --dir-mode=raw     dir 原样写入（需要先放宽 media_album.uk_album_dir）
  --skip-verify      只导入不校验
  --no-deep-bytes    跳过「磁盘实际字节」核对（默认开启）
  --source=<file>    index.json 路径
  --media-root=<dir> 媒体根（rel_path 的基准），默认 E:\\111\\视频
  --station-root=<dir> 站根（poster 的基准），默认 E:\\111\\视频\\_媒体站
  --mysql=<exe>      mysql 客户端路径
  --db=<name> --user=<name> --secret=<yml>
  --out=<dir>        .sql 与 report.md 的输出目录

MySQL 密码：优先 $env:MYSQL_PWD，否则从 --secret 指定的 yml 的
spring.datasource.druid.master.password 读取，仅通过子进程环境变量传递，不落盘、不打印。
`;

// ---------------------------------------------------------------- 工具

function log(msg) {
  process.stdout.write(msg + '\n');
}

function pad(n) {
  return String(n).padStart(2, '0');
}

function fmtDate(d) {
  return (
    d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
    ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
  );
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

function sqlBit(v) {
  return v ? '1' : '0';
}

function chunks(arr, size) {
  const out = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
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
    maxBuffer: 64 * 1024 * 1024,
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

function queryOne(o, password, sql) {
  const rows = query(o, password, sql);
  return rows.length ? rows[0] : [];
}

// ---------------------------------------------------------------- 读源数据

function loadSource(o) {
  const raw = fs.readFileSync(o.source, 'utf8');
  const data = JSON.parse(raw);
  const problems = [];
  if (!Array.isArray(data.videos)) problems.push('index.json 缺少 videos[]');
  if (!Array.isArray(data.albums)) problems.push('index.json 缺少 albums[]');
  if (!Array.isArray(data.people)) problems.push('index.json 缺少 people[]');
  if (problems.length) throw new Error(problems.join('; '));
  if (!data.stats) data.stats = {};
  return data;
}

// ---------------------------------------------------------------- 生成 SQL

function buildSql(data, o) {
  const t0 = Date.now();
  const lines = [];
  const warn = [];
  const stats = {
    people: 0, albums: 0, photos: 0, videos: 0, tags: 0, actors: 0,
    videoBytes: 0, albumBytes: 0, albumPhotoCountSum: 0,
    missingVideoFiles: 0, missingPosters: 0, dirMarked: 0,
  };

  lines.push('-- 由 media-import/import.js 生成，请勿手工编辑');
  lines.push('SET NAMES utf8mb4;');
  lines.push('SET SESSION sql_mode = DEFAULT;');
  lines.push('START TRANSACTION;');

  if (o.truncate) {
    for (const t of TABLES) lines.push('DELETE FROM ' + t + ';');
  }
  // 这两张表没有唯一键，upsert 无法去重 -> 每次都全量重建
  lines.push('DELETE FROM media_video_tag;');
  lines.push('DELETE FROM media_video_actor;');

  // ---- people
  // 占位名（未归类 / 未知 / 空 …）不是人：不建人物行，相册 person_id 落 NULL（见 isPlaceholderPersonName）
  const people = data.people.map((p) => String(p.name)).filter((name) => {
    if (!isPlaceholderPersonName(name)) return true;
    warn.push('data.people 里的 "' + name + '" 是占位名，已跳过（不建 media_person 行）');
    return false;
  });
  const placeholderAlbums = data.albums.filter((a) => isPlaceholderPersonName(a.person));
  for (const a of placeholderAlbums) {
    warn.push('相册 ' + a.id + ' 的人物名 "' + a.person + '" 是占位名，person_id 落 NULL（不建人物行）');
  }
  const albumPeople = [...new Set(data.albums.map((a) => String(a.person)))]
    .filter((name) => !isPlaceholderPersonName(name));
  for (const name of albumPeople) {
    if (!people.includes(name)) {
      people.push(name);
      warn.push('人物 "' + name + '" 只在 albums[].person 出现，已补建 media_person 行');
    }
  }
  const personRows = people.map((name, i) => '(' + sqlStr(name) + ',' + i + ',0)');
  for (const part of chunks(personRows, o.batch)) {
    lines.push(
      'INSERT INTO media_person (name,sort,hidden) VALUES ' + part.join(',') +
      ' AS n ON DUPLICATE KEY UPDATE sort=n.sort;'
    );
  }
  stats.people = personRows.length;

  // ---- albums
  const dirCount = new Map();
  for (const a of data.albums) dirCount.set(a.dir, (dirCount.get(a.dir) || 0) + 1);
  const dupDirs = [...dirCount.entries()].filter(([, n]) => n > 1);
  if (o.dirMode === 'raw' && dupDirs.length && !o.allowDupDir) {
    // 不能只是「写进去看看」：uk_album_dir 冲突会被 ON DUPLICATE KEY UPDATE 命中，
    // 把多本相册静默合并成一行（数据损坏但不报错），所以在导入前直接拦下。
    throw new Error(
      '--dir-mode=raw 与现有表结构冲突：' + dupDirs.length + ' 个 dir 被多本相册共用（' +
      dupDirs.slice(0, 3).map(([d, n]) => d + ' x' + n).join('; ') +
      '），media_album 上的 UNIQUE KEY uk_album_dir (dir(200)) 会拒绝后写入的行。' +
      '请改用 --dir-mode=mark（默认），或先把唯一键放宽为 (dir(200), id) 再重跑。'
    );
  }

  const albumRows = data.albums.map((a, i) => {
    const photos = Array.isArray(a.photos) ? a.photos : [];
    const count = photos.length;
    if (typeof a.count === 'number' && a.count !== count) {
      warn.push('相册 ' + a.id + ' 的 count(' + a.count + ') 与 photos.length(' + count + ') 不一致，以 photos 为准');
    }
    let dir = a.dir;
    if (o.dirMode === 'mark' && dirCount.get(a.dir) > 1) {
      dir = a.dir + ' #' + a.id;
      stats.dirMarked += 1;
    }
    stats.albumBytes += Number(a.bytes) || 0;
    stats.albumPhotoCountSum += count;
    return '(' + [
      sqlStr(a.id),
      // 占位名 -> NULL（不指向任何人物行，也不再造「未归类」这种容器人物）
      isPlaceholderPersonName(a.person)
        ? 'NULL'
        : '(SELECT id FROM media_person WHERE name = ' + sqlStr(a.person) + ')',
      sqlStr(a.title),
      sqlStr(dir),
      sqlInt(count),
      sqlInt(a.bytes || 0),
      sqlStr(a.cover === undefined ? null : a.cover),
      sqlStr(a.note === undefined ? '' : a.note),
      sqlInt(i),
      '0',
      'NULL',
    ].join(',') + ')';
  });
  for (const part of chunks(albumRows, o.batch)) {
    lines.push(
      'INSERT INTO media_album (id,person_id,title,dir,photo_count,bytes,cover_path,note,sort,hidden,mtime) VALUES ' +
      part.join(',') +
      // person_id 只在首次插入时写入：管理端改过的人物归属不能被重建索引冲掉
      ' AS n ON DUPLICATE KEY UPDATE title=n.title,dir=n.dir,' +
      'photo_count=n.photo_count,bytes=n.bytes,cover_path=n.cover_path,note=n.note,sort=n.sort;'
    );
  }
  stats.albums = albumRows.length;

  // ---- photos（index.json 无单图 bytes/w/h -> bytes=0, w/h=NULL，按契约保留）
  const photoRows = [];
  for (const a of data.albums) {
    const photos = Array.isArray(a.photos) ? a.photos : [];
    photos.forEach((p, seq) => {
      const i = typeof p.i === 'number' ? p.i : seq;
      if (i !== seq) warn.push('相册 ' + a.id + ' 的 photo.i(' + i + ') 与数组下标(' + seq + ') 不一致，以数组下标为准');
      photoRows.push(
        '(' + [sqlStr(a.id), seq, sqlStr(p.rel), '0', 'NULL', 'NULL', sqlBit(p.big), '0'].join(',') + ')'
      );
    });
  }
  for (const part of chunks(photoRows, o.batch)) {
    lines.push(
      'INSERT INTO media_photo (album_id,seq,rel_path,bytes,w,h,is_big,hidden) VALUES ' + part.join(',') +
      ' AS n ON DUPLICATE KEY UPDATE rel_path=n.rel_path,bytes=n.bytes,w=n.w,h=n.h,is_big=n.is_big;'
    );
  }
  stats.photos = photoRows.length;
  // 源里已删除的相册可能留下孤图，顺手清掉，保证关系校验恒成立
  lines.push('DELETE p FROM media_photo p LEFT JOIN media_album a ON a.id = p.album_id WHERE a.id IS NULL;');

  // ---- videos（mtime 取磁盘文件 mtime）
  const videoRows = data.videos.map((v) => {
    let mtime = 'NULL';
    const abs = path.join(o.mediaRoot, v.rel);
    try {
      mtime = sqlStr(fmtDate(fs.statSync(abs).mtime));
    } catch (e) {
      stats.missingVideoFiles += 1;
    }
    if (v.poster) {
      const pa = path.join(o.stationRoot, v.poster);
      const pb = path.join(o.mediaRoot, v.poster);
      if (!fs.existsSync(pa) && !fs.existsSync(pb)) stats.missingPosters += 1;
    } else {
      stats.missingPosters += 1;
    }
    stats.videoBytes += Number(v.size) || 0;
    return '(' + [
      sqlStr(v.id),
      sqlStr(v.folder),
      sqlStr(v.rel),
      sqlStr(v.title),
      sqlInt(v.size || 0),
      sqlDec(v.dur),
      sqlInt(v.w),
      sqlInt(v.h),
      sqlStr(v.vcodec),
      sqlBit(v.browserOk),
      sqlStr(v.poster === undefined ? null : v.poster),
      sqlStr(v.doc === undefined ? null : v.doc),
      sqlStr(v.sub === undefined ? null : v.sub),
      '0',
      mtime,
    ].join(',') + ')';
  });
  for (const part of chunks(videoRows, o.batch)) {
    lines.push(
      'INSERT INTO media_video (id,folder,rel_path,title,size,dur,w,h,vcodec,browser_ok,poster_path,doc_path,sub_path,hidden,mtime) VALUES ' +
      part.join(',') +
      ' AS n ON DUPLICATE KEY UPDATE folder=n.folder,rel_path=n.rel_path,title=n.title,size=n.size,' +
      'dur=n.dur,w=n.w,h=n.h,vcodec=n.vcodec,browser_ok=n.browser_ok,poster_path=n.poster_path,' +
      'doc_path=n.doc_path,sub_path=n.sub_path,mtime=n.mtime;'
    );
  }
  stats.videos = videoRows.length;

  // ---- tags / actors
  const tagRows = [];
  const actorRows = [];
  for (const v of data.videos) {
    for (const t of v.tags || []) {
      if (!Array.isArray(t) || t.length < 2) {
        warn.push('视频 ' + v.id + ' 存在非 [facet,name] 形状的标签：' + JSON.stringify(t));
        continue;
      }
      tagRows.push('(' + sqlStr(v.id) + ',' + sqlStr(t[0]) + ',' + sqlStr(t[1]) + ')');
    }
    for (const a of v.actors || []) {
      actorRows.push('(' + sqlStr(v.id) + ',' + sqlStr(a) + ')');
    }
  }
  for (const part of chunks(tagRows, o.batch)) {
    lines.push('INSERT INTO media_video_tag (video_id,facet,name) VALUES ' + part.join(',') + ';');
  }
  for (const part of chunks(actorRows, o.batch)) {
    lines.push('INSERT INTO media_video_actor (video_id,name) VALUES ' + part.join(',') + ';');
  }
  stats.tags = tagRows.length;
  stats.actors = actorRows.length;

  lines.push('COMMIT;');
  return { sql: lines.join('\n') + '\n', stats, warn, genMs: Date.now() - t0 };
}

// ---------------------------------------------------------------- 校验

function verify(o, password, data, sqlStats) {
  const checks = [];
  const add = (name, ok, expect, actual, extra) => {
    checks.push({ name, ok, expect: String(expect), actual: String(actual), extra: extra || '' });
  };

  const stat = data.stats || {};
  const srcVideoBytes = data.videos.reduce((s, v) => s + (Number(v.size) || 0), 0);
  const srcAlbumBytes = data.albums.reduce((s, a) => s + (Number(a.bytes) || 0), 0);
  const srcPhotos = data.albums.reduce((s, a) => s + (a.photos || []).length, 0);
  const srcTags = data.videos.reduce((s, v) => s + (v.tags || []).length, 0);
  const srcActors = data.videos.reduce((s, v) => s + (v.actors || []).length, 0);

  const row = queryOne(o, password,
    'SELECT (SELECT COUNT(*) FROM media_video),' +
    '(SELECT COUNT(*) FROM media_album),' +
    '(SELECT COUNT(*) FROM media_photo),' +
    '(SELECT COUNT(*) FROM media_person),' +
    '(SELECT COUNT(*) FROM media_video_tag),' +
    '(SELECT COUNT(*) FROM media_video_actor),' +
    '(SELECT COALESCE(SUM(size),0) FROM media_video),' +
    '(SELECT COALESCE(SUM(bytes),0) FROM media_album),' +
    '(SELECT COALESCE(SUM(photo_count),0) FROM media_album),' +
    '(SELECT COUNT(*) FROM media_album WHERE person_id IS NULL),' +
    '(SELECT COUNT(*) FROM media_photo p LEFT JOIN media_album a ON a.id=p.album_id WHERE a.id IS NULL)').map(Number);
  const [videos, albums, photos, people, tags, actors, vBytes, aBytes, aPhotoSum, albumNoPerson, orphanPhotos] = row;

  const expectedVideos = stat.videos !== undefined ? stat.videos : data.videos.length;
  const expectedAlbums = stat.albums !== undefined ? stat.albums : data.albums.length;
  const expectedPhotos = stat.photos !== undefined ? stat.photos : srcPhotos;

  add('videos 条数', videos === expectedVideos, expectedVideos, videos);
  add('albums 条数', albums === expectedAlbums, expectedAlbums, albums);
  add('photos 条数', photos === expectedPhotos, expectedPhotos, photos);
  add('photos 条数 = sum(album.photo_count)', aPhotoSum === photos, photos, aPhotoSum);
  // 占位名不建行：期望条数以 buildSql 算出的 personRows 为准（stat.people），退化路径同样先剔占位名
  const expectedPeople = stat.people !== undefined
    ? stat.people
    : data.people.map((p) => String(p.name)).filter((n) => !isPlaceholderPersonName(n)).length;
  add('people 条数', people === expectedPeople, expectedPeople, people);
  add('video_tag 条数 > 0 且等于源', tags > 0 && tags === srcTags, srcTags, tags);
  add('video_actor 条数 > 0 且等于源', actors > 0 && actors === srcActors, srcActors, actors);
  add('sum(media_video.size) 对齐 index.json', vBytes === srcVideoBytes, srcVideoBytes, vBytes);
  add('sum(media_album.bytes) 对齐 index.json', aBytes === srcAlbumBytes, srcAlbumBytes, aBytes);
  // 占位名相册按设计落 NULL，所以「NULL 的相册数」应正好等于源里占位名相册数（正常为 0）
  const expectedNoPerson = data.albums.filter((a) => isPlaceholderPersonName(a.person)).length;
  add('album.person_id 为 NULL 的相册数 = 占位名相册数', albumNoPerson === expectedNoPerson,
    expectedNoPerson, albumNoPerson);
  add('所有 photo.album_id 都能找到相册', orphanPhotos === 0, 0, orphanPhotos);

  // 抽样：20 张图
  const photoSample = query(o, password, 'SELECT rel_path FROM media_photo ORDER BY RAND() LIMIT 20');
  const badPhotos = photoSample.filter((r) => !fs.existsSync(path.join(o.mediaRoot, r[0])));
  add('随机 20 张 photo 的 rel_path 在磁盘存在', photoSample.length === 20 && badPhotos.length === 0,
    '20/20 存在', (20 - badPhotos.length) + '/20 存在',
    badPhotos.length ? '缺失：' + badPhotos.map((r) => r[0]).join(' | ') : '');

  // 抽样：10 个视频（rel + poster）
  const videoSample = query(o, password,
    'SELECT rel_path, COALESCE(poster_path,\'\') FROM media_video ORDER BY RAND() LIMIT 10');
  const badRel = [];
  const badPoster = [];
  for (const [rel, poster] of videoSample) {
    if (!fs.existsSync(path.join(o.mediaRoot, rel))) badRel.push(rel);
    const okPoster = poster && (fs.existsSync(path.join(o.stationRoot, poster)) || fs.existsSync(path.join(o.mediaRoot, poster)));
    if (!okPoster) badPoster.push(poster || '(null)');
  }
  add('随机 10 个 video 的 rel_path 在磁盘存在', videoSample.length === 10 && badRel.length === 0,
    '10/10 存在', (10 - badRel.length) + '/10 存在', badRel.join(' | '));
  add('随机 10 个 video 的 poster 文件存在', videoSample.length === 10 && badPoster.length === 0,
    '10/10 存在', (10 - badPoster.length) + '/10 存在', badPoster.join(' | '));

  // 全量 + 以磁盘为独立真值的核对（index.json 自身没有总字节字段，只能拿磁盘作对照）
  if (o.deepBytes) {
    const photoRows = query(o, password, 'SELECT album_id, rel_path FROM media_photo');
    let diskBytes = 0;
    let missing = 0;
    const perAlbum = new Map();
    for (const [albumId, rel] of photoRows) {
      try {
        const st = fs.statSync(path.join(o.mediaRoot, rel));
        diskBytes += st.size;
        perAlbum.set(albumId, (perAlbum.get(albumId) || 0) + st.size);
      } catch (e) {
        missing += 1;
      }
    }
    add('photo 全量 rel_path 在磁盘存在', photoRows.length === photos && missing === 0,
      photos + ' 条 / 0 缺失', photoRows.length + ' 条 / ' + missing + ' 缺失');
    add('sum(media_album.bytes) = 磁盘图片实际字节', diskBytes === aBytes, aBytes, diskBytes);
    const albumRows = query(o, password, 'SELECT id, bytes FROM media_album');
    const badAlbums = albumRows.filter(([id, b]) => (perAlbum.get(id) || 0) !== Number(b));
    add('每本相册 bytes = 该册图片磁盘字节', badAlbums.length === 0, '0 本不符', badAlbums.length + ' 本不符',
      badAlbums.slice(0, 3).map(([id, b]) => id + '(db=' + b + ',disk=' + (perAlbum.get(id) || 0) + ')').join(' | '));

    const vrows = query(o, password, 'SELECT rel_path, size FROM media_video');
    let vMissing = 0;
    const vBad = [];
    for (const [rel, size] of vrows) {
      try {
        if (fs.statSync(path.join(o.mediaRoot, rel)).size !== Number(size)) vBad.push(rel);
      } catch (e) {
        vMissing += 1;
      }
    }
    add('video 全量 rel_path 在磁盘存在', vrows.length === videos && vMissing === 0,
      videos + ' 条 / 0 缺失', vrows.length + ' 条 / ' + vMissing + ' 缺失');
    add('每部视频 size = 磁盘文件大小', vBad.length === 0, '0 部不符', vBad.length + ' 部不符', vBad.slice(0, 3).join(' | '));
  }

  // 人物分布（信息性）
  const personDist = query(o, password,
    'SELECT p.name, COUNT(a.id) FROM media_album a JOIN media_person p ON p.id=a.person_id ' +
    'GROUP BY p.name, p.sort ORDER BY COUNT(a.id) DESC, p.sort');

  // 分类分布（信息性）
  const folderDist = query(o, password, 'SELECT folder, COUNT(*) FROM media_video GROUP BY folder ORDER BY 2 DESC');

  // 样例行（供 Java 侧对照字段）
  const sampleAlbum = queryOne(o, password,
    'SELECT id,person_id,title,dir,photo_count,bytes,cover_path,note,sort FROM media_album ORDER BY id LIMIT 1');
  const sampleVideo = queryOne(o, password,
    'SELECT id,folder,title,size,dur,w,h,vcodec,browser_ok,poster_path,doc_path,sub_path,mtime FROM media_video ORDER BY id LIMIT 1');
  const samplePhoto = queryOne(o, password,
    'SELECT id,album_id,seq,rel_path,bytes,w,h,is_big FROM media_photo ORDER BY id LIMIT 1');
  const samplePeople = query(o, password, 'SELECT id,name,sort FROM media_person ORDER BY sort LIMIT 3');
  const markedDirs = query(o, password,
    "SELECT id,dir FROM media_album WHERE dir LIKE '%#a%' ORDER BY id LIMIT 2");

  return {
    checks, failed: checks.filter((c) => !c.ok), photoSample, videoSample, personDist, folderDist,
    sampleAlbum, sampleVideo, samplePhoto, samplePeople, markedDirs, sqlStats,
  };
}

// ---------------------------------------------------------------- 只读证明

function rootSignature(o) {
  const dirs = [o.mediaRoot, o.stationRoot, path.join(o.stationRoot, 'data')];
  const sig = dirs.map((d) => {
    try {
      const s = fs.statSync(d);
      return d + '\tmtime=' + s.mtimeMs;
    } catch (e) {
      return d + '\t(missing)';
    }
  });
  const idx = path.join(o.stationRoot, 'data', 'index.json');
  try {
    sig.push(idx + '\tsha256=' + crypto.createHash('sha256').update(fs.readFileSync(idx)).digest('hex').slice(0, 16));
  } catch (e) {
    sig.push(idx + '\t(unreadable)');
  }
  return sig;
}

// ---------------------------------------------------------------- 报告

function buildReport(o, data, result, timing, sigBefore, sigAfter, warnings) {
  const L = [];
  const t = (k, v) => L.push('| ' + k + ' | ' + v + ' |');
  L.push('# 媒体站 → QTools 数据迁移报告（M1）');
  L.push('');
  L.push('- 生成时间：' + fmtDate(new Date()));
  L.push('- 源数据：`' + o.source + '`（built=' + (data.built || '?') + '）');
  L.push('- 目标库：`' + o.db + '` 表 `media_person / media_album / media_photo / media_video / media_video_tag / media_video_actor`');
  L.push('- 模式：' + (o.truncate ? '`--truncate`（先 DELETE 再插入）' : 'upsert（`ON DUPLICATE KEY UPDATE`）') +
    '，`--dir-mode=' + o.dirMode + '`');
  L.push('');
  L.push('## 1. 用时');
  L.push('');
  L.push('| 阶段 | 毫秒 |');
  L.push('| --- | ---: |');
  L.push('| 生成 SQL | ' + timing.gen + ' |');
  L.push('| mysql 导入 | ' + timing.load + ' |');
  L.push('| 校验 | ' + timing.verify + ' |');
  L.push('| **总计** | **' + timing.total + '** |');
  L.push('');
  L.push('## 2. 条数');
  L.push('');
  L.push('| 表 | 行数 | 源期望 |');
  L.push('| --- | ---: | ---: |');
  L.push('| media_person | ' + result.counts.people + ' | ' + data.people.length + ' |');
  L.push('| media_album | ' + result.counts.albums + ' | ' + data.stats.albums + ' |');
  L.push('| media_photo | ' + result.counts.photos + ' | ' + data.stats.photos + ' |');
  L.push('| media_video | ' + result.counts.videos + ' | ' + data.stats.videos + ' |');
  L.push('| media_video_tag | ' + result.counts.tags + ' | ' + result.sqlStats.tags + ' |');
  L.push('| media_video_actor | ' + result.counts.actors + ' | ' + result.sqlStats.actors + ' |');
  L.push('');
  L.push('## 3. 字节');
  L.push('');
  L.push('| 项 | 值 |');
  L.push('| --- | ---: |');
  t('sum(media_video.size)', result.counts.vBytes.toLocaleString('en-US'));
  t('index.json sum(videos[].size)', result.sqlStats.videoBytes.toLocaleString('en-US'));
  t('sum(media_album.bytes)', result.counts.aBytes.toLocaleString('en-US'));
  t('index.json sum(albums[].bytes)', result.sqlStats.albumBytes.toLocaleString('en-US'));
  L.push('');
  L.push('## 4. 校验');
  L.push('');
  L.push('结果：' + (result.failed.length === 0 ? '**全部通过 ✅**' : '**' + result.failed.length + ' 项失败 ❌**'));
  L.push('');
  L.push('| 校验项 | 期望 | 实际 | 结果 |');
  L.push('| --- | --- | --- | :---: |');
  for (const c of result.checks) {
    L.push('| ' + c.name + ' | ' + c.expect + ' | ' + c.actual + (c.extra ? '（' + c.extra + '）' : '') +
      ' | ' + (c.ok ? '✅' : '❌') + ' |');
  }
  L.push('');
  L.push('抽样明细：');
  L.push('');
  for (const [rel] of result.photoSample.slice(0, 5)) L.push('- photo `' + rel + '`');
  for (const [rel, poster] of result.videoSample.slice(0, 3)) L.push('- video `' + rel + '` → poster `' + poster + '`');
  L.push('');
  L.push('## 5. 分布');
  L.push('');
  L.push('人物（相册数）：' + result.personDist.map((r) => r[0] + ' ' + r[1]).join(' / '));
  L.push('');
  L.push('分类（视频数）：' + result.folderDist.map((r) => r[0] + ' ' + r[1]).join(' / '));
  L.push('');
  L.push('## 6. 样例行');
  L.push('');
  L.push('| 表 | 字段 |');
  L.push('| --- | --- |');
  if (result.samplePeople.length) {
    L.push('| media_person | ' + result.samplePeople.map((r) => 'id=' + r[0] + ' name=' + r[1] + ' sort=' + r[2]).join('；') + ' |');
  }
  if (result.sampleAlbum.length) {
    const a = result.sampleAlbum;
    L.push('| media_album | id=' + a[0] + ' person_id=' + a[1] + ' title=' + a[2] + ' dir=`' + a[3] + '` photo_count=' +
      a[4] + ' bytes=' + a[5] + ' cover=' + a[6] + ' sort=' + a[8] + ' |');
  }
  if (result.samplePhoto.length) {
    const p = result.samplePhoto;
    L.push('| media_photo | id=' + p[0] + ' album_id=' + p[1] + ' seq=' + p[2] + ' rel_path=`' + p[3] + '` bytes=' +
      p[4] + ' w/h=' + p[5] + '/' + p[6] + ' is_big=' + p[7] + ' |');
  }
  if (result.sampleVideo.length) {
    const v = result.sampleVideo;
    L.push('| media_video | id=' + v[0] + ' folder=' + v[1] + ' title=' + v[2] + ' size=' + v[3] + ' dur=' + v[4] +
      ' w/h=' + v[5] + '/' + v[6] + ' vcodec=' + v[7] + ' browser_ok=' + v[8] + ' poster=' + v[9] +
      ' doc=' + v[10] + ' sub=' + v[11] + ' mtime=' + v[12] + ' |');
  }
  L.push('');
  if (result.markedDirs.length) {
    L.push('`dir` 追加 ` #<albumId>` 的样例：');
    for (const [id, dir] of result.markedDirs) L.push('- `' + id + '` → `' + dir + '`');
    L.push('');
  }
  L.push('## 7. 媒体根只读证据');
  L.push('');
  L.push('脚本对 `' + o.mediaRoot + '` 与 `' + o.stationRoot + '` 只做 `readFileSync` / `statSync` / `existsSync`，无任何写调用。');
  L.push('导入前后快照一致=' + (sigBefore.join('|') === sigAfter.join('|') ? '**是 ✅**' : '**否 ❌**'));
  L.push('');
  L.push('| 路径 | 导入前 | 导入后 |');
  L.push('| --- | --- | --- |');
  for (let i = 0; i < sigBefore.length; i += 1) {
    const b = sigBefore[i].split('\t');
    const a = (sigAfter[i] || '').split('\t');
    L.push('| ' + b[0] + ' | ' + b[1] + ' | ' + a[1] + ' |');
  }
  L.push('');
  L.push('## 8. 已知限制');
  L.push('');
  for (const w of warnings) L.push('- ' + w);
  L.push('');
  return L.join('\n');
}

// ---------------------------------------------------------------- main

function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.help) {
    log(HELP);
    return 0;
  }
  const tStart = Date.now();
  o.out = path.resolve(o.out);
  fs.mkdirSync(o.out, { recursive: true });

  log('== 读取源数据 ' + o.source);
  const data = loadSource(o);
  log('   videos=' + data.videos.length + ' albums=' + data.albums.length +
    ' photos=' + data.albums.reduce((s, a) => s + a.photos.length, 0) +
    ' people=' + data.people.length);

  const sigBefore = rootSignature(o);

  log('== 生成 SQL ...');
  const built = buildSql(data, o);
  const sqlPath = path.join(o.out, 'import.sql');
  fs.writeFileSync(sqlPath, built.sql, 'utf8');
  log('   ' + sqlPath + '  ' + (Buffer.byteLength(built.sql) / 1024).toFixed(0) + ' KB，生成耗时 ' +
    built.genMs + ' ms');
  for (const w of built.warn) log('   [warn] ' + w);

  const password = readPassword(o);

  log('== mysql 导入 ...');
  const tLoad = Date.now();
  const fd = fs.openSync(sqlPath, 'r');
  let run;
  try {
    run = mysqlRun(o, password, null, fd);
  } finally {
    fs.closeSync(fd);
  }
  const loadMs = Date.now() - tLoad;
  if (run.status !== 0) {
    log('导入失败（exit=' + run.status + '）：');
    log((run.stderr || '').trim().slice(0, 4000));
    return 1;
  }
  if ((run.stderr || '').trim()) log('   [mysql stderr] ' + run.stderr.trim().slice(0, 2000));
  log('   导入完成 ' + loadMs + ' ms');

  const countsRow = queryOne(o, password,
    'SELECT (SELECT COUNT(*) FROM media_video),(SELECT COUNT(*) FROM media_album),' +
    '(SELECT COUNT(*) FROM media_photo),(SELECT COUNT(*) FROM media_person),' +
    '(SELECT COUNT(*) FROM media_video_tag),(SELECT COUNT(*) FROM media_video_actor),' +
    '(SELECT COALESCE(SUM(size),0) FROM media_video),(SELECT COALESCE(SUM(bytes),0) FROM media_album)')
    .map(Number);
  const counts = {
    videos: countsRow[0], albums: countsRow[1], photos: countsRow[2], people: countsRow[3],
    tags: countsRow[4], actors: countsRow[5], vBytes: countsRow[6], aBytes: countsRow[7],
  };
  log('   条数 videos=' + counts.videos + ' albums=' + counts.albums + ' photos=' + counts.photos +
    ' people=' + counts.people + ' tags=' + counts.tags + ' actors=' + counts.actors);

  let result = null;
  let verifyMs = 0;
  let exit = 0;
  if (!o.skipVerify) {
    log('== 校验 ...');
    const tVerify = Date.now();
    result = verify(o, password, data, built.stats);
    result.counts = counts;
    verifyMs = Date.now() - tVerify;
    for (const c of result.checks) {
      log('   ' + (c.ok ? '[ok]  ' : '[FAIL]') + ' ' + c.name + '：期望 ' + c.expect + ' / 实际 ' + c.actual +
        (c.extra ? ' ' + c.extra : ''));
    }
    if (result.failed.length) exit = 1;
  }

  const sigAfter = rootSignature(o);
  const readOnlyOk = sigBefore.join('|') === sigAfter.join('|');
  if (!readOnlyOk) {
    log('[FAIL] 媒体根在导入前后发生变化！');
    exit = 1;
  }

  const timing = {
    gen: built.genMs, load: loadMs, verify: verifyMs, total: Date.now() - tStart,
  };

  if (result) {
    const warnings = [
      '`media_photo.bytes=0`、`w/h=NULL`：index.json 的 `photos[]` 只有 `rel/i/big`，按契约留空。' +
        '校验阶段已 stat 全部 9628 个文件核对相册字节，但**不回填**到 `media_photo.bytes`。',
      '`media_photo` 的图片尺寸在 index.json 里不存在，因此不做逐图 ffprobe（仅 `big` 标记可用）。',
    ];
    if (built.stats.dirMarked) {
      warnings.push('`media_album.dir`：有 ' + built.stats.dirMarked +
        ' 本相册的物理目录与他人重复（念念 144 套同处 `写真/念念 Nian Nian`），' +
        '为满足表上 `UNIQUE KEY uk_album_dir (dir(200))`，这些行的 dir 追加了 ` #<albumId>`。' +
        '如需存原始 dir，请先放宽该唯一键（如改成 `(dir(200), id)`），再用 `--dir-mode=raw` 重跑。');
    }
    if (built.stats.missingPosters) {
      warnings.push('导入时有 ' + built.stats.missingPosters + ' 个视频的 poster 文件在磁盘缺失（仍按契约写入路径）。');
    }
    if (built.stats.missingVideoFiles) {
      warnings.push('导入时有 ' + built.stats.missingVideoFiles + ' 个视频源文件缺失，`mtime` 置 NULL。');
    }
    warnings.push('`media_video_tag` / `media_video_actor` 无唯一键，每次导入都是「先清空再全量写入」，' +
      '因此这两张表不支持 upsert 语义。');
    warnings.push('upsert 模式不覆盖 `hidden` 字段（管理端隐藏状态在重跑后保留）；源数据缩水时请用 `--truncate`。');

    const md = buildReport(o, data, result, timing, sigBefore, sigAfter, warnings);
    const reportPath = path.join(o.out, 'report.md');
    fs.writeFileSync(reportPath, md + '\n', 'utf8');
    log('== 报告已写入 ' + reportPath);
  }

  log('== 总耗时 ' + timing.total + ' ms（gen ' + timing.gen + ' / load ' + loadMs + ' / verify ' + verifyMs + '）');
  log(exit === 0 ? '结果：通过 ✅' : '结果：失败 ❌');
  return exit;
}

try {
  process.exitCode = main();
} catch (e) {
  process.stderr.write('ERROR: ' + (e && e.stack ? e.stack : e) + '\n');
  process.exitCode = 1;
}
