#!/usr/bin/env node
'use strict';
/**
 * 通用媒体缩略图生成器（零依赖，child_process 调 ffmpeg）
 *
 * 从库里读 media_video / media_photo，按 Java 端（MediaFileServiceImpl）的磁盘约定生成：
 *   视频封面   <thumbs>/v/<content_hash>.jpg                  scale=480:-2
 *              content_hash 为 NULL 时回退 <thumbs>/v/<videoId>.jpg（老命名，仍可用）
 *   图片缩略图 <thumbs>/p/<albumId>/<%04d>.jpg                 scale=400:-2
 *   大图预览   <thumbs>/pv/<albumId>/<%04d>.jpg（仅 is_big=1）  scale=1600:-2
 *
 * 视频封面按内容寻址：videoId = sha1(rel_path)[:12] 会随改名变化，content_hash 不会，
 * 所以同一份内容改名 / 移动后复用同一张封面。新老命名共存：Java 端先找 <hash>.jpg，
 * 找不到再回退 <id>.jpg，历史产物不迁移也能直接上线。
 *
 * 增量：产物存在、非空，且产物 mtime >= 源文件 mtime -> skip（源被替换 / 重编码后自动重建）；
 *       --force 强制重建；--no-fresh 关掉 mtime 判断，退回「存在即跳过」的旧语义。
 * 幂等：只写缩略图根目录，不碰源媒体目录。
 *
 * 输出（管理端 MediaScanServiceImpl 的 thumb 模式按这些行解析，改动前先看它）：
 *   START total=<n> tasks=videos,photos,previews force=<0|1> fresh=<0|1>
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
  secret: null,
  db: 'qtools',
  user: 'root',
  host: '127.0.0.1',
  port: 3306,
  force: false,
  fresh: true,
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
  --force             无条件重建全部产物（忽略 mtime 与存在性）
  --no-fresh          关闭新鲜度检查：产物存在即跳过（等价改动前的旧语义）
  --limit=<n>         只处理前 n 条（调试用）
  --secret=<file>     密码来源②：从 secret.yml 取 spring.datasource.druid.master.password
                      （管理端「重建缩略图」按钮触发时没有 MYSQL_PWD，用它）
  --help              显示本帮助

密码优先级：
  1) 环境变量 MYSQL_PWD
  2) --secret=<application-secret.yml / secret.yml 路径>，取其中
     spring.datasource.druid.master.password 的值（只认 master，不会误取 redis 的 password）
  3) 两样都没有 -> 报错退出
  取到后只通过子进程的 MYSQL_PWD 传给 mysql 客户端：不打印、不落盘、不进命令行参数。

产物路径（相对 --thumbs）：
  v/<content_hash>.jpg      视频封面，按内容寻址（-ss 3 抽帧；不足 3 秒自动回退 -ss 0）
  v/<videoId>.jpg           仅当 content_hash 为 NULL 时的回退命名
  p/<albumId>/0000.jpg...   图片缩略图（seq 4 位补零）
  pv/<albumId>/0000.jpg...  大图预览（仅 is_big=1）

增量判定：产物存在且非空，且产物 mtime >= 源文件 mtime 才算「已是最新」。
源文件被替换 / 重编码后 mtime 变新，下一次运行自动重建该条产物。

输出行（管理端解析，不要改）：
  START total=<n> ... / PROGRESS <done>/<total> / DONE ok=<n> skip=<n> fail=<n>
`;

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS, { ignored: [] });
  for (const a of argv) {
    if (a === '--force') o.force = true;
    else if (a === '--no-fresh') o.fresh = false;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--root=')) o.root = a.slice('--root='.length);
    else if (a.startsWith('--thumbs=')) o.thumbs = a.slice('--thumbs='.length);
    else if (a.startsWith('--ffmpeg=')) o.ffmpeg = a.slice('--ffmpeg='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
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

// ---------------------------------------------------------------- 密码

/**
 * 优先级：MYSQL_PWD 环境变量 > --secret=<yml> 里的 spring.datasource.druid.master.password。
 * 拿到后只通过子进程的 MYSQL_PWD 传给 mysql：不打印、不落盘、不进命令行参数。
 */
function readPassword(o) {
  if (process.env.MYSQL_PWD) return process.env.MYSQL_PWD;
  if (o.secret) return readSecretPassword(o.secret);
  throw new Error('未提供数据库密码：请设置环境变量 MYSQL_PWD，' +
    '或用 --secret=<application-secret/secret.yml 路径> 指定私密配置');
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
  if (!pwd) {
    throw new Error('--secret=' + abs + ' 里找不到 spring.datasource.druid.master.password');
  }
  return pwd;
}

