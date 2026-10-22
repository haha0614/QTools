#!/usr/bin/env node
'use strict';
/**
 * 通用媒体缩略图生成器（零依赖，child_process 调 ffmpeg）
 *
 * 从库里读 media_video / media_photo，按 Java 端（MediaController / MediaServiceImpl）的磁盘约定生成：
 *   视频封面   <thumbs>/v/<videoId>.jpg                       scale=480:-2
 *   图片缩略图 <thumbs>/p/<albumId>/<%04d>.jpg                 scale=400:-2
 *   大图预览   <thumbs>/pv/<albumId>/<%04d>.jpg（仅 is_big=1）  scale=1600:-2
 *
 * 增量：目标文件已存在且非空、且没给 --force -> skip。
 * 幂等：只写缩略图根目录，不碰源媒体目录。
 *
 * 输出（管理端 MediaScanServiceImpl 的 thumb 模式按这些行解析，改动前先看它）：
 *   START total=<n> tasks=videos,photos,previews force=<0|1>
 *   PROGRESS <done>/<total>          每条之后一行
 *   FAIL <rel_path>: <原因>           单条失败不中断
 *   TASK <task> total=<n> ok=<n> skip=<n> fail=<n>
 *   DONE ok=<n> skip=<n> fail=<n> seconds=<n>
 * 有失败条目时退出码仍为 0（与原作者 build_thumbs.js 一致），只有 ffmpeg 缺失 / 数据库读不到
 * 等致命错误才非 0。
 *
 * ffmpeg 优先级：--ffmpeg > 环境变量 MS_FFMPEG（Java 端会传）> PATH。
 * mysql 优先级：--mysql > 环境变量 MS_MYSQL > PATH；密码只从 MYSQL_PWD 读（不打印、不落盘）。
 */

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const DEFAULTS = {
  root: null,
  thumbs: null,
  ffmpeg: null,
  mysql: null,
  db: 'qtools',
  user: 'root',
  host: '127.0.0.1',
  port: 3306,
  force: false,
  limit: 0,
};

const HELP = `
用法：node build-thumbs.js --thumbs=<缩略图根> [--root=<媒体根>] [选项]

  --root=<dir>        媒体根（用于定位源文件），默认取环境变量 MS_MEDIA_ROOT，再退到当前目录
  --thumbs=<dir>      缩略图根（必填，也认环境变量 MS_THUMBS）
  --ffmpeg=<exe>      ffmpeg，优先级：--ffmpeg > 环境变量 MS_FFMPEG > PATH
  --mysql=<exe>       mysql 客户端，优先级：--mysql > 环境变量 MS_MYSQL > PATH
  --db=<name>         默认 qtools
  --user=<name>       默认 root
  --host=<host>       默认 127.0.0.1
  --port=<n>          默认 3306
  --force             重建已存在的产物（默认按「存在即跳过」增量）
  --limit=<n>         只处理前 n 条（调试用）
  --help              显示本帮助

密码：只从环境变量 MYSQL_PWD 读取（不打印、不落盘）。

产物路径（相对 --thumbs）：
  v/<videoId>.jpg           视频封面（-ss 3 抽帧；不足 3 秒自动回退 -ss 0）
  p/<albumId>/0000.jpg...   图片缩略图（seq 4 位补零）
  pv/<albumId>/0000.jpg...  大图预览（仅 is_big=1）

输出行（管理端解析，不要改）：
  START total=<n> ... / PROGRESS <done>/<total> / DONE ok=<n> skip=<n> fail=<n>
`;

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS, { ignored: [] });
  for (const a of argv) {
    if (a === '--force') o.force = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--root=')) o.root = a.slice('--root='.length);
    else if (a.startsWith('--thumbs=')) o.thumbs = a.slice('--thumbs='.length);
    else if (a.startsWith('--ffmpeg=')) o.ffmpeg = a.slice('--ffmpeg='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--host=')) o.host = a.slice('--host='.length);
    else if (a.startsWith('--port=')) o.port = a.slice('--port='.length);
    else if (a.startsWith('--limit=')) o.limit = Number(a.slice('--limit='.length));
    else o.ignored.push(a); // 管理端可能传旧脚本参数（--only/--album/--concurrency）：忽略而不是报错
  }
  if (!Number.isFinite(o.limit) || o.limit < 0) throw new Error('--limit 必须是 >= 0 的整数');
  o.limit = Math.trunc(o.limit);
  o.port = String(o.port);
  return o;
}

// ---------------------------------------------------------------- 工具

function log(msg) {
  process.stdout.write(msg + '\n');
}

function warn(msg) {
  process.stderr.write('WARN ' + msg + '\n');
}

function pad4(n) {
  return String(n).padStart(4, '0');
}

