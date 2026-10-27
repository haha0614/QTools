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
 *         poster_path = thumbs/v/<content_hash>.jpg（文件由 build-thumbs.js P4 按内容生成）；
 *         无 content_hash 时回退 thumbs/v/<id>.jpg（老命名，build-thumbs 同样会回退）
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
 *         唯一性保证：相册身份只有 (dir_key,title) 一个键。换了 --album-mode 或改了标题推导，旧行既不会
 *         被复用也不会被删——P5.43 起改为「不许静默并存」：本次没被任何分组认领、且册内 0 图、dir_key
 *         又落在本次产物里的旧行，默认直接报错退出（不写库），加 --purge-unclaimed 才在同一事务里删掉；
 *         存量清理见 doc/media_album_dedupe.sql（先预览再删、幂等）
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

/**
 * 「缺失行」在册内序号上的停泊偏移
 *
 * <p>册内序号 {@code (album_id, seq)} 有唯一键。磁盘上某张图消失后，它在册内的序号槽位要腾给
 * 后面的图，但行本身必须留着（missing=1，管理端可清理）——所以把它整体挪到
 * {@code seq + PARK_BASE}，既保住 album_id（还能按相册看到它），又不会和真实序号撞键。
 * 下一次扫描该文件复活时，planPhotos 会把它当「移动」按磁盘序号写回去。</p>
 */
const PARK_BASE = 1000000;
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
  --mysql=<exe>       mysql 客户端：显式路径 > 环境变量 MS_MYSQL > PATH 里的 mysql
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
  --purge-unclaimed   把「本次没被任何分组认领 + 册内 0 图 + dir_key 在本次产物里」的旧相册行
                      连同其人物关联行一起删掉（同一个事务内）。不加这个开关时，一旦发现这类
                      旧行，脚本会直接报错退出、不写库——避免换模式/改标题推导后两套相册并存
                      （等价的手工清理见 doc/media_album_dedupe.sql，可先预览再删）
  --backfill-hash     给库里 content_hash IS NULL 的行补哈希（按 --root + rel_path 读文件），
                      顺带补齐 media_album.dir_key；文件不存在只计数不删行
  --backfill-album-mtime
                      用磁盘目录 mtime 回填 media_album.mtime（幂等）：遍历库内相册，
                      按 dir_key / dir 定位目录 stat 后 UPDATE；目录不存在只计数、不删行。
                      可配 --dry-run 只看命中。SQL 读不到磁盘，所以这一列只能由本脚本写
                      （真实扫描写库时也会顺手写：INSERT 带 mtime 列）
  --fix-poster-path   只更新 media_video.poster_path（不改其它列、不扫盘、不需要 --root）：
                      有 content_hash 的行刷成 thumbs/v/<content_hash>.jpg，
                      没有的行刷成 thumbs/v/<id>.jpg；可配 --dry-run / --dump-sql=<file>
                      注意顺序：先跑 build-thumbs.js（P4 起按 <content_hash>.jpg 落盘），
                      再执行本模式；封面文件还是 <id>.jpg 时刷完会指向不存在的文件
  --only-video        只扫视频：图片/相册一律不看（也不会把库里的图片标成缺失）
  --only-photo        只扫图片/相册：视频一律不看（也不会把库里的视频标成缺失）
  --limit=<n>         本次最多写多少个视频 / 多少张图片（默认不限）；--backfill-hash 时是最多处理多少行
  --prune-missing     扫完把 media_photo / media_video 里 missing=1 的行真删（默认只标记不删）；
                      删除前先打印 TOP 10 与总数，必须同时给 --yes 才真删
  --help              显示本帮助

查找 mysql 客户端（优先级）：
  1) --mysql=<exe> 显式路径（不存在直接报错，不回退）
  2) 环境变量 MS_MYSQL（为空视为未设置；指向的文件不存在直接报错，不回退）
  3) PATH 里的 mysql

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

 相册 mtime（P5.59；SQL 只能给近似值，这里写磁盘真值，幂等可反复跑）：
  node scan-media.js --root=D:\\我的媒体 --backfill-album-mtime [--dry-run]
  node scan-media.js --root=D:\\我的媒体 --backfill-album-mtime --secret=D:\\cfg\\secret.yml

输出（管理端 MediaScanServiceImpl 按这些行解析，改动前先看它）：
  videos=<n> albums=<n> photos=<n> people=0            扫描到的源数据条数
  条数 videos=<n> albums=<n> photos=<n> people=<n>    写库后回读的库内条数
  新插入=<n> 移动=<n> 更新=<n> 重复=<n> 跳过=<n> 别名=<n> missing=<n> revived=<n>
                                                       本次写的分类计数；missing=扫完仍缺失的行数，
                                                       revived=本次「磁盘又有了」复活的行数
  ALIAS <hash> 主=<path> 别名=<path>                   同内容副本：主文件留在主表，这条进别名表
  DUP <hash> 保留=<path> 忽略=<path>                   同上，但别名表未建时退回老行为（副本被丢弃）
  回填 videos=<n> photos=<n> missing=<n>               --backfill-hash 的计数
  回填 album-mtime 更新=<n> 相同=<n> 目录不存在=<n>     --backfill-album-mtime 的计数

缺失文件（doc/media_missing.sql 加的 missing 列）：
  每次真实扫描收尾都会比对「本次看到的 rel_path」与库内行：
    磁盘上没了 -> missing=1（last_seen 保留最后一次看到的时间，不刷新）
    磁盘上又有 -> missing=0（复活）+ last_seen=当前时间
  默认只标记不删；幽灵行确需清掉时：
    node scan-media.js --root=D:\\媒体 [--prune-missing [--yes]]
  没建 missing 列的老库只 WARN，不会让扫描失败。
