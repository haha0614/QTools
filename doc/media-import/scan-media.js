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
 *         id = sha1(rel_path) 前 12 位（与 Java 端一致）；命中内容哈希且路径变了则 id 保持不变（见下「身份」）
 *         folder = rel_path 第一段（根目录下直接放文件时用「默认」）
 *         rel_path = 正斜杠相对路径；title = 文件名去扩展名
 *         browser_ok = .mp4/.webm -> 1，其它 -> 0
 *         poster_path = thumbs/v/<id>.jpg（文件由 build-thumbs.js 生成）
 *         dur/w/h/vcodec 留 NULL（不跑 ffprobe），且 upsert 时不覆盖已有值
 *   图片  .jpg .jpeg .png .webp .gif .bmp  -> media_photo
 *         album-mode=dir（默认）：每个「图片所在目录」算一本相册
 *         album-mode=prefix      ：目录内再按文件名（去扩展名）开头 ^(\d{1,4})[_\-\s] 分组，每组一本相册；
 *                                  没有前缀的文件归入该目录的未分组册（title 用目录名）
 *         album.id = 按 (dir_key,title) 命中已有行则复用其 id，否则取下一个未占用的 aNNN
 *         album.dir_key = 该册图片所在目录（相对路径、/ 分隔、无首尾斜杠；根目录散图册 = 空串）
 *         album.dir      = 同 dir_key（同一目录可以有多本相册）
 *         photo.seq = 册内文件名自然序（数字感知）从 0 递增
 *         photo.is_big = bytes >= --big-bytes（默认 2MB）
 *         album.photo_count / album.bytes 累加；cover_path = thumbs/p/<albumId>/0000.jpg
 *   people 恒为 0：通用扫描不生成人物（media_album.person_id 留 NULL）
 *
 * 身份（可移植性，配合 doc/media_portability.sql）：
 *   content_hash = oshash = md5(十进制 size + 首 --hash-bytes + 中 --hash-bytes + 尾 --hash-bytes) 前 16 位十六进制；
 *                  文件不超过 3 段时整文件顺序读（区间不重复）；用 fs.readSync 定位读，不整文件进内存。
 *   视频：先按 content_hash 找已有行
 *           命中且 rel_path 相同 -> 普通 upsert（更新 size/mtime/browser_ok/poster_path/content_hash）
 *           命中且 rel_path 不同 -> 判定改名/移动：UPDATE 该行 rel_path 等，**id 不变**
 *           未命中 -> 按现行规则新插（id = sha1(rel_path)[:12]）
 *         同内容的两份真实副本 -> 保留先入库那行做主文件，其余路径写入 media_video_alias，
 *                                  收尾打印 ALIAS 与「别名=N」，不再丢弃；别名表未建时退回老行为（打印 DUP … 忽略=<path>）
 *   图片：同理（命中且 rel_path/位置不同 -> UPDATE rel_path/bytes/is_big 与 album_id/seq），收藏靠 content_hash 不断线；
 *         同一本相册内出现同内容的两份文件时，后一份路径写入 media_photo_alias
 *         （主表仍按磁盘顺序各占一行，册内张数不缩水）
 *
 * 多库根（配合 doc/media_multiroot.sql）：
 *   --root-id=<n> 指定本次扫描写入 media_video/media_photo 的 root_id；
 *   缺省取 media_root 里 kind='root' 的第一行，取不到写 NULL 并 WARN（NULL = 后端用配置 qtools.media.rootPath）。
 *   已知限制：同一 rel_path 同时出现在两个库根下时主表仍会互相覆盖（uk_video_rel 建在 rel_path 上），
 *   视频 id 也仍按 sha1(rel_path) 生成，因此两个根下的相对路径必须互不相同。
 *
 * 写库方式复用 import.js 已验证的做法：内存拼一个 SQL 文本，单事务，用 mysql 客户端经 stdin 灌入，
 * 固定 --default-character-set=utf8mb4；密码只从 MYSQL_PWD 环境变量读（不打印、不落盘）。
 *
 * 幂等：默认 INSERT ... AS n ON DUPLICATE KEY UPDATE（视频按 id、相册按 id、图片按 (album_id,seq)），
 *       且不覆盖 hidden / person_id / dur / w / h / vcodec。
 *       --truncate --yes 才先清空 media_photo / media_album / media_video。
 *
 * 安全：对 --root 只读（readdirSync / statSync / readSync），任何情况下不写媒体目录。
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
/** media_album.dir_key / dir VARCHAR(600) */
const MAX_DIR_KEY = 600;
/** 回填/预载的分页行数 */
const PAGE = 500;
/** 前缀分册：文件名（去扩展名）开头的序号，如 001_xxx */
const PREFIX_RE = /^(\d{1,4})[_\-\s]/;

