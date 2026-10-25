#!/usr/bin/env node
'use strict';
/**
 * media_video 元数据补齐（零依赖，child_process 调 ffprobe）
 *
 * 背景：scan-media.js 只按扩展名登记，dur/w/h/vcodec 留 NULL；网页端没有时长和分辨率
 * 就没法排序、没法判断能否转码。本脚本用 ffprobe 读出第一个视频流的宽高/编码与容器时长，
 * 回写 media_video 的 dur/w/h/vcodec/fps 五列，别的列一概不动。
 *
 * 探测命令（不要改：改了要重新验解析）：
 *   ffprobe -v error -select_streams v:0 -show_entries stream=width,height,codec_name,r_frame_rate,avg_frame_rate:format=duration -of json <文件>
 * 只取 streams[0] 的 width/height/codec_name/r_frame_rate/avg_frame_rate 与 format.duration（秒，字符串）。
 * 时长四舍五入到 2 位小数（dur 是 decimal(10,2)）。
 *
 * 帧率（P5.16 新增，列见 doc/media_fps.sql）：
 *   r_frame_rate 优先（容器声明的帧栅格；CFR 素材就是 30000/1001 这种精确值），
 *   缺失/为 0/0 时退 avg_frame_rate（帧数/时长，VFR 或 r 无效时的兜底）——见 parseProbe 注释；
 *   两者都是「分子/分母」字符串（30000/1001），相除后四舍五入到 3 位小数写进 decimal(8,3)。
 *   29.970 与真值 30000/1001 的残差 3.3e-8 秒/帧，2 小时片累计 ≈7ms，远小于半帧，
 *   所以前端 snapToFrame 不会因此落到相邻帧。
 *   两个有理数都不可用（极少数纯音频容器 / 坏文件）时 fps 写 NULL 并计入 WARN：行仍算补齐失败一栏，
 *   下次跑会重新探测（与 dur 探测失败同一口径），不影响其它列。
 *
 * 待补集合：dur / w / h / vcodec / fps 五列里任一为 NULL。--force 则连已补齐的行一起重探。
 * 没跑过 doc/media_fps.sql 的库（缺 fps 列）不会被本脚本打死：启动时查一次列是否存在，
 * 缺列则只补四列并打一行 WARN 提示先执行迁移。
 * 幂等：补齐后的行自动退出待补集合，第二次跑不会再调 ffprobe，只会计入 skip。
 * 分页：按主键 id 顺序 keyset 分页（id > 上一页最后一条），每页 200 行，每 200 行打一行进度。
 * 单条失败（文件没了 / 不是视频 / ffprobe 报错）只计 FAIL 并继续，不中断整批。
 *
 * 输出（每行一条，便于 grep / 管理端解析）：
 *   START total=<n> pending=<n> force=<0|1> dry=<0|1> root=<媒体根>
 *   PROGRESS <examined>/<budget>     每 200 行一行
 *   DRY <rel_path> dur=.. w=.. h=.. vcodec=..    仅 --dry-run：解析结果照抄，不写库
 *   FAIL <rel_path>: <原因>          单条失败不中断
 *   PENDING <n>                      跑完还剩多少条待补
 *   enrich ok=<n> skip=<n> fail=<n> seconds=<n>
 * 有失败条目时退出码仍是 0（单条坏文件不该让整批看起来失败），只有 ffprobe / mysql 不可用、
 * 密码取不到、媒体根不存在这类致命错误才非 0。
 *
 * ffprobe 优先级：--ffprobe > 环境变量 MS_FFPROBE（管理端会传，和 MS_FFMPEG 一个套路）> PATH。
 * mysql 优先级：--mysql > 环境变量 MS_MYSQL > PATH。
 * 媒体根优先级：--root > 环境变量 MS_MEDIA_ROOT > media_root 表里 kind='root' 的第一条（rel_path 相对它）。
 * 密码优先级：MYSQL_PWD > --secret（取 secret.yml 的 spring.datasource.druid.master.password）。
 * 拿到密码后只通过子进程的 MYSQL_PWD 传给 mysql：不打印、不落盘、不进命令行参数。
 */

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const PAGE = 200;
const PROBE_TIMEOUT_MS = 60000;

const DEFAULTS = {
  root: null,
  ffprobe: null,
  mysql: null,
  secret: null,
  db: 'qtools',
  user: 'root',
  host: '127.0.0.1',
  port: 3306,
  limit: 0,
  dry: false,
  force: false,
};