`;

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS, {
    root: null, mysql: null, secret: null, dumpSql: null, truncate: false, yes: false, dryRun: false,
    ignored: [], noHash: false, backfillHash: false, fixPosterPath: false, limit: null, rootId: null,
    onlyVideo: false, onlyPhoto: false,
    pruneMissing: false, purgeUnclaimed: false, backfillAlbumMtime: false,
  });
  for (const a of argv) {
    if (a === '--truncate') o.truncate = true;
    else if (a === '--yes' || a === '-y') o.yes = true;
    else if (a === '--dry-run') o.dryRun = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a === '--no-hash') o.noHash = true;
    else if (a === '--backfill-hash') o.backfillHash = true;
    else if (a === '--backfill-album-mtime') o.backfillAlbumMtime = true;
    else if (a === '--fix-poster-path') o.fixPosterPath = true;
    else if (a === '--prune-missing') o.pruneMissing = true;
    else if (a === '--purge-unclaimed') o.purgeUnclaimed = true;
    else if (a === '--only-video') o.onlyVideo = true;
    else if (a === '--only-photo') o.onlyPhoto = true;
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
  if (o.onlyVideo && o.onlyPhoto) {
    throw new Error('--only-video 与 --only-photo 只能二选一（两个都不给 = 视频 + 图片一起扫）');
  }
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

/**
 * 视频封面路径：有 content_hash 就按内容寻址，否则回退 videoId
 *
 * <p>与 build-thumbs.js（P4）的落盘命名一一对应：<thumbs>/v/<content_hash>.jpg，
 * content_hash 为 NULL 时才回退 <thumbs>/v/<videoId>.jpg。两处必须一致，
 * 否则后端/前端按 poster_path 去找就是 404。</p>
 */
function posterPathOf(id, hash) {
  const key = hash === null || hash === undefined ? '' : String(hash).trim();
  return 'thumbs/v/' + (key === '' ? String(id) : key) + '.jpg';
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

/** 本地时区的 YYYY-MM-DD HH:MM:SS：与 mysql FROM_UNIXTIME() 的服务器时区口径对齐，便于人工比对 */
function stampLocal(sec) {
  const d = new Date(sec * 1000);
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' +
    p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
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

/** 显式路径 > 环境变量 > PATH（措辞与顺序同 build-thumbs.js 的 resolveTool） */
function resolveExe(explicit, envName, name, label) {
  if (explicit) {
    if (!fs.existsSync(explicit)) throw new Error(label + ' 不存在：' + explicit);
    return explicit;
  }
  const fromEnv = process.env[envName]; // 空字符串视为未设置
  if (fromEnv) {
    if (!fs.existsSync(fromEnv)) throw new Error(envName + ' 指向的文件不存在：' + fromEnv);
    return fromEnv;
  }
  const found = findInPath(name);
  if (!found) {
    throw new Error('PATH 里找不到 ' + name + '，请用 --' + name + '=<exe> 显式指定，' +
      '或设置环境变量 ' + envName);
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
    // 目录自身 mtime：media_album.mtime 的唯一来源（SQL 读不到磁盘，只有这里能 stat 到）
    try {
      sink.dirMtime.set(cur.rel, fs.statSync(cur.abs).mtimeMs);
    } catch (e) {
      sink.errors.push(cur.abs + '：stat ' + e.message);
    }
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
  const sink = { files: [], errors: [], skippedLinks: 0, dirMtime: new Map() };
  walk(root, o.excludeSet, sink);

  // 本次扫描「在磁盘上看到过」的 rel_path 集合：missing 同步的唯一依据。
  // 在长度 / stat / 哈希等跳过判定**之前**就登记——这些文件明明还在磁盘上，
  // 绝不能因为「本次没写进库」就被标成缺失。
  const present = { photo: new Set(), video: new Set() };

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
    // 只扫一类时，被排除的那一类连 present 都不登记：missing 同步只处理本次真的看过的那张表
    if (isVideo ? o.onlyPhoto : o.onlyVideo) continue;
    if (isVideo) present.video.add(f.rel);
    else present.photo.add(f.rel);
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
      g = { key: gkey, dirKey, prefix: m ? m[1] : null, titleGuess: null, dirMtimeMs: null, photos: [] };
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
    // 同一目录的多本册（--album-mode=prefix）共用同一个目录 mtime，与磁盘事实一致
    g.dirMtimeMs = sink.dirMtime.has(g.dirKey) ? sink.dirMtime.get(g.dirKey) : null;
    g.photos.sort((a, b) => naturalCompare(a.name, b.name));
    g.titleGuess = g.prefix ? prefixTitle(g.prefix, g.photos[0].base) : dirTitleOf(g.dirKey, rootTitle);
  }
  // 前缀分册只在「目录里真的分成多册」时才有意义：某个目录本次只分出一册时，标题直接用
  // 目录名——否则像 1 (1).jpg / 1 (2).jpg 这种文件名会把整本册命名成 "1 (1)"，
  // 而它就是该目录唯一的内容，标题完全是噪音。同时让 dir / prefix 两种模式对这类目录
  // 产出同一个标题，切模式不再造出两套相册行。
  const groupsPerDir = new Map();
  for (const g of list) groupsPerDir.set(g.dirKey, (groupsPerDir.get(g.dirKey) || 0) + 1);
  for (const g of list) {
    if (g.prefix && groupsPerDir.get(g.dirKey) === 1) g.titleGuess = dirTitleOf(g.dirKey, rootTitle);
  }
  list.sort((a, b) => naturalCompare(a.dirKey, b.dirKey) ||
    naturalCompare(a.prefix === null ? '' : '#' + a.prefix, b.prefix === null ? '' : '#' + b.prefix));

  if (longPath) warnings.push(longPath + ' 个文件 rel_path 超过 ' + MAX_REL + ' 字符，已跳过');
  for (const e of sink.errors) warnings.push('目录读取失败：' + e);
  if (sink.skippedLinks) warnings.push(sink.skippedLinks + ' 个符号链接/联接点已跳过（不跟随）');

  videos.sort((a, b) => naturalCompare(a.rel, b.rel));

  // --limit：最多写前 n 个视频 / 前 n 张图片。present 集合在上面的循环里已经按**整盘**登记过，
  // 截断只影响「本次写不写」，不会把没写的行误标成缺失（缺失判定只看磁盘上还在不在）。
  if (o.limit !== null) {
    if (videos.length > o.limit) videos.length = o.limit;
    let left = o.limit;
    const kept = [];
    for (const g of list) {
      if (left <= 0) break;
      g.photos = g.photos.slice(0, left);
      left -= g.photos.length;
      kept.push(g);
    }
    list.length = 0;
    for (const g of kept) list.push(g);
    warnings.push('--limit=' + o.limit + '：本次最多写 ' + o.limit + ' 个视频 / ' + o.limit + ' 张图片' +
      '（其余文件仍按「磁盘上还在」登记，不会被标成缺失）');
  }
  return { videos, groups: list, warnings, skipped, rootTitle, present };
}

// ---------------------------------------------------------------- 库内状态

function emptyState() {
  return {
    videoById: new Map(),
    videoByRel: new Map(),
    videoByHash: new Map(),
    photoByHash: new Map(),
    photoByAlbum: new Map(),
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
    "SELECT id, rel_path, IFNULL(content_hash, ''), IFNULL(root_id, 0) FROM media_video;");
  for (const [id, rel, hash, rootId] of vRows) {
    const row = { id, rel, hash, rootId: Number(rootId) || null };
    s.videoById.set(id, row);
    s.videoByRel.set(rel, id);
    if (hash) s.videoByHash.set(hash, row);
  }

  // 图片行：hash 索引（认「同一份内容」）+ album 索引（判断册内哪些行已消失、要腾序号槽位）。
  // 这里不再用 WHERE content_hash IS NOT NULL 过滤：没有哈希的老行同样需要参与
  // 「消失 / 停泊」判断，否则它们会一直霸着 (album_id, seq) 把新图挡掉。
  const pRows = queryRows(o, mysqlExe, password,
    'SELECT id, album_id, seq, rel_path, IFNULL(content_hash, \'\'), IFNULL(root_id, 0) FROM media_photo;');
  for (const [id, albumId, seq, rel, hash, rootId] of pRows) {
    const row = {
      id, albumId, seq: Number(seq), rel, hash: hash || '',
      rootId: Number(rootId) || null,
    };
    const byAlbum = s.photoByAlbum.get(albumId) || [];
    byAlbum.push(row);
    s.photoByAlbum.set(albumId, byAlbum);
    if (!row.hash) continue;
    const list = s.photoByHash.get(row.hash) || [];
    list.push(row);
    s.photoByHash.set(row.hash, list);
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

/**
 * 旧行对应的文件现在还在不在磁盘上
 *
 * <p>同一个 content_hash 命中、rel_path 却不同时，只有这一条能区分两种截然不同的事实：</p>
 * <ul>
 *   <li>旧文件<b>还在</b> → 同内容的两份真实副本（复制出来的那份）；</li>
 *   <li>旧文件<b>没了</b> → 改名 / 移动，库里的行应该跟着走，不能再多出一行。</li>
 * </ul>
 *
 * <p>跨库根的旧行一律当「不在」处理：别的根没参与本次遍历，用本根的视角判断它会误判。</p>
 */
function stillOnDisk(prior, presentVideo, o) {
  if (o.rootId !== null && prior.rootId !== null && prior.rootId !== o.rootId) return false;
  return presentVideo.has(prior.rel);
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
    if (hit && hit.rel !== v.rel && stillOnDisk(hit, ctx.present.video, ctx.o)) {
      // 同一份内容在两处真实存在：media_video.content_hash 有唯一键，第二份只能进别名表
      // （主表仍留在先入库的那条路径上，与「别名只在同一次扫描内记录」的旧行为一致）
      recordAlias(ctx, 'media_video_alias', v.hash, hit.rel, v.rel, v.size);
      continue;
    }
    if (hit && hit.rel !== v.rel) {
      // 命中内容哈希、旧文件已不在磁盘上：改名/移动，id 保持不变
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

/**
 * 相册内图片计划：认领旧行（更新/移动）、停泊已消失的行、判定同内容副本
 *
 * <p>三条规则，顺序不能换：</p>
 * <ol>
 *   <li><b>同路径优先</b>：hash 命中且旧行 rel_path 与磁盘一致 → 认领它（可能只是 seq 变了）；</li>
 *   <li><b>旧文件没了 → 移动</b>：hash 命中但旧行路径不在本次扫描集合里，说明文件被改名/移动，
 *       把那一行改到新路径与序号上（id 不变、不新增行、不留幽灵行）；</li>
 *   <li><b>旧文件还在 → 副本</b>：同相册两张不同路径的同内容图，主表各占一行（相册张数与磁盘
 *       一致），另把这条路径写进 media_photo_alias，管理端将来能按别名做去重。</li>
 * </ol>
 *
 * <p>「停泊」（parks）：库里在本册、但本次扫描没在磁盘上找到的行，一律挪到
 * {@code seq + PARK_BASE}。不挪的话 {@code (album_id, seq)} 唯一键会被残留行占着——
 * 那正是 a006 出现「幽灵行」+ 新图插不进去的机制。行本身留着（missing=1），
 * 由收尾的 missing 同步打标记，{@code --prune-missing --yes} 才真删。</p>
 */
function planPhotos(albums, state, ctx) {
  const stats = ctx.stats;
  const rows = [];
  const moves = [];
  const parks = [];
  const claimed = new Set();
  // 册内同内容副本：只在「同一本相册」里才算重复（同一张图出现在不同相册是合法的，
  // 那是内容复用而不是重复文件）；主表照旧各占一行，册内张数与磁盘保持一致
  const seenInAlbum = new Map();
  for (const a of albums) {
    for (const r of (state.photoByAlbum.get(a.id) || [])) {
      if (r.seq >= PARK_BASE) continue;   // 上一次已经停泊过，别再往上加
      if (ctx.present.photo.has(r.rel)) continue; // 文件还在：等它自己的那张图来认领
      parks.push(r);
    }
    a.photos.forEach((p, seq) => {
      p.seq = seq;
      const key = a.id + '\u0000' + (p.hash || '');
      let prior = null;
      let aliasKeep = null;
      if (p.hash) {
        const cands = (state.photoByHash.get(p.hash) || []).filter(
          (r) => !claimed.has(r.id) &&
            (ctx.o.rootId === null || r.rootId === null || r.rootId === ctx.o.rootId));
        prior = cands.find((r) => r.rel === p.rel) || null;
        if (!prior) {
          const gone = cands.find((r) => !ctx.present.photo.has(r.rel));
          if (gone) prior = gone;
          else if (seenInAlbum.has(key)) aliasKeep = seenInAlbum.get(key);
          else {
            const same = cands.find((r) => r.albumId === a.id);
            if (same) aliasKeep = same.rel;
          }
        }
      }
      if (!seenInAlbum.has(key)) seenInAlbum.set(key, p.rel);
      if (!prior) {
        // 新增行：只有「同册同内容、两份都在磁盘上」才写别名（跨册同内容是合法复用，不算重复）
        if (aliasKeep) recordAlias(ctx, 'media_photo_alias', p.hash, aliasKeep, p.rel, p.bytes);
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
  return { rows, moves, parks };
}

function planImport(scan, state, o) {
  const stats = { insert: 0, move: 0, update: 0, dup: 0, skip: scan.skipped, alias: 0 };
  const dupList = [];
  const present = scan.present || { photo: new Set(), video: new Set() };
  const ctx = { list: [], dupList, stats, o, present };
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
      // P5.59：所在目录的磁盘 mtime（stat 失败为 null，写库时退化为不动该列）
      dirMtimeMs: g.dirMtimeMs,
    });
  }

  const videoPlan = planVideos(scan.videos, state, ctx);
  const photoPlan = planPhotos(albums, state, ctx);

  // 本次「没被任何分组认领」的旧行：册内 0 图 + dir_key 落在本次产物里。
  // 这就是换 --album-mode / 改标题推导后留下的空壳——老版本既不复用也不删，
  // 于是同一套内容并存两条（一条有图、一条永远打不开），photo_count 还停在旧值。
  const producedDirs = new Set(albums.map((a) => a.dirKey));
  const staleAlbums = [];
  for (const a of state.albumById.values()) {
    if (claimed.has(a.id) || a.dirKeyNull || !producedDirs.has(a.dirKey)) continue;
    const live = (state.photoByAlbum.get(a.id) || []).filter((r) => r.seq < PARK_BASE).length;
    if (live > 0) continue;
    staleAlbums.push({ id: a.id, dirKey: a.dirKey, title: a.title });
  }

  return {
    albums,
    staleAlbums,
    videoRows: videoPlan.rows,
    movedVideos: videoPlan.moves,
    photoRows: photoPlan.rows,
    movedPhotos: photoPlan.moves,
    parkedPhotos: photoPlan.parks,
    aliasList: ctx.list,
    dupList,
    stats,
  };
}

// ---------------------------------------------------------------- 生成 SQL

/**
 * 缺失标记两列（missing / last_seen）的名字片段：老库还没执行 doc/media_missing.sql 时
 * 一律退化成空串，生成的 SQL 与改动前逐字相同，不会因为缺列让整次扫描失败。
 */
function missingCols(o, withValues) {
  if (!o.missingEnabled) return '';
  return withValues ? ',last_seen,missing' : '';
}

function missingValues(o, withValues) {
  if (!o.missingEnabled) return '';
  return withValues ? ',NOW(),0' : '';
}

/** ON DUPLICATE KEY UPDATE 里「磁盘上又看到它了」的两列 */
function missingTouch(o) {
  return o.missingEnabled ? ',missing=0,last_seen=NOW()' : '';
}

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
  // 1-b) 已消失的行先停泊出去：册内序号槽位要腾给真实文件，行留着（收尾会打 missing=1）
  if (model.parkedPhotos && model.parkedPhotos.length) {
    for (const part of chunks(model.parkedPhotos.map((r) => sqlInt(r.id)), 500)) {
      lines.push('UPDATE media_photo SET seq = seq + ' + PARK_BASE + ' WHERE id IN (' + part.join(',') + ');');
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
    'NULL', 'NULL', 'NULL', 'NULL', sqlInt(v.browserOk), sqlStr(posterPathOf(v.id, v.hash)), '0',
    sqlDateTime(v.mtimeMs), sqlStr(v.hash), sqlRoot(o.rootId) + missingValues(o, true),
  ].join(',') + ')');
  for (const part of chunks(videoRows, o.batch)) {
    lines.push('INSERT INTO media_video (id,folder,rel_path,title,size,dur,w,h,vcodec,browser_ok,poster_path,hidden,mtime,content_hash,root_id' +
      missingCols(o, true) + ') VALUES ' +
      part.join(',') +
      // dur/w/h/vcodec 不覆盖：本脚本不跑 ffprobe，别把别的工具填好的元数据清空
      ' AS n ON DUPLICATE KEY UPDATE folder=n.folder,rel_path=n.rel_path,title=n.title,' +
      'size=n.size,browser_ok=n.browser_ok,poster_path=n.poster_path,mtime=n.mtime,' +
      'content_hash=n.content_hash,root_id=n.root_id' + missingTouch(o) + ';');
  }

  // 3) 视频改名/移动：UPDATE 同一行，id 保持不变
  for (const v of model.movedVideos) {
    lines.push('UPDATE media_video SET rel_path=' + sqlStr(v.rel) +
      ',folder=' + sqlStr(v.folder) + ',title=' + sqlStr(v.title) +
      ',size=' + sqlInt(v.size) + ',browser_ok=' + sqlInt(v.browserOk) +
      ',poster_path=' + sqlStr(posterPathOf(v.id, v.hash)) +
      ',mtime=' + sqlDateTime(v.mtimeMs) + ',content_hash=' + sqlStr(v.hash) +
      ',root_id=' + sqlRoot(o.rootId) + (o.missingEnabled ? ',missing=0,last_seen=NOW()' : '') +
      ' WHERE id=' + sqlStr(v.id) + ';');
  }

  // 4) 相册（id 已按 (dir_key,title) 复用/新分配）
  //    P5.59：mtime = 所在目录的磁盘 mtime。老库没有该列时整段降级（SQL 逐字同改动前）。
  const mtimeCol = o.albumMtimeEnabled ? ',mtime' : '';
  const mtimeTouch = o.albumMtimeEnabled ? ',mtime=IFNULL(n.mtime,media_album.mtime)' : '';
  const albumRows = model.albums.map((a) => '(' + [
    sqlStr(a.id), 'NULL', sqlStr(a.title), sqlStr(a.dirKey), sqlInt(a.photoCount),
    sqlInt(a.bytes), sqlStr(a.cover), "''", sqlInt(a.sort), '0', sqlStr(a.dirKey),
    ...(o.albumMtimeEnabled ? [sqlDateTime(a.dirMtimeMs)] : []),
  ].join(',') + ')');
  for (const part of chunks(albumRows, o.batch)) {
    lines.push('INSERT INTO media_album (id,person_id,title,dir,photo_count,bytes,cover_path,note,sort,hidden,dir_key' +
      mtimeCol + ') VALUES ' +
      part.join(',') +
      // person_id / note / hidden 不覆盖：管理端的人工归类不能被重扫冲掉
      // mtime 用 IFNULL：本次 stat 不到目录时保留库里的已有值，绝不用 NULL 抹掉
      ' AS n ON DUPLICATE KEY UPDATE title=n.title,dir=n.dir,photo_count=n.photo_count,' +
      'bytes=n.bytes,cover_path=n.cover_path,sort=n.sort,dir_key=n.dir_key' + mtimeTouch + ';');
  }

  // 5) 图片 upsert（content_hash 一并写入）
  const photoRows = model.photoRows.map((r) => '(' + [
    sqlStr(r.albumId), sqlInt(r.seq), sqlStr(r.photo.rel), sqlInt(r.photo.bytes), 'NULL', 'NULL',
    sqlInt(r.photo.isBig), '0', sqlStr(r.photo.hash), sqlRoot(o.rootId) + missingValues(o, true),
  ].join(',') + ')');
  for (const part of chunks(photoRows, o.batch)) {
    lines.push('INSERT INTO media_photo (album_id,seq,rel_path,bytes,w,h,is_big,hidden,content_hash,root_id' +
      missingCols(o, true) + ') VALUES ' + part.join(',') +
      // w/h 不覆盖：本脚本不探测图片尺寸，别把别的工具填好的值清空
      ' AS n ON DUPLICATE KEY UPDATE rel_path=n.rel_path,bytes=n.bytes,is_big=n.is_big,' +
      'content_hash=n.content_hash,root_id=n.root_id' + missingTouch(o) + ';');
  }

  // 6) 图片改名/移动：UPDATE 同一行，并按新目录更新 album_id/seq
  for (const m of model.movedPhotos) {
    lines.push('UPDATE media_photo SET album_id=' + sqlStr(m.albumId) + ',seq=' + sqlInt(m.seq) +
      ',rel_path=' + sqlStr(m.photo.rel) + ',bytes=' + sqlInt(m.photo.bytes) +
      ',is_big=' + sqlInt(m.photo.isBig) + ',content_hash=' + sqlStr(m.photo.hash) +
      ',root_id=' + sqlRoot(o.rootId) + (o.missingEnabled ? ',missing=0,last_seen=NOW()' : '') +
      ' WHERE id=' + sqlInt(m.photo.id) + ';');
  }

  // 7) 目录里删过图：把每本相册 seq >= 当前张数 的历史残留清掉，否则相册会越重扫越多。
  //    seq >= PARK_BASE 的是已停泊的缺失行（要留着给管理端清理），绝不能在这里删掉。
  for (const a of model.albums) {
    lines.push('DELETE FROM media_photo WHERE album_id = ' + sqlStr(a.id) +
      ' AND seq >= ' + sqlInt(a.photoCount) + ' AND seq < ' + PARK_BASE + ';');
  }
  // 孤儿图（相册已被删掉）：保证「每张图都能找到相册」恒成立
  lines.push('DELETE p FROM media_photo p LEFT JOIN media_album a ON a.id = p.album_id WHERE a.id IS NULL;');

  // 7-b) 本次没被认领的空壳旧行：不给 --purge-unclaimed 就只把计数归零（行留着，页头数字
  //      至少不再翻倍），给了就真删——连同人物关联行，避免留下指向已删相册的孤儿行
  for (const s of model.staleAlbums) {
    if (o.purgeUnclaimed) {
      if (o.albumPersonEnabled) {
        lines.push('DELETE FROM media_album_person WHERE album_id = ' + sqlStr(s.id) + ';');
      }
      lines.push('DELETE FROM media_album WHERE id = ' + sqlStr(s.id) + ';');
    } else {
      lines.push('UPDATE media_album SET photo_count = 0, bytes = 0 WHERE id = ' + sqlStr(s.id) +
        ' AND (photo_count <> 0 OR bytes <> 0);');
    }
  }

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
  const mysqlExe = resolveExe(o.mysql, 'MS_MYSQL', 'mysql', 'mysql 客户端');
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

// ---------------------------------------------------------------- 回填相册 mtime（--backfill-album-mtime）

/** media_album.mtime 列（老库可能还没建）：没有就整段降级，绝不因为缺列让扫描/回填失败 */
function albumMtimeReady(o, mysqlExe, password) {
  const rows = queryRows(o, mysqlExe, password,
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() " +
    "AND table_name = 'media_album' AND column_name = 'mtime';");
  const n = rows.length && rows[0].length ? Number(rows[0][0]) : 0;
  if (n === 1) return true;
  warn('media_album 还没有 mtime 列（先执行 doc/media_album_mtime.sql）：本次不写相册 mtime');
  return false;
}

/**
 * 相册目录的候选根：--root 优先，其后是 media_root 里其它 kind='root' 的根
 * （多库根时 media_album 不带 root_id，只能按「哪个根下真有这个目录」认）
 */
function albumRoots(o, root, mysqlExe, password) {
  const roots = [root];
  try {
    const rows = queryRows(o, mysqlExe, password,
      "SELECT IFNULL(path, '') FROM media_root WHERE kind = 'root' ORDER BY id;");
    for (const [p] of rows) {
      if (!p) continue;
      const abs = path.resolve(p);
      if (!roots.some((r) => r.toLowerCase() === abs.toLowerCase())) roots.push(abs);
    }
  } catch (e) {
    warn('读 media_root 失败（按单根回填）：' + (e.message || e));
  }
  return roots;
}

/** dir_key（相对媒体根、/ 分隔）-> 命中的绝对目录；越界或不存在返回 null */
function locateAlbumDir(roots, rel) {
  const parts = rel === '' ? [] : String(rel).split('/');
  if (parts.some((p) => p === '' || p === '.' || p === '..')) return null;
  for (const r of roots) {
    const abs = parts.length ? path.resolve(r, ...parts) : r;
    const back = path.relative(r, abs);
    if (back.startsWith('..') || path.isAbsolute(back)) continue; // 防越界：必须还在根里
    try {
      if (fs.statSync(abs).isDirectory()) return abs;
    } catch (e) {
      // 该根下没有，试下一个
    }
  }
  return null;
}

/**
 * 用磁盘目录 mtime 回填 media_album.mtime（幂等）
 *
 * <p>这一列 SQL 补不全：SQL 读不到磁盘。真实扫描写库时已经顺手写了（buildSql 的 mtime 列），
 * 本模式补的是「历史行 + 本次没扫到的根」。同一目录多本册（--album-mode=prefix）拿到同一个值，
 * 与磁盘事实一致。</p>
 *
 * <p>幂等：与磁盘秒数相等的行不发 UPDATE，连跑第二遍 更新=0；目录不存在只计数、不删行。</p>
 */
function runBackfillAlbumMtime(o, root) {
  const mysqlExe = resolveExe(o.mysql, 'MS_MYSQL', 'mysql', 'mysql 客户端');
  const password = readPassword(o);
  if (!albumMtimeReady(o, mysqlExe, password)) return 1;

  const rows = queryRows(o, mysqlExe, password,
    "SELECT id, IFNULL(dir_key, ''), IFNULL(dir, ''), IFNULL(UNIX_TIMESTAMP(mtime), 0) FROM media_album ORDER BY id;");
  const roots = albumRoots(o, root, mysqlExe, password);
  const stats = { total: rows.length, updated: 0, same: 0, noDir: 0 };
  log('== 回填相册 mtime：root=' + root + ' 相册=' + rows.length +
    ' 候选根=' + roots.length + (o.dryRun ? '（dry-run：不写库）' : ''));

  const stmts = [];
  const samples = [];
  for (const [id, dirKey, dir, curSec] of rows) {
    const rel = (dirKey || dir || '').trim();
    const abs = locateAlbumDir(roots, rel);
    if (!abs) {
      stats.noDir += 1;
      if (stats.noDir <= 10) log('  SKIP ' + id + ' dir="' + rel + '"（目录不存在）');
      continue;
    }
    let st;
    try {
      st = fs.statSync(abs);
    } catch (e) {
      stats.noDir += 1;
      if (stats.noDir <= 10) log('  SKIP ' + id + ' dir="' + rel + '"（' + (e.code || e.message) + '）');
      continue;
    }
    const sec = Math.floor(st.mtimeMs / 1000);
    // 比秒：FROM_UNIXTIME(sec) 与 UNIX_TIMESTAMP(mtime) 在同一时区上闭环，不受本地时区影响
    if (String(sec) === String(curSec)) {
      stats.same += 1;
      continue;
    }
    stats.updated += 1;
    samples.push([id, rel, stampLocal(sec)]);
    stmts.push('UPDATE media_album SET mtime = FROM_UNIXTIME(' + sqlInt(sec) + ') WHERE id = ' + sqlStr(id) + ';');
  }
  for (const s of samples.slice(0, 10)) log('  SET ' + s[0] + ' dir="' + s[1] + '" mtime=' + s[2]);
  if (samples.length > 10) log('  …还有 ' + (samples.length - 10) + ' 本');
  if (o.dryRun) {
    log('回填 album-mtime 更新=' + stats.updated + ' 相同=' + stats.same +
      ' 目录不存在=' + stats.noDir + '（dry-run 未写库）');
    return 0;
  }
  for (const part of chunks(stmts, o.batch)) {
    const r = runMysql(o, mysqlExe, password, 'SET NAMES utf8mb4;\n' + part.join('\n'));
    if (r.status !== 0) throw new Error('回填相册 mtime 失败：\n' + (r.stderr || '').trim());
  }
  const after = queryRows(o, mysqlExe, password, 'SELECT COUNT(*), SUM(mtime IS NULL) FROM media_album;');
  const total = after.length && after[0].length ? Number(after[0][0]) : 0;
  const nulls = after.length && after[0].length > 1 ? Number(after[0][1] || 0) : 0;
  log('回填 album-mtime 更新=' + stats.updated + ' 相同=' + stats.same + ' 目录不存在=' + stats.noDir);
  log('相册 mtime 自检 total=' + total + ' 仍为空=' + nulls);
  return 0;
}

// ---------------------------------------------------------------- 修正 poster_path（--fix-poster-path）

/**
 * poster_path 补救 SQL：只 UPDATE media_video.poster_path，不动任何其它列
 *
 * <p>P4 起 build-thumbs 把封面按内容落盘成 <thumbs>/v/<content_hash>.jpg，而老库里的
 * poster_path 还是建库时写的 thumbs/v/<id>.jpg —— 谁按它去找封面谁就 404。
 * 这里把已有行刷成与现有文件一致的命名：有 hash 用 hash，没 hash 用 id
 * （= build-thumbs 的回退命名）。只更新已有行，不插入、不删除。</p>
 *
 * <p><b>执行顺序</b>：先 build-thumbs.js 把封面按 {@code <content_hash>.jpg} 落盘，再跑本模式。
 * 反过来的话，刷新的 poster_path 会指向还没生成的封面文件。</p>
 */
function buildPosterFixSql() {
  const byHash = "CONCAT('thumbs/v/', content_hash, '.jpg')";
  const byId = "CONCAT('thumbs/v/', id, '.jpg')";
  return [
    'SET NAMES utf8mb4;',
    'UPDATE media_video SET poster_path = ' + byHash +
      " WHERE content_hash IS NOT NULL AND content_hash <> ''" +
      ' AND (poster_path IS NULL OR poster_path <> ' + byHash + ');',
    'SELECT ROW_COUNT();',
    'UPDATE media_video SET poster_path = ' + byId +
      " WHERE (content_hash IS NULL OR content_hash = '')" +
      ' AND (poster_path IS NULL OR poster_path <> ' + byId + ');',
    'SELECT ROW_COUNT();',
  ].join('\n') + '\n';
}

/** 修正后的自检：total / 有 hash 且一致 / 无 hash 且一致 */
const POSTER_VERIFY_SQL = "SELECT COUNT(*)," +
  " SUM(content_hash IS NOT NULL AND content_hash <> '' AND poster_path = CONCAT('thumbs/v/', content_hash, '.jpg'))," +
  " SUM((content_hash IS NULL OR content_hash = '') AND poster_path = CONCAT('thumbs/v/', id, '.jpg'))" +
  ' FROM media_video;';

function runFixPosterPath(o) {
  const sql = buildPosterFixSql();
  const lines = sql.split('\n').filter(Boolean);
  if (o.dumpSql) {
    const file = path.resolve(o.dumpSql);
    fs.writeFileSync(file, sql, 'utf8');
    log('== poster_path 修正 SQL 已写入 ' + file + '（' + lines.length + ' 行），未执行');
    for (const line of lines) log('  | ' + line);
    log('== 执行：mysql -uroot -D ' + o.db + ' --default-character-set=utf8mb4 < ' + file);
    return 0;
  }
  if (o.dryRun) {
    log('== poster_path 修正 SQL（dry-run：不连库、不写库）');
    for (const line of lines) log('  | ' + line);
    return 0;
  }
  const mysqlExe = resolveExe(o.mysql, 'MS_MYSQL', 'mysql', 'mysql 客户端');
  const password = readPassword(o);
  log('== 修正 poster_path：' + o.db + '@' + o.host + ':' + o.port);
  const rows = queryRows(o, mysqlExe, password, sql);
  const fixedHash = rows.length > 0 ? Number(rows[0][0]) || 0 : 0;
  const fixedId = rows.length > 1 ? Number(rows[1][0]) || 0 : 0;
  const v = queryRows(o, mysqlExe, password, POSTER_VERIFY_SQL)[0] || [];
  const total = Number(v[0]) || 0;
  const ok = (Number(v[1]) || 0) + (Number(v[2]) || 0);
  log('poster 修正 hash=' + fixedHash + ' id=' + fixedId);
  log('poster 校验 total=' + total + ' 一致=' + ok + (ok === total ? '' : ' 不一致=' + (total - ok)));
  return 0;
}

// ---------------------------------------------------------------- 缺失文件（missing）

/**
 * missing / last_seen 两列是否就位（doc/media_missing.sql）；没建就只 WARN：
 * 老库照样能扫，只是不标记缺失、不执行 --prune-missing。
 */
function missingReady(o, mysqlExe, password) {
  const rows = queryRows(o, mysqlExe, password,
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND column_name = 'missing' " +
    "AND table_name IN ('media_photo', 'media_video');");
  const n = rows.length && rows[0].length ? Number(rows[0][0]) : 0;
  if (n === 2) return true;
  warn('media_photo / media_video 还没有 missing 列（先执行 doc/media_missing.sql）：' +
    '本次不标记缺失文件、--prune-missing 也不生效');
  return false;
}

/**
 * 本次扫描的「库根范围」条件
 *
 * <p>多库根下绝不能拿 A 根的磁盘视角去标 B 根的行：显式 --root-id 时只认该根；
 * 默认根（media_root 里 kind=root 的第一行）额外把 root_id IS NULL 的存量行算进来，
 * 与后端「NULL = 默认根」的约定一致。</p>
 */
function rootScope(o) {
  if (o.rootId === null) return 'root_id IS NULL';
  if (o.rootIdIsDefault) return '(root_id = ' + sqlInt(o.rootId) + ' OR root_id IS NULL)';
  return 'root_id = ' + sqlInt(o.rootId);
}

/** 落在 --exclude 目录里的行不参与缺失判定（本次遍历根本没进那些目录） */
function excludedRel(rel, excludeSet) {
  if (!rel) return true;
  for (const seg of String(rel).split('/')) {
    if (excludeSet.has(seg.toLowerCase())) return true;
  }
  return false;
}

/** 写库前的缺失状态快照：{photo:[{id,rel,missing}], video:[...]}（用来算 revived） */
function loadMissingSnapshot(o, mysqlExe, password) {
  const out = { photo: [], video: [] };
  const scope = rootScope(o);
  for (const kind of ['photo', 'video']) {
    const table = kind === 'photo' ? 'media_photo' : 'media_video';
    let last = '0';
    for (;;) {
      const rows = queryRows(o, mysqlExe, password,
        'SELECT id, rel_path, missing FROM ' + table + ' WHERE ' + scope +
        ' AND id > ' + sqlStr(last) + ' ORDER BY id LIMIT ' + sqlInt(PAGE) + ';');
      if (!rows.length) break;
      for (const [id, rel, miss] of rows) {
        out[kind].push({ id, rel, missing: Number(miss) === 1 });
        last = id;
      }
    }
  }
  return out;
}

/**
 * 收尾：把「本次扫描看到的 rel_path」与库内行比对
 *
 * <ul>
 *   <li>库里有、磁盘上没有 -> missing=1（last_seen 保持最后一次看到的时间，不刷新）；</li>
 *   <li>磁盘上又有 -> missing=0 + last_seen=NOW()（复活）；</li>
 *   <li>磁盘上还在 -> 由 upsert 的 missing=0 / last_seen=NOW() 顺手刷新，这里不重复写。</li>
 * </ul>
 *
 * <p>默认只标记不删；要清掉幽灵行得用 --prune-missing --yes。</p>
 */
function applyMissing(o, mysqlExe, password, present, snapshot) {
  const out = { missing: 0, revived: 0, marked: 0 };
  if (!snapshot) return out;
  const scope = rootScope(o);
  // 判定必须用「写库之后」的 rel_path：本次扫描可能把旧行移动到了新路径（改名/复活），
  // 拿写库前的路径去比对，会把刚移动好的行误标成缺失。
  const after = loadMissingSnapshot(o, mysqlExe, password);
  // --only-video / --only-photo：本次没看的那张表一律不碰（否则全部会被标成缺失）
  const kinds = o.onlyVideo ? ['video'] : o.onlyPhoto ? ['photo'] : ['photo', 'video'];
  const beforeById = new Map();
  for (const kind of ['photo', 'video']) {
    for (const r of (snapshot[kind] || [])) beforeById.set(kind + ':' + r.id, r);
  }
  for (const kind of kinds) {
    const table = kind === 'photo' ? 'media_photo' : 'media_video';
    const rows = after[kind] || [];
    const mark = [];
    const revive = [];
    let still = 0;
    for (const r of rows) {
      if (excludedRel(r.rel, o.excludeSet)) continue;
      const wasMissing = (beforeById.get(kind + ':' + r.id) || {}).missing === true;
      if (present[kind].has(r.rel)) {
        // 复活：上一次是缺失、这次磁盘上又有了（正常路径下 upsert/移动已把 missing 清 0）
        if (wasMissing) out.revived += 1;
        if (r.missing) revive.push(r);
      } else if (r.missing) {
        still += 1;
      } else {
        mark.push(r);
      }
    }
    const stmts = [];
    for (const part of chunks(mark.map((r) => sqlStr(r.id)), o.batch)) {
      stmts.push('UPDATE ' + table + ' SET missing = 1 WHERE missing = 0 AND ' + scope +
        ' AND id IN (' + part.join(',') + ');');
    }
    for (const part of chunks(revive.map((r) => sqlStr(r.id)), o.batch)) {
      stmts.push('UPDATE ' + table + ' SET missing = 0, last_seen = NOW() WHERE missing = 1' +
        ' AND id IN (' + part.join(',') + ');');
    }
    if (stmts.length) {
      const res = runMysql(o, mysqlExe, password,
        'START TRANSACTION;\n' + stmts.join('\n') + '\nCOMMIT;\n');
      if (res.status !== 0) {
        warn('缺失标记同步失败（' + table + '）：' + (res.stderr || '').trim());
        continue;
      }
    }
    out.marked += mark.length;
    out.missing += still + mark.length;
    if (mark.length || revive.length) {
      log('MISSING ' + table + ' 新标记=' + mark.length + ' 复活=' + revive.length +
        ' 仍缺失=' + (still + mark.length));
    }
  }
  return out;
}

/** 库里存在这些表（视频附属行清理前先确认，老库可能还没建） */
function existingTables(o, mysqlExe, password, names) {
  const list = names.map((n) => sqlStr(n)).join(',');
  const rows = queryRows(o, mysqlExe, password,
    'SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ' +
    'AND table_name IN (' + list + ');');
  const set = new Set();
  for (const r of rows) if (r.length) set.add(r[0]);
  return set;
}

/**
 * --prune-missing：把 missing=1 的行真删（磁盘上本来就没有文件，回收站无从谈起）
 *
 * <p>删除前打印 TOP 10 与总数；不带 --yes 只打印不删。视频的附属行（标签 / 演员 / 标记）
 * 一并清掉，避免留下指向已删视频的孤儿行。</p>
 */
function pruneMissingRows(o, mysqlExe, password) {
  const scope = rootScope(o);
  const out = { photo: 0, video: 0, deleted: 0 };
  // 与 applyMissing 同口径：--only-video / --only-photo 时另一张表的缺失行不是本次的结论，不许删
  for (const kind of (o.onlyVideo ? ['video'] : o.onlyPhoto ? ['photo'] : ['photo', 'video'])) {
    const table = kind === 'photo' ? 'media_photo' : 'media_video';
    const totalRows = queryRows(o, mysqlExe, password,
      'SELECT COUNT(*) FROM ' + table + ' WHERE ' + scope + ' AND missing = 1;');
    const total = totalRows.length && totalRows[0].length ? Number(totalRows[0][0]) : 0;
    if (!total) {
      log('PRUNE ' + table + '=0');
      continue;
    }
    const top = queryRows(o, mysqlExe, password,
      "SELECT id, rel_path, IFNULL(last_seen, '') FROM " + table + ' WHERE ' + scope +
      ' AND missing = 1 ORDER BY rel_path LIMIT 10;');
    log('PRUNE ' + table + '=' + total + '（TOP ' + Math.min(10, total) + '）');
    for (const [id, rel, seen] of top) {
      log('  MISSING ' + kind + ' id=' + id + ' rel=' + rel + (seen ? ' last-seen=' + seen : ''));
    }
    if (!o.yes) {
      log('  --prune-missing 未带 --yes：只列出、不删除（确认无误后加 --yes）');
      continue;
    }
    const idRows = queryRows(o, mysqlExe, password,
      'SELECT id FROM ' + table + ' WHERE ' + scope + ' AND missing = 1;');
    const ids = idRows.map((r) => (r.length ? r[0] : null)).filter(Boolean);
    const stmts = [];
    if (kind === 'video' && ids.length) {
      const ready = existingTables(o, mysqlExe, password,
        ['media_video_tag', 'media_video_actor', 'media_video_marker']);
      for (const dep of ['media_video_tag', 'media_video_actor', 'media_video_marker']) {
        if (!ready.has(dep)) continue;
        for (const part of chunks(ids.map((x) => sqlStr(x)), o.batch)) {
          stmts.push('DELETE FROM ' + dep + ' WHERE video_id IN (' + part.join(',') + ');');
        }
      }
    }
    stmts.push('DELETE FROM ' + table + ' WHERE ' + scope + ' AND missing = 1;');
    const res = runMysql(o, mysqlExe, password,
      'START TRANSACTION;\n' + stmts.join('\n') + '\nCOMMIT;\n');
    if (res.status !== 0) {
      warn('清理缺失行失败（' + table + '）：' + (res.stderr || '').trim());
      continue;
    }
    const after = queryRows(o, mysqlExe, password,
      'SELECT COUNT(*) FROM ' + table + ' WHERE ' + scope + ' AND missing = 1;');
    const left = after.length && after[0].length ? Number(after[0][0]) : 0;
    const removed = Math.max(0, total - left);
    out[kind] = removed;
    out.deleted += removed;
    log('PRUNE ' + table + ' removed=' + removed);
  }
  return out;
}

// ---------------------------------------------------------------- 主流程

/**
 * 汇总行（管理端按 key=value 逐项取值，只加键、不改既有键）
 *
 * @param miss {missing, revived} 缺失同步结果；未写库（dry-run / dump-sql）时传 null，两键给 '-'
 */
function statsLine(stats, miss) {
  return '新插入=' + stats.insert + ' 移动=' + stats.move + ' 更新=' + stats.update +
    ' 重复=' + stats.dup + ' 跳过=' + stats.skip + ' 别名=' + stats.alias +
    ' missing=' + (miss ? miss.missing : '-') + ' revived=' + (miss ? miss.revived : '-');
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
  // 补救模式只动 poster_path 一列，不扫盘也不需要 --root
  if (o.fixPosterPath) return runFixPosterPath(o);
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
  if (o.backfillAlbumMtime) return runBackfillAlbumMtime(o, root);

  const needDb = !(o.dryRun || o.dumpSql);
  let mysqlExe = null;
  let password = null;
  let state = emptyState();
  if (needDb) {
    mysqlExe = resolveExe(o.mysql, 'MS_MYSQL', 'mysql', 'mysql 客户端');
    password = readPassword(o);
    // --truncate 会清空这三张表：库内状态一律作废，按空库重新分配 aNNN（与旧行为一致）
    state = o.truncate ? emptyState() : loadState(o, mysqlExe, password);
    o.rootId = resolveRootId(o, mysqlExe, password);
    o.aliasEnabled = aliasTablesReady(o, mysqlExe, password);
    o.albumPersonEnabled = existingTables(o, mysqlExe, password, ['media_album_person']).has('media_album_person');
    // 缺失标记两列没建时整段降级：生成的 SQL 与改动前逐字相同
    o.missingEnabled = o.truncate ? false : missingReady(o, mysqlExe, password);
    // 相册 mtime 列（历史行回填由 --backfill-album-mtime 负责，缺列时两处一起降级）
    o.albumMtimeEnabled = albumMtimeReady(o, mysqlExe, password);
  } else {
    // dry-run / dump-sql 不连库：按「别名表 / missing 列已建」生成 SQL
    // （执行前请确认已 source media_dup_alias.sql 与 doc/media_missing.sql）
    o.aliasEnabled = true;
    o.missingEnabled = true;
    o.albumPersonEnabled = true;
    o.albumMtimeEnabled = true;
  }

  const scan = scanDisk(root, o);
  const model = planImport(scan, state, o);
  const photoCount = model.albums.reduce((s, a) => s + a.photoCount, 0);

  log('videos=' + scan.videos.length + ' albums=' + model.albums.length +
    ' photos=' + photoCount + ' people=0');
  log('root=' + root + ' root-id=' + (o.rootId === null ? 'NULL' : o.rootId) +
    ' big-bytes=' + o.bigBytes + ' album-mode=' + o.albumMode +
    (o.onlyVideo ? ' only-video=1' : '') + (o.onlyPhoto ? ' only-photo=1' : '') +
    (o.limit !== null ? ' limit=' + o.limit : '') +
    ' hash=' + (o.noHash ? 'off' : o.hashBytes + 'B') +
    ' exclude=' + o.exclude.join(',') + (o.truncate ? ' truncate=1' : ''));
  for (const a of model.albums.slice(0, 20)) {
    log('  album ' + a.id + ' dir="' + a.dirKey + '" photos=' + a.photoCount +
      ' bytes=' + a.bytes + ' title="' + a.title + '"' + (a.reused ? ' (复用)' : ' (新建)'));
  }
  if (model.albums.length > 20) log('  …还有 ' + (model.albums.length - 20) + ' 本相册');
  for (const w of scan.warnings) warn(w);

  // 唯一性保证：相册身份只有 (dir_key,title)，标题一旦推导变了就会「同一套内容两本册」。
  // 只要发现同目录的 0 图旧行，就别静默并存——默认报错退出，给开关才清理。
  if (model.staleAlbums.length) {
    warn('有 ' + model.staleAlbums.length + ' 本相册本次没被任何分组认领（册内 0 图、dir_key 在本次产物里）：' +
      '同一目录的图已被别的相册整本认领，通常是换过 --album-mode 或改过标题推导留下的旧行');
    for (const s of model.staleAlbums.slice(0, 10)) {
      warn('  STALE album ' + s.id + ' dir="' + s.dirKey + '" title="' + s.title + '"');
    }
    if (model.staleAlbums.length > 10) warn('  …还有 ' + (model.staleAlbums.length - 10) + ' 本');
    if (o.purgeUnclaimed) {
      log('== --purge-unclaimed：同一事务内删除 ' + model.staleAlbums.length + ' 本空壳旧相册（含人物关联行）');
    } else if (!(o.dryRun || o.dumpSql)) {
      throw new Error('发现 ' + model.staleAlbums.length + ' 本「同 dir_key + 0 图」的旧相册行：' +
        '本次扫描会在同一目录里再建/复用另一本，两套相册并存（旧版本就是这么留下永远打不开的空相册）。' +
        '二选一后再跑：① 加 --purge-unclaimed，让本次扫描在同一事务里删掉这些空壳；' +
        '② 先执行 doc/media_album_dedupe.sql（先预览再删、幂等）清理存量。本次未写库。');
    }
  }

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
    log(statsLine(model.stats, null));
    if (o.missingEnabled) {
      log('（未写库：missing / revived 只在真实运行时同步，dump 出来的 SQL 不含这段收尾）');
    }
    printDups(model.dupList);
    printAliases(model.aliasList);
    return 0;
  }

  // 写库前先记下 missing 现状：复活（revived）要跟「上一次的状态」比才算得出来
  const missSnap = o.missingEnabled ? loadMissingSnapshot(o, mysqlExe, password) : null;
  const sql = buildSql(model, o);
  const r = runMysql(o, mysqlExe, password, sql);
  if (r.status !== 0) {
    throw new Error('导入失败（mysql 退出码 ' + r.status + '）：\n' + (r.stderr || '').trim() +
      '\n提示：SQL 已在内存生成，未落盘；修好数据后用 --dry-run 复核。');
  }
  log('== 写库完成 ' + o.db + '@' + o.host + ':' + o.port + '（' + scan.videos.length + ' 视频 / ' +
    model.albums.length + ' 相册 / ' + photoCount + ' 图）');
  // 收尾：库里有、磁盘没有 -> missing=1；磁盘又有了 -> missing=0（复活）
  const missOut = o.missingEnabled
    ? applyMissing(o, mysqlExe, password, scan.present, missSnap)
    : { missing: 0, revived: 0 };
  log(queryCounts(o, mysqlExe, password));
  log(statsLine(model.stats, missOut));
  log('相册 复用=' + model.albums.filter((a) => a.reused).length +
    ' 新建=' + model.albums.filter((a) => !a.reused).length);
  printDups(model.dupList);
  printAliases(model.aliasList);
  if (o.pruneMissing) {
    if (!o.missingEnabled) {
      warn('--prune-missing 未执行：missing 列还没建（先 source doc/media_missing.sql）');
    } else {
      const pruned = pruneMissingRows(o, mysqlExe, password);
      log('清理缺失行 deleted=' + pruned.deleted + '（photo=' + pruned.photo + ' video=' + pruned.video + '）');
    }
  }
  log('== 下一步：node build-thumbs.js --root=' + root + ' --thumbs=<缩略图根>');
  return 0;
}

try {
  process.exitCode = main();
} catch (e) {
  process.stderr.write('ERROR ' + (e && e.message ? e.message : String(e)) + '\n');
  process.exitCode = 1;
}