const DEFAULTS = {
  db: 'qtools',
  user: 'root',
  host: '127.0.0.1',
  port: 3306,
  bigBytes: 2097152,
  batch: 400,
  hashBytes: 65536,
  albumMode: 'dir',
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
  --dry-run           只统计并打印，不写库（不需要数据库/mysql 客户端；不读库内状态，所见即全部新建）
  --dump-sql=<file>   把生成的 SQL 写入该文件并打印前 20 行，**不执行**（可与 --dry-run 同用）
  --secret=<file>     密码来源②：从 secret.yml 取 spring.datasource.druid.master.password
                      （管理端「重建索引」按钮触发时没有 MYSQL_PWD，用它）
  --big-bytes=<n>     判定 is_big=1 的字节阈值，默认 2097152（2MB）
  --exclude=<a,b>     按目录名排除（任意层级），默认 _媒体站,trash,.git,node_modules,thumbs,pv
  --root-id=<n>       本次扫描写入的库根 media_root.id（默认取 media_root 里 kind='root' 的第一行；
                      取不到就写 NULL 并 WARN，NULL = 后端用配置 qtools.media.rootPath）
  --no-hash           不计算内容哈希：content_hash 写 NULL，同时关闭改名/移动自动识别
  --hash-bytes=<n>    oshash 每段字节数，默认 65536（64KB）；文件不超过 3 段时整文件顺序读
  --album-mode=dir|prefix
                      相册划分：dir=一个目录一本册（默认）；
                      prefix=目录内再按文件名（去扩展名）开头 ^(\d{1,4})[_\-\s] 分组，每组一本册
  --backfill-hash     给库里 content_hash IS NULL 的行补哈希（按 --root + rel_path 读文件），
                      顺带补齐 media_album.dir_key；文件不存在只计数不删行
  --limit=<n>         --backfill-hash 时最多处理多少行（默认不限）
  --help              显示本帮助

密码优先级：
  1) 环境变量 MYSQL_PWD
  2) --secret=<application-secret.yml / secret.yml 路径>，取其中
     spring.datasource.druid.master.password 的值（只认 master，不会误取 redis 的 password）
  3) 两样都没有 -> 报错退出
  取到后只通过子进程的 MYSQL_PWD 传给 mysql 客户端：不打印、不落盘、不进命令行参数。

  PowerShell:  $env:MYSQL_PWD = '<root 密码>'; node scan-media.js --root=D:\\我的媒体
  或:          node scan-media.js --root=D:\\我的媒体 --secret=D:\\cfg\\secret.yml

补哈希（老库升级后跑一次）：
  node scan-media.js --root=D:\\我的媒体 --backfill-hash [--limit=N] [--dry-run]
  node scan-media.js --root=D:\\我的媒体 --backfill-hash --secret=D:\\cfg\\secret.yml

输出（管理端 MediaScanServiceImpl 按这些行解析，改动前先看它）：
  videos=<n> albums=<n> photos=<n> people=0            扫描到的源数据条数
  条数 videos=<n> albums=<n> photos=<n> people=<n>    写库后回读的库内条数
  新插入=<n> 移动=<n> 更新=<n> 重复=<n> 跳过=<n> 别名=<n>   本次写的分类计数
  ALIAS <hash> 主=<path> 别名=<path>                   同内容副本：主文件留在主表，这条进别名表
  DUP <hash> 保留=<path> 忽略=<path>                   同上，但别名表未建时退回老行为（副本被丢弃）
  回填 videos=<n> photos=<n> missing=<n>               --backfill-hash 的计数