/** 在 PATH 里找可执行文件（Windows 需要拼 PATHEXT）；找不到返回 null */
function findInPath(name) {
  const exts = process.platform === 'win32'
    ? (process.env.PATHEXT || '.EXE;.CMD;.BAT').split(';').filter(Boolean)
    : [''];
  for (const dir of (process.env.PATH || '').split(path.delimiter).filter(Boolean)) {
    for (const ext of exts) {
      const candidate = path.join(dir, name + ext);
      try {
        if (fs.statSync(candidate).isFile()) return candidate;
      } catch (e) { /* 不存在，继续找 */ }
    }
  }
  return null;
}

/** 显式路径 > 环境变量 > PATH；都没有返回 null（由调用方决定报错措辞） */
function resolveTool(explicit, envName, exeName, exeFlag) {
  if (explicit) {
    if (!fs.existsSync(explicit)) throw new Error(exeFlag + ' 指定的文件不存在：' + explicit);
    return explicit;
  }
  const fromEnv = process.env[envName];
  if (fromEnv) {
    if (!fs.existsSync(fromEnv)) throw new Error(envName + ' 指向的文件不存在：' + fromEnv);
    return fromEnv;
  }
  const found = findInPath(exeName);
  if (!found) {
    throw new Error('找不到 ' + exeName + '：PATH 里没有、' + envName + ' 也没设置。\n' +
      '  请显式传 ' + exeFlag + '=<' + exeName + ' 可执行文件的完整路径>，或设置环境变量 ' + envName);
  }
  return found;
}

function readPassword() {
  const pwd = process.env.MYSQL_PWD;
  if (!pwd) {
    throw new Error('未设置 MYSQL_PWD 环境变量（密码只从环境变量读取，不打印、不落盘）：\n' +
      '  PowerShell:  $env:MYSQL_PWD = \'<密码>\'; node build-thumbs.js --thumbs=<缩略图根>');
  }
  return pwd;
}