const HELP = `
用法：node enrich-meta.js [选项]

  --root=<dir>        媒体根，rel_path 相对它解析；默认取环境变量 MS_MEDIA_ROOT，
                      再退到 media_root 表里 kind='root' 的第一条
  --ffprobe=<exe>     ffprobe，优先级：--ffprobe > 环境变量 MS_FFPROBE > PATH
  --mysql=<exe>       mysql 客户端，优先级：--mysql > 环境变量 MS_MYSQL > PATH
  --db=<name>         默认 qtools
  --user=<name>       默认 root
  --host=<host>       默认 127.0.0.1
  --port=<n>          默认 3306
  --limit=<n>         最多检视前 n 条（调试用）；不给则处理全部待补
  --dry-run           只调 ffprobe 打印解析结果，不写库
  --force             连 dur/w/h/vcodec 已补齐的行也重新探测
  --secret=<file>     密码来源②：从 secret.yml 取 spring.datasource.druid.master.password
  --help              显示本帮助

密码优先级：
  1) 环境变量 MYSQL_PWD
  2) --secret=<application-secret.yml / secret.yml 路径>，取其中
     spring.datasource.druid.master.password 的值（只认 master，不会误取 redis 的 password）
  3) 两样都没有 -> 报错退出
  取到后只通过子进程的 MYSQL_PWD 传给 mysql 客户端：不打印、不落盘、不进命令行参数。

探测命令：
  ffprobe -v error -select_streams v:0
          -show_entries stream=width,height,codec_name,r_frame_rate,avg_frame_rate:format=duration
          -of json <文件绝对路径>

待补集合：dur / w / h / vcodec / fps 任一为 NULL；--force 则全部行重探。
帧率写 media_video.fps（decimal(8,3)，先跑 doc/media_fps.sql；缺列时只补四列并 WARN）。
帧率取 r_frame_rate（栅格），无效时退 avg_frame_rate（帧数/时长）。
单条失败只记 FAIL 并继续；补齐后的行退出待补集合，重跑只计 skip。

输出行（可被管理端解析，别随便改）：
  START total=<n> pending=<n> force=<0|1> dry=<0|1> root=<dir>
  PROGRESS <examined>/<budget>
  DRY <rel_path> dur=.. w=.. h=.. vcodec=.. fps=..   （仅 --dry-run）
  FAIL <rel_path>: <原因>
  PENDING <n>
  enrich ok=<n> skip=<n> fail=<n> seconds=<n>
`;

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS, { ignored: [] });
  for (const a of argv) {
    if (a === '--force') o.force = true;
    else if (a === '--dry-run' || a === '--dry') o.dry = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--root=')) o.root = a.slice('--root='.length);
    else if (a.startsWith('--ffprobe=')) o.ffprobe = a.slice('--ffprobe='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--host=')) o.host = a.slice('--host='.length);
    else if (a.startsWith('--port=')) o.port = a.slice('--port='.length);
    else if (a.startsWith('--limit=')) o.limit = Number(a.slice('--limit='.length));
    else o.ignored.push(a); // 管理端可能沿用旧参数：忽略而不是报错
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

// ---------------------------------------------------------------- mysql

function sqlStr(v) {
  if (v === null || v === undefined) return 'NULL';
  const s = String(v).replace(/[\\'\u0000\n\r\u001a]/g, (c) => ({
    '\\': '\\\\', "'": "\\'", '\u0000': '\\0', '\n': '\\n', '\r': '\\r', '\u001a': '\\Z',
  }[c]));
  return "'" + s + "'";
}

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

/** mysql --batch 的字段反转义（\0 \t \n \\），路径用 / 分隔，一般用不到但别把反斜杠吃掉 */
function unescapeField(s) {
  return String(s).replace(/\\([0tn\\])/g, (_, c) => ({
    '0': '\u0000', t: '\t', n: '\n', '\\': '\\',
  }[c]));
}

function queryRows(o, mysqlExe, password, sql) {
  const r = runMysql(o, mysqlExe, password, sql);
  if (r.status !== 0) {
    throw new Error('查询失败（mysql 退出码 ' + r.status + '）：\n' + String(r.stderr || '').trim() +
      '\nSQL：' + sql.split('\n')[0]);
  }
  const rows = [];
  const text = String(r.stdout || '').replace(/\r\n/g, '\n');
  for (const line of text.split('\n')) {
    if (line === '') continue;
    rows.push(line.split('\t').map(unescapeField));
  }
  return rows;
}

/**
 * 待补集合：五列里任一为 NULL（dur 是最常见的，但 w/h/vcodec/fps 缺了同样没法用）。
 * fps 列要先跑 doc/media_fps.sql 才有 —— 没这列时由 hasFpsCol 关掉它（见 main）。
 */
const PENDING_SQL_BASE = '(dur IS NULL OR w IS NULL OR h IS NULL OR vcodec IS NULL)';
const PENDING_SQL_FPS = '(dur IS NULL OR w IS NULL OR h IS NULL OR vcodec IS NULL OR fps IS NULL)';

/** 本次运行使用的待补条件（按 fps 列是否存在决定） */
function pendingSql(hasFps) {
  return hasFps ? PENDING_SQL_FPS : PENDING_SQL_BASE;
}

/** media_video.fps 列是否存在（未迁移的老库 = false，脚本照旧只补四列） */
function hasFpsCol(o, mysqlExe, password) {
  const rows = queryRows(o, mysqlExe, password,
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()" +
    " AND table_name = 'media_video' AND column_name = 'fps';");
  return !!(rows.length && Number(rows[0][0]) > 0);
}

function readCounts(o, mysqlExe, password, hasFps) {
  const rows = queryRows(o, mysqlExe, password,
    'SELECT COUNT(*), SUM(' + pendingSql(hasFps) + ') FROM media_video;');
  const cols = rows.length ? rows[0] : ['0', 'NULL'];
  const total = Number(cols[0]) || 0;
  // 空表时 SUM 返回 NULL
  const pending = Number(cols[1] === 'NULL' || cols[1] === undefined ? 0 : cols[1]) || 0;
  return { total: total, pending: pending };
}

/**
 * keyset 分页取一页待办行。--force 时不加待补过滤（全部重探）。
 * 用 id > 游标 而不是 OFFSET：补齐的行会退出待补集合，OFFSET 会漏行。
 */
function fetchPage(o, mysqlExe, password, cursor, size, hasFps) {
  const where = [];
  if (!o.force) where.push(pendingSql(hasFps));
  if (cursor !== null) where.push('id > ' + sqlStr(cursor));
  const sql = 'SELECT id,rel_path,dur,w,h,vcodec' + (hasFps ? ',fps' : '') + ' FROM media_video' +
    (where.length ? ' WHERE ' + where.join(' AND ') : '') +
    ' ORDER BY id LIMIT ' + size + ';';
  return queryRows(o, mysqlExe, password, sql).map((c) => ({
    id: c[0], rel: c[1], dur: c[2], w: c[3], h: c[4], vcodec: c[5], fps: hasFps ? c[6] : 'NULL',
  }));
}

/** mysql --batch 把 NULL 打成字面量 "NULL" */
function isNullish(v) {
  return v === undefined || v === null || v === 'NULL';
}

function isComplete(row) {
  return !isNullish(row.dur) && !isNullish(row.w) && !isNullish(row.h) &&
    !isNullish(row.vcodec) && !isNullish(row.fps);
}

// ---------------------------------------------------------------- ffprobe

function probeArgs(file) {
  return ['-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,codec_name,r_frame_rate,avg_frame_rate:format=duration',
    '-of', 'json', file];
}

/** 帧率上限：更高的只有高速摄影素材，播放器不需要也不该按它做帧级 seek */
const FPS_MAX = 240;

/**
 * "30000/1001" / "25/1" / "29.97" → 帧率数字（四舍五入到 3 位，与 decimal(8,3) 一致）。
 * "0/0"（ffprobe 对 VFR / 坏流的常见输出）与越界值一律 null，由调用方退另一个字段。
 */
function parseFrameRate(raw) {
  if (raw === null || raw === undefined) return null;
  const s = String(raw).trim();
  if (!s) return null;
  const m = s.match(/^(\d+)\s*\/\s*(\d+)$/);
  const n = m ? Number(m[1]) / Number(m[2]) : Number(s);
  if (!Number.isFinite(n) || n <= 0 || n > FPS_MAX) return null;
  const rounded = Math.round(n * 1000) / 1000;
  return rounded > 0 ? rounded : null;
}

function runProbe(ffprobe, file) {
  const r = spawnSync(ffprobe, probeArgs(file), {
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
    timeout: PROBE_TIMEOUT_MS, // 坏文件会把 ffprobe 挂住
  });
  if (r.error) {
    const code = r.error.code === 'ETIMEDOUT' ? '探测超时（' + PROBE_TIMEOUT_MS + 'ms）' : r.error.message;
    return { ok: false, reason: 'ffprobe 启动失败：' + code };
  }
  if (r.status !== 0) {
    const tail = (String(r.stderr || '') + String(r.stdout || '')).replace(/\s+/g, ' ').trim().slice(0, 200);
    return { ok: false, reason: 'ffprobe 退出码 ' + r.status + (tail ? '：' + tail : '') };
  }
  return parseProbe(String(r.stdout || ''));
}

/** 解析 -of json 的输出：只要第一个视频流的 w/h/codec 与容器时长 */
function parseProbe(stdout) {
  let j;
  try {
    j = JSON.parse(stdout);
  } catch (e) {
    return { ok: false, reason: 'ffprobe 输出不是合法 JSON：' + stdout.replace(/\s+/g, ' ').trim().slice(0, 120) };
  }
  const stream = (j.streams || [])[0];
  if (!stream) return { ok: false, reason: '没有视频流' };
  const w = Math.trunc(Number(stream.width));
  const h = Math.trunc(Number(stream.height));
  if (!Number.isFinite(w) || w <= 0 || !Number.isFinite(h) || h <= 0) {
    return { ok: false, reason: '宽高无效（width=' + stream.width + ' height=' + stream.height + '）' };
  }
  const codec = stream.codec_name ? String(stream.codec_name).trim() : '';
  if (!codec) return { ok: false, reason: 'codec_name 缺失' };
  const rawDur = (j.format || {}).duration;
  const dur = Number(rawDur);
  if (!Number.isFinite(dur) || dur <= 0) {
    return { ok: false, reason: '时长无效（format.duration=' + rawDur + '）' };
  }
  if (dur >= 1e8) return { ok: false, reason: '时长超出 decimal(10,2) 范围：' + dur };
  // P5.16 帧率：**实帧率（r_frame_rate）优先** —— 它是容器声明的帧栅格
  // （CFR 素材就是 30000/1001 = 29.970030 这种精确值）。
  // 平均帧率（avg_frame_rate = 帧数/容器时长）只在 r 不可用时兜底：它不是栅格，
  // 实测 2 小时那片 r=30000/1001、avg=1986093000/66271139=29.969163，用它当栅格
  // 22 万帧会累计偏出 ≈0.24 秒（7 帧），帧级 seek 会落错帧。
  const fps = parseFrameRate(stream.r_frame_rate) || parseFrameRate(stream.avg_frame_rate);
  return {
    ok: true,
    meta: { dur: Math.round(dur * 100) / 100, w: w, h: h, codec: codec.slice(0, 32), fps: fps },
  };
}

// ---------------------------------------------------------------- 媒体根

function resolveRoot(o, mysqlExe, password) {
  let raw = o.root || process.env.MS_MEDIA_ROOT || null;
  if (!raw) {
    const rows = queryRows(o, mysqlExe, password,
      "SELECT path FROM media_root WHERE kind='root' ORDER BY id LIMIT 1;");
    if (rows.length && !isNullish(rows[0][0]) && String(rows[0][0]).trim() !== '') {
      raw = String(rows[0][0]).trim();
      warn('未传 --root / MS_MEDIA_ROOT，按 media_root 表的 path 解析：' + raw);
    }
  }
  if (!raw) {
    throw new Error('无法确定媒体根：请传 --root=<dir>、设置环境变量 MS_MEDIA_ROOT，' +
      '或确保 media_root 表里有一条 kind=root 的记录');
  }
  const abs = path.resolve(raw.replace(/[\\/]+/g, path.sep));
  if (!fs.existsSync(abs) || !fs.statSync(abs).isDirectory()) {
    throw new Error('媒体根不存在或不是目录：' + abs);
  }
  return abs;
}

/** rel_path 存的是相对媒体根的正斜杠路径 */
function srcPathOf(root, rel) {
  return path.resolve(root, String(rel).replace(/[\\/]+/g, path.sep));
}

// ---------------------------------------------------------------- 单条

function updateRow(o, mysqlExe, password, id, meta, hasFps) {
  // fps 只在列存在时写（未迁移老库照旧只补四列）；探测不到的帧率写 NULL，不写 0
  const fpsSql = hasFps ? (', fps=' + (meta.fps == null ? 'NULL' : meta.fps.toFixed(3))) : '';
  const sql = 'UPDATE media_video SET dur=' + meta.dur.toFixed(2) +
    ', w=' + meta.w + ', h=' + meta.h + ', vcodec=' + sqlStr(meta.codec) + fpsSql +
    ' WHERE id=' + sqlStr(id) + ';';
  const r = runMysql(o, mysqlExe, password, sql);
  if (r.status !== 0) {
    const tail = String(r.stderr || '').replace(/\s+/g, ' ').trim().slice(0, 200);
    throw new Error('更新失败（mysql 退出码 ' + r.status + '）' + (tail ? '：' + tail : ''));
  }
}

function enrichOne(o, mysqlExe, password, ffprobe, root, row, hasFps) {
  if (!o.force && isComplete(row)) return { ok: true, skip: true };
  const file = srcPathOf(root, row.rel);
  if (!fs.existsSync(file)) return { ok: false, reason: '源文件不存在：' + file };
  const p = runProbe(ffprobe, file);
  if (!p.ok) return { ok: false, reason: p.reason };
  const fpsText = p.meta.fps == null ? 'NULL' : p.meta.fps.toFixed(3);
  if (o.dry) {
    log('DRY ' + row.rel + ' dur=' + p.meta.dur.toFixed(2) + ' w=' + p.meta.w +
      ' h=' + p.meta.h + ' vcodec=' + p.meta.codec + ' fps=' + fpsText);
    return { ok: true, skip: false };
  }
  try {
    updateRow(o, mysqlExe, password, row.id, p.meta, hasFps);
  } catch (e) {
    return { ok: false, reason: e.message };
  }
  // 帧率探测不到：其它列已写好，但这一行仍留在待补集合里（下次重探同一口径与 dur 一致）
  if (hasFps && p.meta.fps == null) {
    return { ok: false, reason: '帧率不可用（avg_frame_rate / r_frame_rate 都无效）' };
  }
  return { ok: true, skip: false };
}

// ---------------------------------------------------------------- 主流程

function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.help) {
    log(HELP);
    return 0;
  }
  for (const a of o.ignored) warn('已忽略无法识别的参数（管理端/旧脚本兼容）：' + a);

  // ffprobe / mysql 不可用直接退出码 1：后面的每一条都会失败，没有继续的意义
  const ffprobe = resolveTool(o.ffprobe, 'MS_FFPROBE', 'ffprobe', '--ffprobe');
  const mysqlExe = resolveTool(o.mysql, 'MS_MYSQL', 'mysql', '--mysql');
  const password = readPassword(o);
  const root = resolveRoot(o, mysqlExe, password);

  // fps 列是 P5.16 才加的（doc/media_fps.sql）：老库没这列时只补四列，不因缺列整批报错
  const hasFps = hasFpsCol(o, mysqlExe, password);
  if (!hasFps) {
    warn('media_video 没有 fps 列：本次只补 dur/w/h/vcodec。' +
      '需要帧级 seek 的帧率先执行 doc/media_fps.sql 再重跑本脚本');
  }
  const counts = readCounts(o, mysqlExe, password, hasFps);
  const startedAt = Date.now();
  const c = { ok: 0, skip: 0, fail: 0 };
  log('START total=' + counts.total + ' pending=' + counts.pending +
    ' force=' + (o.force ? 1 : 0) + ' dry=' + (o.dry ? 1 : 0) + ' fps=' + (hasFps ? 1 : 0) +
    ' root=' + root);

  // 全库已补齐（非 --force）：没有待办行，不必扫全表；已补齐的行就是本次的 skip
  if (!o.force && counts.pending === 0) {
    c.skip = o.limit > 0 ? Math.min(o.limit, counts.total) : counts.total;
  } else {
    const work = o.force ? counts.total : counts.pending;
    const budget = o.limit > 0 ? Math.min(o.limit, work) : work;
    let examined = 0;
    let cursor = null;
    while (examined < budget) {
      const rows = fetchPage(o, mysqlExe, password, cursor, Math.min(PAGE, budget - examined), hasFps);
      if (rows.length === 0) break;
      for (const row of rows) {
        cursor = row.id;
        examined += 1;
        const res = enrichOne(o, mysqlExe, password, ffprobe, root, row, hasFps);
        if (!res.ok) {
          c.fail += 1;
          log('FAIL ' + row.rel + ': ' + res.reason);
        } else if (res.skip) {
          c.skip += 1;
        } else {
          c.ok += 1;
        }
        if (examined % PAGE === 0) log('PROGRESS ' + examined + '/' + budget);
      }
    }
  }

  // 剩余待补：跑完再数一次，让调用方一眼看到「还有多少要补」
  if (!o.dry) {
    try {
      log('PENDING ' + readCounts(o, mysqlExe, password, hasFps).pending);
    } catch (e) {
      warn('回读剩余条数失败：' + e.message);
    }
  }
  log('enrich ok=' + c.ok + ' skip=' + c.skip + ' fail=' + c.fail +
    ' seconds=' + Math.round((Date.now() - startedAt) / 1000));
  return 0; // 单条失败不改变退出码
}

try {
  process.exitCode = main();
} catch (e) {
  process.stderr.write('ERROR ' + (e && e.message ? e.message : String(e)) + '\n');
  process.exitCode = 1;
}