/** 从 YAML 文本取 spring.datasource.druid.master.password 的值（缩进扫描 + 正则，零依赖，不引 js-yaml） */
function extractMasterPassword(text) {
  // 兼容扁平写法：spring.datasource.druid.master.password: xxx
  const flat = text.match(/^[ \t]*spring\.datasource\.druid\.master\.password[ \t]*:[ \t]*(.*)$/m);
  if (flat) return yamlScalar(flat[1]);

  const want = ['spring', 'datasource', 'druid', 'master', 'password'].join('.');
  const stack = []; // 缩进路径栈：{ indent, key }
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.replace(/^\uFEFF/, '');
    if (!line.trim() || /^[ \t]*#/.test(line)) continue;
    const m = line.match(/^([ \t]*)([A-Za-z0-9_.-]+):(?:[ \t]+(.*))?$/);
    if (!m) continue;
    const indent = m[1].replace(/\t/g, '  ').length;
    const value = m[3] === undefined ? '' : m[3];
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    const trail = stack.map((s) => s.key).concat(m[2]).join('.');
    // 只认 master.password：spring.data.redis.password、slave.password 等前缀对不上，天然排除
    if (value !== '' && m[2] === 'password' && trail.slice(-want.length) === want) {
      return yamlScalar(value);
    }
    stack.push({ indent, key: m[2] });
  }
  return null;
}

/** 取 YAML 标量：去引号、去行尾注释（引号内的 # 不算注释） */
function yamlScalar(raw) {
  const value = String(raw).trim();
  const quote = value[0];
  if (quote === '"' || quote === "'") {
    const end = value.indexOf(quote, 1);
    return end < 0 ? value.slice(1) : value.slice(1, end);
  }
  return value.replace(/[ \t]+#.*$/, '').trim();
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

/** 视频封面文件名：content_hash 优先（内容寻址），为空回退 videoId（老命名） */
function thumbName(v) {
  const hash = v.hash == null ? '' : String(v.hash).trim();
  return hash !== '' ? hash : v.id;
}

function planTasks(videos, photos, o) {
  const t = [];
  for (const v of videos) {
    // 内容寻址：同一份内容改名 / 移动后 content_hash 不变，仍复用同一张封面
    t.push({ task: 'videos', src: v.rel, out: path.join(o.thumbs, 'v', thumbName(v) + '.jpg'), kind: 'video' });
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

/** 产物存在且非空（旧语义，--no-fresh / ffmpeg 产物校验都用它） */
function alreadyDone(out) {
  try {
    return fs.statSync(out).size > 0;
  } catch (e) {
    return false;
  }
}

/** 源文件绝对路径（库里存的是相对媒体根的正斜杠路径） */
function srcPathOf(item, root) {
  return path.resolve(root, item.src.replace(/[\\/]+/g, path.sep));
}

/**
 * 新鲜度判定：产物存在、非空，且产物 mtime >= 源文件 mtime。
 * 源文件被替换 / 重编码后 mtime 变新 -> 判定为过期 -> 重建。
 * checkMtime=false（--no-fresh）时只要求产物存在，等价改动前的旧语义。
 */
function isFresh(out, src, checkMtime) {
  let outStat;
  try {
    outStat = fs.statSync(out);
  } catch (e) {
    return false; // 没产物，必须生成
  }
  if (outStat.size <= 0) return false; // 空文件（上次 ffmpeg 中断）也算没产物
  if (!checkMtime) return true;
  let srcStat;
  try {
    srcStat = fs.statSync(src);
  } catch (e) {
    return false; // 源文件不见了交给 buildOne 报 FAIL，不在这里静默跳过
  }
  return outStat.mtimeMs >= srcStat.mtimeMs;
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
  const src = srcPathOf(item, root);
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
  const password = readPassword(o);

  const videoRows = query(mysqlExe, o, password,
    'SELECT id,rel_path,content_hash FROM media_video ORDER BY id;');
  const photoRows = query(mysqlExe, o, password,
    'SELECT album_id,seq,rel_path,is_big FROM media_photo ORDER BY album_id,seq;');
  // mysql --batch 把 NULL 打成字面量 "NULL"，这里还原成 null，免得拿 "NULL" 当哈希拼文件名
  const videos = videoRows.map((c) => ({ id: c[0], rel: c[1], hash: c[2] === 'NULL' ? null : c[2] }));
  const photos = photoRows.map((c) => ({ albumId: c[0], seq: Number(c[1]), rel: c[2], isBig: c[3] }));

  const tasks = planTasks(videos, photos, o);
  const total = tasks.length;
  const startedAt = Date.now();
  const counts = { ok: 0, skip: 0, fail: 0 };
  log('START total=' + total + ' tasks=videos,photos,previews force=' + (o.force ? 1 : 0) +
    ' fresh=' + (o.fresh ? 1 : 0) + ' root=' + root + ' thumbs=' + o.thumbs);
  // 哈希没回填的视频只能按老命名产出：整批提示一次，避免刷屏（stderr，不干扰管理端解析 stdout）
  const noHash = videos.filter((v) => !v.hash || String(v.hash).trim() === '').length;
  if (noHash > 0) {
    warn(noHash + ' 个视频 content_hash 为 NULL，封面回退 v/<videoId>.jpg（重命名后会重复生成，建议先回填哈希）');
  }

  let done = 0;
  const perTask = {};
  for (const t of tasks) {
    if (!perTask[t.task]) perTask[t.task] = { total: 0, ok: 0, skip: 0, fail: 0 };
    perTask[t.task].total += 1;
    let result;
    if (!o.force && isFresh(t.out, srcPathOf(t, root), o.fresh)) {
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