function query(mysqlExe, o, password, sql) {
  const r = spawnSync(mysqlExe, ['-u', o.user, '--host=' + o.host, '--port=' + o.port,
    '--default-character-set=utf8mb4', '-D', o.db, '--batch', '-N', '-e', sql], {
    env: Object.assign({}, process.env, { MYSQL_PWD: password }),
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (r.error) throw new Error('调用 mysql 失败：' + r.error.message);
  if (r.status !== 0) {
    throw new Error('读库失败（mysql 退出码 ' + r.status + '）：\n' + (r.stderr || '').trim());
  }
  const text = (r.stdout || '').replace(/\r/g, '').replace(/\n+$/, '');
  if (!text) return [];
  return text.split('\n').map((line) => line.split('\t'));
}

// ---------------------------------------------------------------- 任务清单

function planTasks(videos, photos, o) {
  const t = [];
  for (const v of videos) {
    t.push({ task: 'videos', src: v.rel, out: path.join(o.thumbs, 'v', v.id + '.jpg'), kind: 'video' });
  }
  for (const p of photos) {
    t.push({
      task: 'photos', src: p.rel,
      out: path.join(o.thumbs, 'p', p.albumId, pad4(p.seq) + '.jpg'), kind: 'photo',
    });
  }
  for (const p of photos) {
    if (String(p.isBig) !== '1') continue;
    t.push({
      task: 'previews', src: p.rel,
      out: path.join(o.thumbs, 'pv', p.albumId, pad4(p.seq) + '.jpg'), kind: 'preview',
    });
  }
  return o.limit > 0 ? t.slice(0, o.limit) : t;
}

function alreadyDone(out) {
  try {
    return fs.statSync(out).size > 0;
  } catch (e) {
    return false;
  }
}

function runFfmpeg(ffmpeg, args) {
  const r = spawnSync(ffmpeg, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  if (r.error) return { ok: false, reason: 'ffmpeg 启动失败：' + r.error.message };
  if (r.status !== 0) {
    const tail = ((r.stderr || '') + (r.stdout || '')).replace(/\s+/g, ' ').trim().slice(0, 200);
    return { ok: false, reason: 'ffmpeg 退出码 ' + r.status + (tail ? '：' + tail : '') };
  }
  return { ok: true, reason: null };
}

function ffmpegArgsFor(item, src) {
  const base = ['-y', '-loglevel', 'error'];
  if (item.kind === 'video') return base.concat(['-ss', '3', '-i', src, '-frames:v', '1', '-vf', 'scale=480:-2', '-q:v', '4', item.out]);
  if (item.kind === 'photo') return base.concat(['-i', src, '-frames:v', '1', '-vf', 'scale=400:-2', '-q:v', '4', item.out]);
  return base.concat(['-i', src, '-frames:v', '1', '-vf', 'scale=1600:-2', '-q:v', '4', item.out]);
}

function buildOne(ffmpeg, item, root) {
  const src = path.resolve(root, item.src.replace(/[\\/]+/g, path.sep));
  if (!fs.existsSync(src)) return { ok: false, reason: '源文件不存在：' + src };
  fs.mkdirSync(path.dirname(item.out), { recursive: true });
  let r = runFfmpeg(ffmpeg, ffmpegArgsFor(item, src));
  // 源不足 3 秒时 -ss 3 抽不到帧：回退 -ss 0 再试一次
  if (!r.ok && item.kind === 'video') {
    const args = ffmpegArgsFor(item, src);
    args[args.indexOf('-ss') + 1] = '0';
    r = runFfmpeg(ffmpeg, args);
  }
  if (!r.ok) return { ok: false, reason: r.reason };
  if (!alreadyDone(item.out)) return { ok: false, reason: 'ffmpeg 返回 0 但没有产物' };
  return { ok: true, reason: null };
}

// ---------------------------------------------------------------- 主流程

function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.help) {
    log(HELP);
    return 0;
  }
  for (const a of o.ignored) warn('已忽略无法识别的参数（管理端/旧脚本兼容）：' + a);

  const thumbsRaw = o.thumbs || process.env.MS_THUMBS || '';
  if (!thumbsRaw) {
    throw new Error('缺少必填参数 --thumbs=<缩略图根>（也认环境变量 MS_THUMBS，--help 看用法）');
  }
  o.thumbs = path.resolve(thumbsRaw);
  const root = path.resolve(o.root || process.env.MS_MEDIA_ROOT || process.cwd());
  if (o.root === null && !process.env.MS_MEDIA_ROOT) {
    warn('未传 --root，按当前目录解析源文件：' + root);
  }

  // ffmpeg 不可用直接退出码 1（后面的 1 万张全都会失败，没有继续的意义）
  const ffmpeg = resolveTool(o.ffmpeg, 'MS_FFMPEG', 'ffmpeg', '--ffmpeg');
  const mysqlExe = resolveTool(o.mysql, 'MS_MYSQL', 'mysql', '--mysql');
  const password = readPassword();

  const videoRows = query(mysqlExe, o, password,
    'SELECT id,rel_path FROM media_video ORDER BY id;');
  const photoRows = query(mysqlExe, o, password,
    'SELECT album_id,seq,rel_path,is_big FROM media_photo ORDER BY album_id,seq;');
  const videos = videoRows.map((c) => ({ id: c[0], rel: c[1] }));
  const photos = photoRows.map((c) => ({ albumId: c[0], seq: Number(c[1]), rel: c[2], isBig: c[3] }));

  const tasks = planTasks(videos, photos, o);
  const total = tasks.length;
  const startedAt = Date.now();
  const counts = { ok: 0, skip: 0, fail: 0 };
  log('START total=' + total + ' tasks=videos,photos,previews force=' + (o.force ? 1 : 0) +
    ' root=' + root + ' thumbs=' + o.thumbs);

  let done = 0;
  const perTask = {};
  for (const t of tasks) {
    if (!perTask[t.task]) perTask[t.task] = { total: 0, ok: 0, skip: 0, fail: 0 };
    perTask[t.task].total += 1;
    let result;
    if (!o.force && alreadyDone(t.out)) {
      result = { ok: true, skip: true, reason: null };
    } else {
      const r = buildOne(ffmpeg, t, root);
      result = { ok: r.ok, skip: false, reason: r.reason };
    }
    if (!result.ok) {
      counts.fail += 1;
      perTask[t.task].fail += 1;
      log('FAIL ' + t.src + ': ' + result.reason);
    } else if (result.skip) {
      counts.skip += 1;
      perTask[t.task].skip += 1;
    } else {
      counts.ok += 1;
      perTask[t.task].ok += 1;
    }
    done += 1;
    log('PROGRESS ' + done + '/' + total);
  }

  for (const task of ['videos', 'photos', 'previews']) {
    const c = perTask[task];
    if (!c) continue;
    log('TASK ' + task + ' total=' + c.total + ' ok=' + c.ok + ' skip=' + c.skip + ' fail=' + c.fail);
  }
  log('DONE ok=' + counts.ok + ' skip=' + counts.skip + ' fail=' + counts.fail +
    ' seconds=' + Math.round((Date.now() - startedAt) / 1000));
  return 0; // 单条失败不改变退出码（与原作者 build_thumbs.js 一致）
}

try {
  process.exitCode = main();
} catch (e) {
  process.stderr.write('ERROR ' + (e && e.message ? e.message : String(e)) + '\n');
  process.exitCode = 1;
}