`;

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS, {
    root: null, mysql: null, secret: null, dumpSql: null, truncate: false, yes: false, dryRun: false,
    ignored: [], noHash: false, backfillHash: false, limit: null, rootId: null,
  });
  for (const a of argv) {
    if (a === '--truncate') o.truncate = true;
    else if (a === '--yes' || a === '-y') o.yes = true;
    else if (a === '--dry-run') o.dryRun = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a === '--no-hash') o.noHash = true;
    else if (a === '--backfill-hash') o.backfillHash = true;
    else if (a.startsWith('--root=')) o.root = a.slice('--root='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--host=')) o.host = a.slice('--host='.length);
    else if (a.startsWith('--port=')) o.port = a.slice('--port='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
    else if (a.startsWith('--dump-sql=')) o.dumpSql = a.slice('--dump-sql='.length);
    else if (a.startsWith('--big-bytes=')) o.bigBytes = Number(a.slice('--big-bytes='.length));
    else if (a.startsWith('--hash-bytes=')) o.hashBytes = Number(a.slice('--hash-bytes='.length));
    else if (a.startsWith('--limit=')) o.limit = Number(a.slice('--limit='.length));
    else if (a.startsWith('--root-id=')) o.rootId = Number(a.slice('--root-id='.length));
    else if (a.startsWith('--album-mode=')) o.albumMode = a.slice('--album-mode='.length);
    else if (a.startsWith('--exclude=')) {
      o.exclude = a.slice('--exclude='.length).split(',').map((s) => s.trim()).filter(Boolean);
    } else {
      // 管理端会传它自己的参数（例如 --out=<dir>、旧 import.js 的 --dir-mode=raw）：
      // 认不出的忽略并告警，绝不因为多一个参数就不干活
      o.ignored.push(a);
    }
  }
  if (!Number.isFinite(o.bigBytes) || o.bigBytes <= 0) throw new Error('--big-bytes 必须是正整数');
  if (!Number.isFinite(o.hashBytes) || o.hashBytes <= 0) throw new Error('--hash-bytes 必须是正整数');
  o.hashBytes = Math.trunc(o.hashBytes);
  if (o.albumMode !== 'dir' && o.albumMode !== 'prefix') {
    throw new Error('--album-mode 只接受 dir 或 prefix（收到：' + o.albumMode + '）');
  }
  if (o.limit !== null && (!Number.isFinite(o.limit) || o.limit <= 0)) throw new Error('--limit 必须是正整数');
  if (o.rootId !== null && (!Number.isInteger(o.rootId) || o.rootId <= 0)) {
    throw new Error('--root-id 必须是正整数（media_root.id）；不确定就省略它，脚本会取 kind=root 的第一行');
  }
  if (o.noHash && o.backfillHash) throw new Error('--no-hash 与 --backfill-hash 互相矛盾：回填就是来算哈希的');
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

/** 库根 id：null/undefined 写 NULL（= 后端用配置 rootPath），不能用 sqlInt（那会写 0） */
function sqlRoot(v) {
  if (v === null || v === undefined) return 'NULL';
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? String(Math.trunc(n)) : 'NULL';
}

/** 毫秒时间戳 -> FROM_UNIXTIME(秒)；NaN -> NULL */
function sqlDateTime(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n)) return 'NULL';
  return 'FROM_UNIXTIME(' + Math.floor(n / 1000) + ')';
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

// ---------------------------------------------------------------- 内容哈希（oshash）

/**
 * oshash：md5( 十进制 size + 首段 + 中段 + 尾段 ) 取前 16 位十六进制。
 * 全程 fs.readSync 定位读，不把文件读进内存；文件不超过 3 段时整文件顺序读（三段会重叠，故不重复区间）。
 * 同一实现供「写入」与「--backfill-hash 回填」共用。
 */
function oshashFile(abs, chunkBytes) {
  const win = chunkBytes;
  const fd = fs.openSync(abs, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    const h = crypto.createHash('md5');
    h.update(String(size), 'utf8');
    if (size <= win * 3) {
      readRange(fd, h, 0, size, 1024 * 1024);
    } else {
      readRange(fd, h, 0, win, win);
      readRange(fd, h, Math.floor((size - win) / 2), win, win); // 中段：居中的 win 字节，完整落在文件内
      readRange(fd, h, size - win, win, win);
    }
    return h.digest('hex').slice(0, 16);
  } finally {
    fs.closeSync(fd);
  }
}

/** 从 start 起读 length 字节喂给 hash，每次最多 chunk 字节 */
function readRange(fd, h, start, length, chunk) {
  if (length <= 0) return;
  const buf = Buffer.allocUnsafe(Math.min(chunk, length));
  let pos = start;
  let left = length;
  while (left > 0) {
    const want = Math.min(buf.length, left);
    const got = fs.readSync(fd, buf, 0, want, pos);
    if (got <= 0) break; // 文件在扫描途中被截断：按已读到的算
    h.update(buf.subarray(0, got));
    pos += got;
    left -= got;
  }
}

// ---------------------------------------------------------------- 磁盘扫描

/** 前缀分册的稳定标题：前缀 + 该组首个文件名的可读片段（去前缀、去尾部帧号、下划线转空格） */
function prefixTitle(prefix, firstBase) {
  let rest = String(firstBase).replace(/^\d{1,4}[_\-\s]+/, '');
  rest = rest.replace(/([_\-\s]+\d{1,4})+$/, '');
  rest = rest.replace(/[_\-\s]+/g, ' ').trim();
  if (!rest) rest = String(firstBase).replace(/[_\-\s]+/g, ' ').trim();
  return (prefix + ' ' + rest).trim();
}

function dirTitleOf(dirKey, rootTitle) {
  if (dirKey === '') return rootTitle;
  return dirKey.slice(dirKey.lastIndexOf('/') + 1);
}

function scanDisk(root, o) {
  const sink = { files: [], errors: [], skippedLinks: 0 };
  walk(root, o.excludeSet, sink);

  const rootTitle = path.basename(root) || '未分组';
  const videos = [];
  const groups = new Map();
  const warnings = [];
  let skipped = 0;
  let longPath = 0;

  for (const f of sink.files) {
    const isVideo = VIDEO_EXT.has(f.ext);
    const isPhoto = PHOTO_EXT.has(f.ext);
    if (!isVideo && !isPhoto) continue;
    if (f.rel.length > MAX_REL) {
      longPath += 1;
      skipped += 1;
      continue;
    }
    let st;
    try {
      st = fs.statSync(f.abs);
    } catch (e) {
      warnings.push('stat 失败已跳过：' + f.rel + '（' + e.message + '）');
      skipped += 1;
      continue;
    }
    let hash = null;
    if (!o.noHash) {
      try {
        hash = oshashFile(f.abs, o.hashBytes);
      } catch (e) {
        warnings.push('哈希失败，content_hash 写 NULL：' + f.rel + '（' + (e.code || e.message) + '）');
      }
    }
    const base = f.name.slice(0, f.name.length - f.ext.length);
    if (isVideo) {
      const slash = f.rel.indexOf('/');
      videos.push({
        id: null,
        hash,
        rel: f.rel,
        title: base,
        folder: slash < 0 ? '默认' : f.rel.slice(0, slash),
        size: st.size,
        mtimeMs: st.mtimeMs,
        browserOk: BROWSER_OK_EXT.has(f.ext) ? 1 : 0,
        movedFrom: null,
      });
      continue;
    }
    let dirKey = f.rel.lastIndexOf('/') < 0 ? '' : f.rel.slice(0, f.rel.lastIndexOf('/'));
    if (dirKey.length > MAX_DIR_KEY) {
      warnings.push('目录路径超过 dir_key 上限 ' + MAX_DIR_KEY + '，已截断：' + dirKey);
      dirKey = dirKey.slice(0, MAX_DIR_KEY);
    }
    const m = o.albumMode === 'prefix' ? PREFIX_RE.exec(base) : null;
    const gkey = dirKey + '\u0000' + (m ? '#' + m[1] : '*');
    let g = groups.get(gkey);
    if (!g) {
      g = { key: gkey, dirKey, prefix: m ? m[1] : null, titleGuess: null, photos: [] };
      groups.set(gkey, g);
    }
    g.photos.push({
      id: null,
      hash,
      rel: f.rel,
      name: f.name,
      base,
      bytes: st.size,
      isBig: st.size >= o.bigBytes ? 1 : 0,
      movedFrom: null,
    });
  }

  const list = [...groups.values()];
  for (const g of list) {
    g.photos.sort((a, b) => naturalCompare(a.name, b.name));
    g.titleGuess = g.prefix ? prefixTitle(g.prefix, g.photos[0].base) : dirTitleOf(g.dirKey, rootTitle);
  }
  list.sort((a, b) => naturalCompare(a.dirKey, b.dirKey) ||
    naturalCompare(a.prefix === null ? '' : '#' + a.prefix, b.prefix === null ? '' : '#' + b.prefix));

  if (longPath) warnings.push(longPath + ' 个文件 rel_path 超过 ' + MAX_REL + ' 字符，已跳过');
  for (const e of sink.errors) warnings.push('目录读取失败：' + e);
  if (sink.skippedLinks) warnings.push(sink.skippedLinks + ' 个符号链接/联接点已跳过（不跟随）');

  videos.sort((a, b) => naturalCompare(a.rel, b.rel));
  return { videos, groups: list, warnings, skipped, rootTitle };
}

// ---------------------------------------------------------------- 库内状态

function emptyState() {
  return {
    videoById: new Map(),
    videoByRel: new Map(),
    videoByHash: new Map(),
    photoByHash: new Map(),
    albumById: new Map(),
    albumKey: new Map(),
    albumLegacyDirKey: new Map(),
    albumLegacyTitle: new Map(),
    maxAlbumNo: 0,
    maxSort: 0,
  };
}

/**
 * 一次性预载库内状态，等价于「每个文件先 SELECT ... WHERE content_hash = ?」，
 * 但避免 N 次进程启动（一台机器几万文件时 N 次 spawn 不可接受）。
 */
function loadState(o, mysqlExe, password) {
  const s = emptyState();

  const vRows = queryRows(o, mysqlExe, password,
    "SELECT id, rel_path, IFNULL(content_hash, '') FROM media_video;");
  for (const [id, rel, hash] of vRows) {
    s.videoById.set(id, { id, rel, hash });
    s.videoByRel.set(rel, id);
    if (hash) s.videoByHash.set(hash, { id, rel });
  }

  const pRows = queryRows(o, mysqlExe, password,
    "SELECT id, album_id, seq, rel_path, IFNULL(content_hash, '') FROM media_photo " +
    'WHERE content_hash IS NOT NULL;');
  for (const [id, albumId, seq, rel, hash] of pRows) {
    if (!hash) continue;
    const list = s.photoByHash.get(hash) || [];
    list.push({ id, albumId, seq: Number(seq), rel });
    s.photoByHash.set(hash, list);
  }

  const aRows = queryRows(o, mysqlExe, password,
    "SELECT id, dir_key IS NULL, IFNULL(dir_key, ''), IFNULL(dir, ''), title, sort FROM media_album;");
  for (const [id, isNull, dirKey, dir, title, sort] of aRows) {
    const row = { id, dirKey, dir, title, sort: Number(sort), dirKeyNull: isNull === '1' };
    s.albumById.set(id, row);
    if (!row.dirKeyNull) s.albumKey.set(dirKey + '\u0000' + title, row);
    else {
      const dk = dir + '\u0000' + title;
      const byDir = s.albumLegacyDirKey.get(dk) || [];
      byDir.push(row);
      s.albumLegacyDirKey.set(dk, byDir);
      const byTitle = s.albumLegacyTitle.get(title) || [];
      byTitle.push(row);
      s.albumLegacyTitle.set(title, byTitle);
    }
    const m = /^a(\d+)$/.exec(id);
    if (m) s.maxAlbumNo = Math.max(s.maxAlbumNo, Number(m[1]));
    s.maxSort = Math.max(s.maxSort, Number(sort) || 0);
  }
  return s;
}

/**
 * 本次扫描写入的库根 id
 *
 * 显式 --root-id 优先；否则取 media_root 里 kind='root' 的第一行。
 * media_root 不存在 / 没有根行 / 库连不上都只 WARN，root_id 写 NULL（后端回退配置 rootPath），
 * 绝不能因为一张可选的表让整次扫描失败。
 */
function resolveRootId(o, mysqlExe, password) {
  if (o.rootId !== null) return o.rootId;
  try {
    const rows = queryRows(o, mysqlExe, password,
      "SELECT id FROM media_root WHERE kind = 'root' ORDER BY id LIMIT 1;");
    const id = rows.length && rows[0].length ? Number(rows[0][0]) : NaN;
    if (Number.isInteger(id) && id > 0) return id;
    warn('media_root 里没有 kind=root 的库根：root_id 写 NULL（后端用配置 rootPath）；' +
      '要显式指定请加 --root-id=<n>');
  } catch (e) {
    warn('读取 media_root 失败，root_id 写 NULL：' + (e.message || e));
  }
  return null;
}

/** 别名表是否已建（doc/media_dup_alias.sql）；没建就把同内容副本退回老行为，不让整次扫描报错 */
function aliasTablesReady(o, mysqlExe, password) {
  const rows = queryRows(o, mysqlExe, password,
    'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() ' +
    "AND table_name IN ('media_video_alias', 'media_photo_alias');");
  const n = rows.length && rows[0].length ? Number(rows[0][0]) : 0;
  if (n === 2) return true;
  warn('别名表 media_video_alias / media_photo_alias 未建（先执行 doc/media_dup_alias.sql）：' +
    '同内容副本仍按老行为丢弃并打印 DUP，别名=0');
  return false;
}

// ---------------------------------------------------------------- 计划（分配 id / 识别移动）

/** 按 (dir_key,title) 认相册；老行 dir_key 为空时退化用 (dir,title) / 唯一 title / 前缀令牌匹配 */
function findAlbum(state, g, o, claimed) {
  const exact = state.albumKey.get(g.dirKey + '\u0000' + g.titleGuess);
  if (exact && !claimed.has(exact.id)) return exact;

  if (o.albumMode === 'prefix' && g.prefix) {
    for (const a of state.albumById.values()) {
      if (a.dirKeyNull || a.dirKey !== g.dirKey || claimed.has(a.id)) continue;
      if (!a.title.startsWith(g.prefix)) continue;
      const next = a.title.charAt(g.prefix.length);
      if (next === '' || /[ _\-\u3000]/.test(next)) return a; // 「001 元宵灯节」这类前缀令牌对上即复用
    }
  }

  const legacyDir = (state.albumLegacyDirKey.get(g.dirKey + '\u0000' + g.titleGuess) || [])
    .filter((a) => !claimed.has(a.id));
  if (legacyDir.length === 1) return legacyDir[0];

  const legacyTitle = (state.albumLegacyTitle.get(g.titleGuess) || []).filter((a) => !claimed.has(a.id));
  if (legacyTitle.length === 1) return legacyTitle[0];

  return null;
}

function allocAlbumId(state, claimed, nextNo) {
  for (;;) {
    nextNo.v += 1;
    const id = 'a' + String(nextNo.v).padStart(3, '0');
    if (!state.albumById.has(id) && !claimed.has(id)) return id;
  }
}

/**
 * 同内容副本（先入库那行是主文件，这份是别名）：
 * 别名表可用 -> 写别名表并计入 别名=N；否则退回老的「丢弃 + DUP」行为。
 */
function recordAlias(ctx, table, hash, keepRel, rel, size) {
  if (ctx.o.aliasEnabled) {
    ctx.list.push({ table, hash, rel, size, rootId: ctx.o.rootId });
    ctx.stats.alias += 1;
    return;
  }
  ctx.stats.dup += 1;
  ctx.dupList.push({ hash, keep: keepRel, drop: rel });
}

function planVideos(videos, state, ctx) {
  const stats = ctx.stats;
  const rows = [];
  const moves = [];
  const seen = new Map(); // hash -> 本次保留的 rel（同内容第二份进别名表）
  for (const v of videos) {
    if (v.hash) {
      const keep = seen.get(v.hash);
      if (keep !== undefined) {
        recordAlias(ctx, 'media_video_alias', v.hash, keep, v.rel, v.size);
        continue;
      }
      seen.set(v.hash, v.rel);
    }
    const hit = v.hash ? state.videoByHash.get(v.hash) : null;
    const pathId = crypto.createHash('sha1').update(v.rel, 'utf8').digest('hex').slice(0, 12);
    if (hit && hit.rel !== v.rel) {
      // 命中内容哈希但路径变了：改名/移动，id 保持不变
      v.id = hit.id;
      v.movedFrom = hit.rel;
      state.videoByRel.delete(hit.rel);
      moves.push(v);
      stats.move += 1;
    } else if (hit) {
      v.id = hit.id;
      rows.push(v);
      stats.update += 1;
    } else if (state.videoById.has(pathId)) {
      v.id = pathId; // 同一路径内容变了：按 id 覆盖（content_hash 一并更新）
      rows.push(v);
      stats.update += 1;
    } else {
      v.id = pathId;
      rows.push(v);
      stats.insert += 1;
    }
  }
  return { rows, moves };
}

function planPhotos(albums, state, ctx) {
  const stats = ctx.stats;
  const rows = [];
  const moves = [];
  const claimed = new Set();
  // 册内同内容副本：只在「同一本相册」里才算重复（同一张图出现在不同相册是合法的，
  // 那是内容复用而不是重复文件）；主表照旧各占一行，册内张数与磁盘保持一致
  const seenInAlbum = new Map();
  for (const a of albums) {
    a.photos.forEach((p, seq) => {
      p.seq = seq;
      if (p.hash) {
        const key = a.id + '\u0000' + p.hash;
        const keep = seenInAlbum.get(key);
        if (keep === undefined) seenInAlbum.set(key, p.rel);
        else recordAlias(ctx, 'media_photo_alias', p.hash, keep, p.rel, p.bytes);
      }
      let prior = null;
      if (p.hash) {
        const cands = state.photoByHash.get(p.hash) || [];
        prior = cands.find((r) => r.rel === p.rel && !claimed.has(r.id)) ||
          cands.find((r) => !claimed.has(r.id)) || null;
      }
      if (!prior) {
        rows.push({ albumId: a.id, seq, photo: p });
        stats.insert += 1;
        return;
      }
      claimed.add(prior.id);
      p.id = prior.id;
      if (prior.rel === p.rel && prior.albumId === a.id && prior.seq === seq) {
        rows.push({ albumId: a.id, seq, photo: p });
        stats.update += 1;
      } else {
        p.movedFrom = prior.rel;
        moves.push({ albumId: a.id, seq, photo: p });
        stats.move += 1;
      }
    });
  }
  return { rows, moves };
}

function planImport(scan, state, o) {
  const stats = { insert: 0, move: 0, update: 0, dup: 0, skip: scan.skipped, alias: 0 };
  const dupList = [];
  const ctx = { list: [], dupList, stats, o };
  const claimed = new Set();
  const nextNo = { v: state.maxAlbumNo };
  const albums = [];

  for (const g of scan.groups) {
    const hit = findAlbum(state, g, o, claimed);
    let id;
    let title;
    let sort;
    let reused;
    if (hit) {
      id = hit.id;
      title = hit.title;
      sort = hit.sort;
      reused = true;
    } else {
      id = allocAlbumId(state, claimed, nextNo);
      title = g.titleGuess;
      sort = state.maxSort + 1 + albums.length;
      reused = false;
    }
    claimed.add(id);
    const photos = g.photos;
    albums.push({
      id,
      reused,
      copyOf: hit && hit.title !== g.titleGuess ? hit.title : null,
      title,
      dirKey: g.dirKey,
      prefix: g.prefix,
      sort,
      photos,
      photoCount: photos.length,
      bytes: photos.reduce((s, p) => s + p.bytes, 0),
      cover: 'thumbs/p/' + id + '/0000.jpg',
    });
  }

  const videoPlan = planVideos(scan.videos, state, ctx);
  const photoPlan = planPhotos(albums, state, ctx);
  return {
    albums,
    videoRows: videoPlan.rows,
    movedVideos: videoPlan.moves,
    photoRows: photoPlan.rows,
    movedPhotos: photoPlan.moves,
    aliasList: ctx.list,
    dupList,
    stats,
  };
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

  // 1) 改名/移动：先腾出被占用的唯一键（rel_path / (album_id,seq)），避免两句 UPDATE 互相撞键
  if (model.movedVideos.length) {
    for (const v of model.movedVideos) {
      // 目标路径上若还坐着一条旧行（历史残留/上一次半途而废的扫描），让位给要移动的这一行
      lines.push('DELETE FROM media_video WHERE rel_path = ' + sqlStr(v.rel) + ' AND id <> ' + sqlStr(v.id) + ';');
    }
    for (const part of chunks(model.movedVideos.map((v) => sqlStr(v.id)), 500)) {
      lines.push("UPDATE media_video SET rel_path = CONCAT('~mv~', id) WHERE id IN (" + part.join(',') + ');');
    }
  }
  if (model.movedPhotos.length) {
    for (const part of chunks(model.movedPhotos.map((m) => sqlInt(m.photo.id)), 500)) {
      // 先把要移动的行挪到临时位置（album_id='' + 负 seq），否则册内序号轮换会撞 uk_photo_album_seq
      lines.push("UPDATE media_photo SET album_id = '', seq = -id WHERE id IN (" + part.join(',') + ');');
    }
  }

  // 2) 视频 upsert（新增/同路径更新；content_hash 一并写入）
  const videoRows = model.videoRows.map((v) => '(' + [
    sqlStr(v.id), sqlStr(v.folder), sqlStr(v.rel), sqlStr(v.title), sqlInt(v.size),
    'NULL', 'NULL', 'NULL', 'NULL', sqlInt(v.browserOk), sqlStr('thumbs/v/' + v.id + '.jpg'), '0',
    sqlDateTime(v.mtimeMs), sqlStr(v.hash), sqlRoot(o.rootId),
  ].join(',') + ')');
  for (const part of chunks(videoRows, o.batch)) {
    lines.push('INSERT INTO media_video (id,folder,rel_path,title,size,dur,w,h,vcodec,browser_ok,poster_path,hidden,mtime,content_hash,root_id) VALUES ' +
      part.join(',') +
      // dur/w/h/vcodec 不覆盖：本脚本不跑 ffprobe，别把别的工具填好的元数据清空
      ' AS n ON DUPLICATE KEY UPDATE folder=n.folder,rel_path=n.rel_path,title=n.title,' +
      'size=n.size,browser_ok=n.browser_ok,poster_path=n.poster_path,mtime=n.mtime,' +
      'content_hash=n.content_hash,root_id=n.root_id;');
  }

  // 3) 视频改名/移动：UPDATE 同一行，id 保持不变
  for (const v of model.movedVideos) {
    lines.push('UPDATE media_video SET rel_path=' + sqlStr(v.rel) +
      ',folder=' + sqlStr(v.folder) + ',title=' + sqlStr(v.title) +
      ',size=' + sqlInt(v.size) + ',browser_ok=' + sqlInt(v.browserOk) +
      ',poster_path=' + sqlStr('thumbs/v/' + v.id + '.jpg') +
      ',mtime=' + sqlDateTime(v.mtimeMs) + ',content_hash=' + sqlStr(v.hash) +
      ',root_id=' + sqlRoot(o.rootId) +
      ' WHERE id=' + sqlStr(v.id) + ';');
  }

  // 4) 相册（id 已按 (dir_key,title) 复用/新分配）
  const albumRows = model.albums.map((a) => '(' + [
    sqlStr(a.id), 'NULL', sqlStr(a.title), sqlStr(a.dirKey), sqlInt(a.photoCount),
    sqlInt(a.bytes), sqlStr(a.cover), "''", sqlInt(a.sort), '0', sqlStr(a.dirKey),
  ].join(',') + ')');
  for (const part of chunks(albumRows, o.batch)) {
    lines.push('INSERT INTO media_album (id,person_id,title,dir,photo_count,bytes,cover_path,note,sort,hidden,dir_key) VALUES ' +
      part.join(',') +
      // person_id / note / hidden 不覆盖：管理端的人工归类不能被重扫冲掉
      ' AS n ON DUPLICATE KEY UPDATE title=n.title,dir=n.dir,photo_count=n.photo_count,' +
      'bytes=n.bytes,cover_path=n.cover_path,sort=n.sort,dir_key=n.dir_key;');
  }

  // 5) 图片 upsert（content_hash 一并写入）
  const photoRows = model.photoRows.map((r) => '(' + [
    sqlStr(r.albumId), sqlInt(r.seq), sqlStr(r.photo.rel), sqlInt(r.photo.bytes), 'NULL', 'NULL',
    sqlInt(r.photo.isBig), '0', sqlStr(r.photo.hash), sqlRoot(o.rootId),
  ].join(',') + ')');
  for (const part of chunks(photoRows, o.batch)) {
    lines.push('INSERT INTO media_photo (album_id,seq,rel_path,bytes,w,h,is_big,hidden,content_hash,root_id) VALUES ' + part.join(',') +
      // w/h 不覆盖：本脚本不探测图片尺寸，别把别的工具填好的值清空
      ' AS n ON DUPLICATE KEY UPDATE rel_path=n.rel_path,bytes=n.bytes,is_big=n.is_big,' +
      'content_hash=n.content_hash,root_id=n.root_id;');
  }

  // 6) 图片改名/移动：UPDATE 同一行，并按新目录更新 album_id/seq
  for (const m of model.movedPhotos) {
    lines.push('UPDATE media_photo SET album_id=' + sqlStr(m.albumId) + ',seq=' + sqlInt(m.seq) +
      ',rel_path=' + sqlStr(m.photo.rel) + ',bytes=' + sqlInt(m.photo.bytes) +
      ',is_big=' + sqlInt(m.photo.isBig) + ',content_hash=' + sqlStr(m.photo.hash) +
      ',root_id=' + sqlRoot(o.rootId) +
      ' WHERE id=' + sqlInt(m.photo.id) + ';');
  }

  // 7) 目录里删过图：把每本相册 seq >= 当前张数 的残留行清掉，否则相册会越重扫越多
  for (const a of model.albums) {
    lines.push('DELETE FROM media_photo WHERE album_id = ' + sqlStr(a.id) +
      ' AND seq >= ' + sqlInt(a.photoCount) + ';');
  }
  // 孤儿图（相册已被删掉）：保证「每张图都能找到相册」恒成立
  lines.push('DELETE p FROM media_photo p LEFT JOIN media_album a ON a.id = p.album_id WHERE a.id IS NULL;');

  // 8) 同内容副本 -> 别名表（本期只沉淀数据，后端不读）
  for (const table of ['media_video_alias', 'media_photo_alias']) {
    const aliasRows = model.aliasList.filter((x) => x.table === table)
      .map((x) => '(' + [sqlStr(x.hash), sqlStr(x.rel), sqlRoot(x.rootId), sqlInt(x.size)].join(',') + ')');
    for (const part of chunks(aliasRows, o.batch)) {
      lines.push('INSERT INTO ' + table + ' (content_hash,rel_path,root_id,size) VALUES ' + part.join(',') +
        // 重扫幂等：(hash, rel_path) 唯一键命中就只刷 root_id/size，create_time 保留首次登记时间
        ' AS n ON DUPLICATE KEY UPDATE root_id=n.root_id,size=n.size;');
    }
  }

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

/** mysql --batch 的字段反转义（\0 \t \n \\），路径用 / 分隔，一般用不到但别把反斜杠吃掉 */
function unescapeField(s) {
  return String(s).replace(/\\([0tn\\])/g, (_, c) => ({
    '0': '\u0000', t: '\t', n: '\n', '\\': '\\',
  }[c]));
}

function queryRows(o, mysqlExe, password, sql) {
  const r = runMysql(o, mysqlExe, password, sql);
  if (r.status !== 0) {
    throw new Error('查询失败（mysql 退出码 ' + r.status + '）：\n' + (r.stderr || '').trim() +
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

function queryCounts(o, mysqlExe, password) {
  const sql = 'SELECT (SELECT COUNT(*) FROM media_video),(SELECT COUNT(*) FROM media_album),' +
    '(SELECT COUNT(*) FROM media_photo),(SELECT COUNT(*) FROM media_person);';
  const r = runMysql(o, mysqlExe, password, sql);
  if (r.status !== 0) throw new Error('回读条数失败：\n' + (r.stderr || '').trim());
  const cols = (r.stdout || '').replace(/\r/g, '').trim().split('\t');
  if (cols.length !== 4) throw new Error('回读条数格式异常：' + JSON.stringify(r.stdout));
  return '条数 videos=' + cols[0] + ' albums=' + cols[1] + ' photos=' + cols[2] + ' people=' + cols[3];
}

// ---------------------------------------------------------------- 回填（--backfill-hash）

/**
 * 遍历 content_hash IS NULL 的行，按 root + rel_path 读文件算哈希并 UPDATE。
 * 文件不存在只计 MISSING（不删行、不报错退出）；每 200 行打印进度。
 */
function backfillTable(o, root, mysqlExe, password, table, budget, stats) {
  const key = table === 'media_video' ? 'videos' : 'photos';
  let last = '';
  let seen = 0;
  for (;;) {
    if (budget.left <= 0) return;
    const limit = Math.min(PAGE, budget.left);
    const rows = queryRows(o, mysqlExe, password,
      'SELECT id, rel_path FROM ' + table + ' WHERE content_hash IS NULL AND id > ' + sqlStr(last) +
      ' ORDER BY id LIMIT ' + sqlInt(limit) + ';');
    if (!rows.length) return;
    const stmts = [];
    let done = false;
    for (const [id, rel] of rows) {
      last = id;
      budget.left -= 1;
      seen += 1;
      const abs = path.resolve(root, String(rel).split('/').join(path.sep));
      let hash = null;
      try {
        hash = oshashFile(abs, o.hashBytes);
      } catch (e) {
        stats.missing += 1;
        log('  MISSING ' + rel + '（' + (e.code || e.message) + '）');
      }
      if (hash) {
        stats[key] += 1;
        stmts.push('UPDATE ' + table + ' SET content_hash = ' + sqlStr(hash) + ' WHERE id = ' + sqlStr(id) + ';');
      }
      if (seen % 200 === 0) {
        log('  回填进度 ' + seen + ' 行（videos=' + stats.videos + ' photos=' + stats.photos +
          ' missing=' + stats.missing + '）');
      }
      if (budget.left <= 0) { done = true; break; }
    }
    if (stmts.length && !o.dryRun) {
      const r = runMysql(o, mysqlExe, password, 'SET NAMES utf8mb4;\n' + stmts.join('\n'));
      if (r.status !== 0) throw new Error('回填失败（' + table + '）：\n' + (r.stderr || '').trim());
    }
    if (done) return;
  }
}

/** 老行 dir_key 为空：按该册 min(rel_path) 的目录补齐。用 CHAR_LENGTH 做多字节安全的字符级减法 */
function backfillAlbumDirKey(o, mysqlExe, password) {
  const sql = [
    'SET NAMES utf8mb4;',
    'UPDATE media_album a',
    '  JOIN (SELECT album_id AS aid, MIN(rel_path) AS rp FROM media_photo GROUP BY album_id) m ON m.aid = a.id',
    "   SET a.dir_key = IF(CHAR_LENGTH(m.rp) - CHAR_LENGTH(SUBSTRING_INDEX(m.rp, '/', -1)) = CHAR_LENGTH(m.rp), '',",
    "                         LEFT(m.rp, CHAR_LENGTH(m.rp) - CHAR_LENGTH(SUBSTRING_INDEX(m.rp, '/', -1)) - 1))",
    ' WHERE a.dir_key IS NULL;',
    'SELECT ROW_COUNT();',
  ].join('\n');
  if (o.dryRun) return null;
  const rows = queryRows(o, mysqlExe, password, sql);
  const n = rows.length && rows[rows.length - 1].length ? Number(rows[rows.length - 1][0]) : 0;
  return Number.isFinite(n) ? n : 0;
}

function runBackfill(o, root) {
  const mysqlExe = resolveExe(o.mysql, 'mysql', 'mysql 客户端');
  const password = readPassword(o);
  const stats = { videos: 0, photos: 0, missing: 0 };
  const budget = { left: o.limit === null ? Number.MAX_SAFE_INTEGER : o.limit };
  log('== 回填 content_hash：root=' + root + ' hash-bytes=' + o.hashBytes +
    (o.limit !== null ? ' limit=' + o.limit : '') + (o.dryRun ? '（dry-run：不写库）' : ''));
  backfillTable(o, root, mysqlExe, password, 'media_video', budget, stats);
  backfillTable(o, root, mysqlExe, password, 'media_photo', budget, stats);
  const fixed = backfillAlbumDirKey(o, mysqlExe, password);
  if (fixed !== null) log('  相册 dir_key 补齐 ' + fixed + ' 行');
  log('回填 videos=' + stats.videos + ' photos=' + stats.photos + ' missing=' + stats.missing);
  return 0;
}

// ---------------------------------------------------------------- 主流程

function statsLine(stats) {
  return '新插入=' + stats.insert + ' 移动=' + stats.move + ' 更新=' + stats.update +
    ' 重复=' + stats.dup + ' 跳过=' + stats.skip + ' 别名=' + stats.alias;
}

function printDups(dupList) {
  for (const d of dupList) log('DUP ' + d.hash + ' 保留=' + d.keep + ' 忽略=' + d.drop);
}

function printAliases(aliasList) {
  for (const a of aliasList) {
    log('ALIAS ' + a.hash + ' 主表=' + a.table + ' 别名=' + a.rel +
      (a.rootId === null ? '' : ' root-id=' + a.rootId));
  }
}

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

  if (o.backfillHash) return runBackfill(o, root);

  const needDb = !(o.dryRun || o.dumpSql);
  let mysqlExe = null;
  let password = null;
  let state = emptyState();
  if (needDb) {
    mysqlExe = resolveExe(o.mysql, 'mysql', 'mysql 客户端');
    password = readPassword(o);
    // --truncate 会清空这三张表：库内状态一律作废，按空库重新分配 aNNN（与旧行为一致）
    state = o.truncate ? emptyState() : loadState(o, mysqlExe, password);
    o.rootId = resolveRootId(o, mysqlExe, password);
    o.aliasEnabled = aliasTablesReady(o, mysqlExe, password);
  } else {
    // dry-run / dump-sql 不连库：按「别名表已建」生成 SQL（执行前请确认已 source media_dup_alias.sql）
    o.aliasEnabled = true;
  }

  const scan = scanDisk(root, o);
  const model = planImport(scan, state, o);
  const photoCount = model.albums.reduce((s, a) => s + a.photoCount, 0);

  log('videos=' + scan.videos.length + ' albums=' + model.albums.length +
    ' photos=' + photoCount + ' people=0');
  log('root=' + root + ' root-id=' + (o.rootId === null ? 'NULL' : o.rootId) +
    ' big-bytes=' + o.bigBytes + ' album-mode=' + o.albumMode +
    ' hash=' + (o.noHash ? 'off' : o.hashBytes + 'B') +
    ' exclude=' + o.exclude.join(',') + (o.truncate ? ' truncate=1' : ''));
  for (const a of model.albums.slice(0, 20)) {
    log('  album ' + a.id + ' dir="' + a.dirKey + '" photos=' + a.photoCount +
      ' bytes=' + a.bytes + ' title="' + a.title + '"' + (a.reused ? ' (复用)' : ' (新建)'));
  }
  if (model.albums.length > 20) log('  …还有 ' + (model.albums.length - 20) + ' 本相册');
  for (const w of scan.warnings) warn(w);

  if (o.dryRun) {
    log('== dry-run：未写库（未读库内状态，相册/视频一律按新建处理）');
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
  if (o.dryRun || o.dumpSql) {
    log(statsLine(model.stats));
    printDups(model.dupList);
    printAliases(model.aliasList);
    return 0;
  }

  const sql = buildSql(model, o);
  const r = runMysql(o, mysqlExe, password, sql);
  if (r.status !== 0) {
    throw new Error('导入失败（mysql 退出码 ' + r.status + '）：\n' + (r.stderr || '').trim() +
      '\n提示：SQL 已在内存生成，未落盘；修好数据后用 --dry-run 复核。');
  }
  log('== 写库完成 ' + o.db + '@' + o.host + ':' + o.port + '（' + scan.videos.length + ' 视频 / ' +
    model.albums.length + ' 相册 / ' + photoCount + ' 图）');
  log(queryCounts(o, mysqlExe, password));
  log(statsLine(model.stats));
  log('相册 复用=' + model.albums.filter((a) => a.reused).length +
    ' 新建=' + model.albums.filter((a) => !a.reused).length);
  printDups(model.dupList);
  printAliases(model.aliasList);
  log('== 下一步：node build-thumbs.js --root=' + root + ' --thumbs=<缩略图根>');
  return 0;
}

try {
  process.exitCode = main();
} catch (e) {
  process.stderr.write('ERROR ' + (e && e.message ? e.message : String(e)) + '\n');
  process.exitCode = 1;
}
