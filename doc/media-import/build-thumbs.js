#!/usr/bin/env node
'use strict';
/**
 * 通用媒体缩略图生成器（零依赖，child_process 调 ffmpeg）
 *
 * 从库里读 media_video / media_photo，按 Java 端（MediaFileServiceImpl）的磁盘约定生成：
 *   视频封面   <thumbs>/v/<content_hash>.jpg                  scale=480:-2
 *              content_hash 为 NULL 时回退 <thumbs>/v/<videoId>.jpg（老命名，仍可用）
 *   图片缩略图 <thumbs>/p/<content_hash>.jpg                   scale=400:-2
 *              content_hash 为 NULL 时回退 <thumbs>/p/<albumId>/<%04d>.jpg（老命名，仍可用）
 *   大图预览   <thumbs>/pv/<content_hash>.jpg（仅 is_big=1）     scale=1600:-2
 *              content_hash 为 NULL 时回退 <thumbs>/pv/<albumId>/<%04d>.jpg
 *   精灵图     <thumbs>/s/<content_hash>.jpg                  20x20 网格，每格 160x90（成品恒 3200x1800）
 *              （select 绝对时间格栅 + tile 一次成型，400 格；格数可用 --sprite-cols/rows 改）
 *   预览视频   <thumbs>/pv2/<content_hash>.mp4（或 .webm）       静音 320x180 5~8 秒
 *              （帧数 = 影片时长/sampleSec，夹在 32~300 帧；帧率由此反推，见 pvPlan）
 *              （按 ffmpeg 里的编码器择一：libx264 > libvpx-vp9 > libvpx > mpeg4）
 *   s/ 与 pv2/ 同样按内容寻址，content_hash 为 NULL 时回退 <videoId>。
 *   两者默认**不**生成：要 --sprite / --preview 显式开启，管理端现有命令行为不变。
 *
 * 视频封面按内容寻址：videoId = sha1(rel_path)[:12] 会随改名变化，content_hash 不会，
 * 所以同一份内容改名 / 移动后复用同一张封面。新老命名共存：Java 端先找 <hash>.jpg，
 * 找不到再回退 <id>.jpg，历史产物不迁移也能直接上线。
 *
 * 图片缩略图/大图同样按内容寻址：旧命名 p/<albumId>/<%04d>.jpg 里的 seq 会随相册内新增
 * 图片整体后移，导致「同一张图串到别的 seq 上」——P4 只改了视频这一半，本条即补齐。
 * 新老命名共存：Java 端先找 <content_hash>.jpg，找不到再回退 <albumId>/<seq4>.jpg，
 * 历史产物不迁移也能直接上线。
 *
 * 派生资源回收（--gc）：扫 thumbs/v、thumbs/s、thumbs/pv2（按 video 的 hash/id 判定）与
 * thumbs/p、thumbs/pv（按 photo 的 content_hash 判定），删掉孤儿产物；p/ pv/ 只看顶层
 * 文件，老命名的 <albumId>/ 子目录一律不动。P5.15 起还扫 thumbs/frames/（实时取帧缓存，
 * 目录名 = 内容哈希，见下行注释）。只碰这几类派生文件，绝不碰媒体源。
 * 默认干跑只打印，--gc --yes 才真删
 * （与 scan-media.js 的 --truncate/--yes 同一套确认语义）。
 *
 * 指定范围：--video=<id[,id]>（可重复）/ --ids-file=<每行一个> 只处理这几条视频的三档产物，
 *           图片缩略图一概不碰；库里没有的 id 只 WARN 并计入 skipped（管理端「勾选生成」用它）。
 *
 * 增量：产物存在、非空，且产物 mtime >= 源文件 mtime -> skip（源被替换 / 重编码后自动重建）；
 *       --force 强制重建；--no-fresh 关掉 mtime 判断，退回「存在即跳过」的旧语义。
 * 幂等：只写缩略图根目录，不碰源媒体目录。
 *
 * 输出（管理端 MediaScanServiceImpl 的 thumb 模式按这些行解析，改动前先看它）：
 *   START total=<n> tasks=videos,photos,previews[,sprites,pvclips] force=<0|1> fresh=<0|1>
 *         sprite=<0|1> preview=<0|1> gc=<0|1>
 *   PROGRESS <done>/<total>          每条之后一行
 *   FAIL <rel_path>: <原因>           单条失败不中断
 *   TASK <task> total=<n> ok=<n> skip=<n> fail=<n>
 *   DISTINCT <photos|previews> rows=<n> files=<n> missing=<n>
 *        内容寻址的产物比行数少是正常的（多张重复内容共用一个 <content_hash>.jpg），
 *        rows 是库里的行数、files 是去重后应有的文件数、missing 才是真缺文件。
 *        「ok=0 skip=470」不代表该建未建：先看这一行的 missing。
 *   GC scan dirs=v,s,pv2,p,pv,frames files=<n> orphans=<n> bytes=<n>
 *   GC TOP <v|s|pv2|p|pv|frames/<bucket>>/<name> size=<n>   最多 10 行（按体积从大到小）
 *   GC dry-run ... / GC remove dirs=v,s,pv2,p,pv,frames removed=<n> failed=<n> bytes=<n>
 *   ITEM video=<id> task=<video|sprite|pvclip> result=<ok|skip|fail> out=<相对路径> seconds=<n> [reason=...]
 *        逐条结果行（每完成一项一行）：后端按它汇总「哪几条生成了」，见 MediaScanServiceImpl 的 RESULT 行
 *   DONE ok=<n> skip=<n> fail=<n> seconds=<n>[ GC removed=<n> kept=<n>] ids=<逗号分隔的已处理 videoId>
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
  // --root-from-db：每条视频 / 图片按自己的 media_* 行 root_id 去 media_root 取媒体根，
  // 多库根场景下 --root 只当「root_id 为空 / 查不到」的兜底（P5.96）
  rootFromDb: false,
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
  sprite: false,
  // 精灵图网格（null = 用 SPRITE 的默认 20x20）：整库重采样时阶段性换网格用，产物文件名不变
  spriteCols: null,
  spriteRows: null,
  preview: false,
  gc: false,
  gcYes: false,
  // --video / --ids-file：只处理指定 videoId（可重复传、可逗号分隔、可从文件读）
  video: null,
  idsFile: null,
};

// 精灵图：默认 20 列 x 20 行（= 400 格）、每格 160x90 -> 成品恒为 3200x1800。
// P5.14：10x10 时一格覆盖影片时长/100（2 小时片 ≈ 72 秒），悬停/标记取到的帧太粗；
// 400 格把它压到 /400（同片 ≈ 18 秒）。网格可由 --sprite-cols= / --sprite-rows= 覆盖，
// 但**整库必须一次全换成同一套网格**：文件名不变（s/<hash>.jpg），前端靠 SPRITE_COLS/SPRITE_ROWS
// 常量切格，10x10 与 20x20 的产物混在一起会整体切错帧（见 docs/精灵图重采样与重生成.md）。
const SPRITE = { cols: 20, rows: 20, w: 160, h: 90 };
// 预览视频：静音、低码率，时长夹在 5~8 秒（需求是 5-10 秒）。
// 帧数（= 悬停定位精度）由**影片时长**决定，见 pvPlan：旧实现固定 4fps×8s = 32 帧，
// 把 464 秒的片子压成 32 个采样点（每帧覆盖 14.5 秒），悬停到 50% 时显示的是第 15 帧
// （影片 217 秒处，比指针位置早 14.5 秒）——「预览图和实际位置不一样」的根因。
const PV = {
  crf: 30,         // 帧数从 32 涨到 300+ 后同画质需要略降 CRF（实测 32 会掉 ~0.05 SSIM）
  fps: 4,          // 帧数下限对应的帧率（短片 / 时长未知时就是它）
  maxFps: 40,      // 帧率上限（= 8 秒成片里最多 320 帧）
  minSec: 5,
  maxSec: 8,
  w: 320,
  h: 180,
  sampleSec: 1,    // 目标采样间隔：影片每 1 秒一个采样帧
  maxFrames: 300,  // 采样帧数上限（再长就按上限均摊）
  minFrames: 32,
};

const HELP = `
用法：node build-thumbs.js --thumbs=<缩略图根> [--root=<媒体根>] [选项]

  --root=<dir>        媒体根（用于定位源文件），默认取环境变量 MS_MEDIA_ROOT，再退到当前目录
  --root-from-db      按每行的 media_video.root_id / media_photo.root_id 去 media_root 取媒体根
                      逐条解析源文件（多库根必带）；root_id 为空或查不到时回落到 --root
  --thumbs=<dir>      缩略图根（必填，也认环境变量 MS_THUMBS）
  --ffmpeg=<exe>      ffmpeg，优先级：--ffmpeg > 环境变量 MS_FFMPEG > PATH
  --mysql=<exe>       mysql 客户端，优先级：--mysql > 环境变量 MS_MYSQL > PATH
  --db=<name>         默认 qtools
  --user=<name>       默认 root
  --host=<host>       默认 127.0.0.1
  --port=<n>          默认 3306
  --force             无条件重建全部产物（忽略 mtime 与存在性）
  封面来源（P6.00，无需开关）：media_video.poster_source='file' 且 poster_file 存在时，
                      直接用那张自带封面派生 v/<hash>.jpg（不跑 ffmpeg 截帧）；
                      文件不存在或老库没这两列时照旧截帧（stderr WARN 一行）
  --no-fresh          关闭新鲜度检查：产物存在即跳过（等价改动前的旧语义）
  --limit=<n>         只处理前 n 条（调试用）
  --only=<task[,task]> 只跑这一档产物：videos|sprites|pvclips|photos|previews
                      （配 --force 可只重生成预览片段：--preview --only=pvclips --force）
  --video=<id[,id]>   只处理这些 videoId（排查 / 单条重生成用）：可重复传、可逗号分隔，
                      给了它就只处理这些视频的 v/ s/ pv2/ 产物，不再碰任何图片缩略图
  --ids-file=<path>   从文件读 videoId（每行一个，空行与 # 注释忽略）：与 --video 叠加
  --sprite            顺手生成视频精灵图 s/<hash>.jpg（默认 20x20 网格 = 400 格，每格 160x90；
                      成品恒 3200x1800；默认关）
  --sprite-cols=<n>   精灵图列数，默认 20（1~64）
  --sprite-rows=<n>   精灵图行数，默认 20（1~64）；改了网格就必须整库重生成，见下
  --preview           顺手生成预览视频 pv2/<hash>.mp4|webm（静音 5~8 秒，帧数按影片时长取；默认关）
  --gc                扫 v/ s/ pv2/ p/ pv/ frames/ 删孤儿派生文件（默认只打印，配 --yes 才真删）
  --yes, -y           确认 --gc 的删除动作（不给就只是干跑统计）
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
  p/<content_hash>.jpg      图片缩略图，按内容寻址（相册内顺序变化不影响它）
  pv/<content_hash>.jpg     大图预览，按内容寻址（仅 is_big=1）
  p/<albumId>/0000.jpg...   仅当 content_hash 为 NULL 时的回退命名（老命名，保留不删）
  pv/<albumId>/0000.jpg...  同上（大图预览的回退命名）
  s/<content_hash>.jpg      精灵图（--sprite）
  pv2/<content_hash>.mp4    预览视频（--preview；编码器只有 VP9/VP8 时是 .webm）
  frames/<content_hash>/<秒>.jpg
                            实时取帧缓存（P5.15，后端 /media/frame/{id}?t=<秒> 按需写入，
                            不是本脚本的产物；--gc 只负责回收孤儿目录，见下）

精灵图 / 预览视频的取帧间隔都由 media_video.dur 决定：精灵图目标是铺满 cols*rows 格
（默认 20x20=400 格，step=时长/400），预览视频是「成片 5~8 秒、帧数 = 时长/1 秒（32~300）」——
帧数就是悬停定位精度，一个采样帧代表时长/帧数 秒的影片。两者同时开启时
**一次 ffmpeg 解码同时出两张产物**（-filter_complex 分两路），不做二次解码。
一张图/一段视频失败只计一次 fail，不中断整批。

--gc：只删 thumbs/v、thumbs/s、thumbs/pv2 里「文件名去掉扩展名后既不在
media_video.content_hash 里、也不在 media_video.id 里」的文件，以及 thumbs/p、thumbs/pv
顶层「文件名去掉扩展名后不是任何 media_photo.content_hash」的文件；不递归、不碰
媒体源目录、不删目录本身（老命名的 p/<albumId>/、pv/<albumId>/ 子目录整棵保留）。
删除前打印 TOP 10 与总数，只有再加 --yes 才真删。

--gc 与 thumbs/frames/（P5.15 实时取帧缓存）：结构是 frames/<桶>/<秒>.jpg，桶名与 v/ s/ pv2/
同一套（content_hash 优先，回退 videoId）。回收规则因此按「桶」判：桶名既不是任何
content_hash 也不是任何 videoId -> 整棵目录删除；桶内的 <秒>.jpg 全部保留（秒是无限的，
只有视频被删 / 换了内容才会整桶变孤儿）。顶层落单文件按 v/ s/ pv2 同一条 stem 规则判。

增量判定：产物存在且非空，且产物 mtime >= 源文件 mtime 才算「已是最新」。
源文件被替换 / 重编码后 mtime 变新，下一次运行自动重建该条产物。

输出行（管理端解析，不要改）：
  START total=<n> ... / PROGRESS <done>/<total> / DONE ok=<n> skip=<n> fail=<n> ids=<...>
  ITEM video=<id> task=<video|sprite|pvclip> result=<ok|skip|fail> out=<相对路径> seconds=<n> [reason=...]
      逐条结果行：每完成一项打一行（task 只有视频的三档；图片任务不打）。
      out 相对 --thumbs，正斜杠；skip / fail 也打一行，fail 另有 reason=（空白换成 _）。
      指定了 --video 但库里没有的 id：打 WARN 视频不存在 <id>，并打一行
      ITEM video=<id> task=video result=skip out=- reason=video-not-found。
  DISTINCT photos|previews rows=<n> files=<n> missing=<n>
      内容寻址去重：rows 是库里的行数、files 是应有的 <content_hash>.jpg 个数、
      missing 才是真缺文件（skip 多不代表该建未建）
`;

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const o = Object.assign({}, DEFAULTS, { ignored: [] });
  for (const a of argv) {
    if (a === '--force') o.force = true;
    else if (a === '--no-fresh') o.fresh = false;
    else if (a === '--sprite') o.sprite = true;
    else if (a === '--preview') o.preview = true;
    else if (a === '--gc') o.gc = true;
    else if (a === '--yes' || a === '-y') o.gcYes = true;
    else if (a === '--help' || a === '-h') o.help = true;
    else if (a.startsWith('--root=')) o.root = a.slice('--root='.length);
    else if (a === '--root-from-db') o.rootFromDb = true;
    else if (a.startsWith('--thumbs=')) o.thumbs = a.slice('--thumbs='.length);
    else if (a.startsWith('--ffmpeg=')) o.ffmpeg = a.slice('--ffmpeg='.length);
    else if (a.startsWith('--mysql=')) o.mysql = a.slice('--mysql='.length);
    else if (a.startsWith('--secret=')) o.secret = a.slice('--secret='.length);
    else if (a.startsWith('--db=')) o.db = a.slice('--db='.length);
    else if (a.startsWith('--user=')) o.user = a.slice('--user='.length);
    else if (a.startsWith('--host=')) o.host = a.slice('--host='.length);
    else if (a.startsWith('--port=')) o.port = a.slice('--port='.length);
    else if (a.startsWith('--limit=')) o.limit = Number(a.slice('--limit='.length));
    // 精灵图网格（默认 20x20）：整库一次性重生成时用同一组参数，产物文件名不变
    else if (a.startsWith('--sprite-cols=')) o.spriteCols = parseGrid(a.slice('--sprite-cols='.length), '--sprite-cols');
    else if (a.startsWith('--sprite-rows=')) o.spriteRows = parseGrid(a.slice('--sprite-rows='.length), '--sprite-rows');
    // 只跑某一档产物（pvclips 用来「全库重生成预览片段」而不动 1 万张图片缩略图）
    else if (a.startsWith('--only=')) {
      o.only = a.slice('--only='.length).split(',').map((s) => s.trim()).filter(Boolean);
    }
    // 只处理指定 videoId（排查 / 重生成个别片子的派生资源，不动全库）
    // 可重复传（--video=a --video=b）也可逗号分隔（--video=a,b）：两种写法叠加，最后按出现顺序去重
    else if (a.startsWith('--video=')) {
      const ids = a.slice('--video='.length).split(',').map((s) => s.trim()).filter(Boolean);
      o.video = (o.video || []).concat(ids);
    }
    // 从文件读 id（每行一个，空行与 # 注释忽略）：上千条时命令行会超长，用它绕开
    else if (a.startsWith('--ids-file=')) o.idsFile = a.slice('--ids-file='.length);
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

/**
 * 读 --ids-file：每行一个 id，忽略空行与 # 注释（上千条 id 靠命令行会超长，用它绕开）
 */
function readIdsFile(file) {
  const abs = path.resolve(file);
  let raw;
  try {
    raw = fs.readFileSync(abs, 'utf8');
  } catch (e) {
    throw new Error('--ids-file 指定的文件读不到：' + abs + '（' + (e.code || e.message) + '）');
  }
  const ids = [];
  for (const line of raw.replace(/^\uFEFF/, '').split(/\r?\n/)) {
    const text = line.trim();
    if (text === '' || text.startsWith('#')) continue;
    ids.push(text);
  }
  return ids;
}

// videoId 在库里是 varchar(16)（sha1(rel_path)[:12] 或手工 id）：只收字母数字与 _-，
// 避免把任意文本拼进 SQL 的 IN (...) 与命令行
const VIDEO_ID_RE = /^[A-Za-z0-9_-]{1,32}$/;

/** --video 与 --ids-file 合并去重（保序）：返回要处理的 videoId 列表，空表 = 不筛 */
function selectedVideoIds(o) {
  const raw = (o.video || []).slice();
  if (o.idsFile) raw.push(...readIdsFile(o.idsFile));
  const out = [];
  for (const item of raw) {
    const id = String(item).trim();
    if (id === '' || out.indexOf(id) >= 0) continue;
    if (!VIDEO_ID_RE.test(id)) {
      throw new Error('videoId 形状不对（只允许字母数字与 _-，最长 32 字符）：' + id);
    }
    out.push(id);
  }
  return out;
}

/**
 * media_video 的封面来源两列（P6.00 / doc/media_video_poster_source.sql）
 *
 * <p>老库还没执行那个迁移时这两列不存在，硬查会直接报 1054 Unknown column：所以先探一次，
 * 没有就整段退化成「全部按截帧」——与 scan-media.js 的降级口径一致。</p>
 */
function posterColumnsReady(mysqlExe, o, password) {
  try {
    const rows = query(mysqlExe, o, password,
      "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() " +
      "AND table_name = 'media_video' AND column_name IN ('poster_source','poster_file');");
    const n = rows.length && rows[0] && rows[0].length ? Number(rows[0][0]) : 0;
    if (n === 2) return true;
  } catch (e2) {
    warn('探测 poster_source / poster_file 失败，按「没有这两列」处理：' + oneLine(e2.message));
    return false;
  }
  warn('media_video 还没有 poster_source / poster_file（先执行 doc/media_video_poster_source.sql）：' +
    '封面一律按 ffmpeg 截帧生成');
  return false;
}

/** 视频 SELECT 的列：缺列时不多这两项（见 posterColumnsReady） */
function videoSelectCols(o) {
  return 'id,rel_path,content_hash,dur,IFNULL(root_id,0)' +
    (o.posterEnabled ? ",IFNULL(poster_source,'frame'),IFNULL(poster_file,'')" : '');
}

/** 只查选中的 id：--video 时不再把全库视频拉进内存 */
function queryVideosByIds(mysqlExe, o, password, ids) {
  const list = ids.map((id) => "'" + id.replace(/'/g, "''") + "'").join(',');
  return query(mysqlExe, o, password,
    'SELECT ' + videoSelectCols(o) + ' FROM media_video WHERE id IN (' + list + ') ORDER BY id;');
}

/**
 * 库里所有视频（不带 --video 时）
 *
 * <p>root_id 一起取：多库根下每条视频的源文件在<b>自己</b>那个媒体根里，
 * 拿单一 --root 拼路径就会把「非默认根」的条目全判成「源文件不存在」。</p>
 */
function queryAllVideos(mysqlExe, o, password) {
  return query(mysqlExe, o, password,
    'SELECT ' + videoSelectCols(o) + ' FROM media_video ORDER BY id;');
}

/** mysql 行 -> 视频对象（NULL 还原成 null，免得拿字面量 "NULL" 当哈希拼文件名） */
function mapVideoRows(rows) {
  return rows.map((c) => ({
    id: c[0], rel: c[1], hash: c[2] === 'NULL' ? null : c[2], dur: c[3] === 'NULL' ? null : c[3],
    rootId: c[4] === undefined || c[4] === 'NULL' ? 0 : Number(c[4]) || 0,
    // P6.00：file = 同目录「视频名-封面」自带封面（poster_file 指向它），frame = 老行为截帧
    poster: c[5] === undefined || c[5] === 'NULL' ? 'frame' : String(c[5]).trim() || 'frame',
    cover: c[6] === undefined || c[6] === 'NULL' ? '' : String(c[6]),
  }));
}

/**
 * media_root 里的媒体根（{@code kind='root'}）：root_id -> 绝对路径（P5.96 多库根）
 *
 * <p>返回 Map&lt;number, {id,name,path}&gt;。表不存在 / 读库失败只 WARN 并返回空表：
 * 那时全部回落到 --root，等价改动前的单根行为，不该因为一条查询把整批缩略图挡下来。</p>
 */
function loadRoots(mysqlExe, o, password) {
  const roots = new Map();
  let rows;
  try {
    rows = query(mysqlExe, o, password,
      "SELECT id,IFNULL(name,''),path FROM media_root WHERE kind='root' ORDER BY id;");
  } catch (e) {
    warn('--root-from-db 读 media_root 失败，全部按 --root 解析：' + oneLine(e.message));
    return roots;
  }
  for (const c of rows) {
    const id = Number(c[0]);
    const p = String(c[2] || '').trim();
    if (!Number.isFinite(id) || p === '') continue;
    roots.set(id, { id: id, name: c[1] || '', path: path.resolve(p.replace(/[\\/]+/g, path.sep)) });
  }
  return roots;
}

/** 一条记录（视频 / 图片）该用哪个媒体根：root_id 命中 media_root 就用它，否则 null（调用方回落 --root） */
function rootOfRecord(record, roots) {
  if (!record || !roots) return null;
  const hit = roots.get(record.rootId);
  return hit ? hit.path : null;
}

// ---------------------------------------------------------------- 任务清单

/** 视频封面文件名：content_hash 优先（内容寻址），为空回退 videoId（老命名） */
function thumbName(v) {
  const hash = v.hash == null ? '' : String(v.hash).trim();
  return hash !== '' ? hash : v.id;
}

/** 图片 content_hash（去空白；NULL / 空串返回 ''） */
function photoHashOf(p) {
  return p.hash == null ? '' : String(p.hash).trim();
}

/**
 * 图片缩略图 / 大图预览的产物路径
 *
 * <p>与视频封面同一范式：有 content_hash 就 <b>按内容寻址</b> {@code p|pv/<content_hash>.jpg}，
 * 相册内新增 / 删除图片导致 seq 顺移时缩略图不会串到别的图上；content_hash 为 NULL 的老数据
 * 回退老命名 {@code p|pv/<albumId>/<%04d>.jpg}（Java 端同样先内容寻址再回退，两处必须一致）。</p>
 */
function photoOutPath(thumbs, p, preview) {
  const sub = preview ? 'pv' : 'p';
  const hash = photoHashOf(p);
  if (hash !== '') return path.join(thumbs, sub, hash + '.jpg');
  return path.join(thumbs, sub, p.albumId, pad4(p.seq) + '.jpg');
}

function planTasks(videos, photos, o) {
  const t = [];
  // 多库根（P5.96）：每条记录带自己的媒体根，源文件按它解析；o.roots 为空时 root=null，
  // srcPathOf 照旧回落到 --root（单根部署行为不变）
  const roots = o.roots && typeof o.roots.get === 'function' ? o.roots : null;
  for (const v of videos) {
    const root = rootOfRecord(v, roots);
    const rootId = v.rootId || 0;
    // P6.00：自带封面（poster_source='file'）→ 直接拿那张图派生，不再跑 ffmpeg 截帧。
    // 两种退化都回到截帧：库里没记封面路径（老数据），或文件已经不在磁盘上（被用户删了）——
    // 后一种还要 WARN，否则「海报怎么变了」会变成一条无迹可寻的怪现象。
    let src = v.rel;
    let kind = 'video';
    if (v.poster === 'file' && v.cover) {
      const coverAbs = srcPathOf({ root: root, src: v.cover }, root);
      if (fs.existsSync(coverAbs)) {
        src = v.cover;
        kind = 'cover';
        o.coverUsed = (o.coverUsed || 0) + 1;
      } else {
        o.coverMissing = (o.coverMissing || 0) + 1;
        if (o.coverMissing <= 5) {
          warn('自带封面文件不存在，退回截帧：' + coverAbs + '（视频 ' + v.id + ' ' + v.rel + '）');
        }
      }
    }
    // 内容寻址：同一份内容改名 / 移动后 content_hash 不变，仍复用同一张封面
    // videoId 只用于 ITEM 行（后端按它汇总「哪几条生成了」），不参与落盘路径
    t.push({
      task: 'videos', videoId: v.id, src: src, kind: kind, root: root, rootId: rootId,
      out: path.join(o.thumbs, 'v', thumbName(v) + '.jpg'),
    });
    // 精灵图 / 预览视频紧跟同一条视频：两者都要重建时主流程会把它们合并成一次 ffmpeg
    if (o.sprite) {
      t.push({
        task: 'sprites', videoId: v.id, src: v.rel, kind: 'sprite', dur: v.dur, root: root, rootId: rootId,
        out: path.join(o.thumbs, 's', thumbName(v) + '.jpg'),
      });
    }
    if (o.preview) {
      t.push({
        task: 'pvclips', videoId: v.id, src: v.rel, kind: 'pvclip', dur: v.dur, root: root, rootId: rootId,
        out: path.join(o.thumbs, 'pv2', thumbName(v) + '.' + (o.previewEncoder || {}).ext),
      });
    }
  }
  for (const p of photos) {
    const root = rootOfRecord(p, roots);
    const rootId = p.rootId || 0;
    // 内容寻址：相册内加图后 seq 会整体后移，按 <content_hash>.jpg 落盘才不会张冠李戴
    t.push({ task: 'photos', src: p.rel, out: photoOutPath(o.thumbs, p, false), kind: 'photo', root: root, rootId: rootId });
  }
  for (const p of photos) {
    if (String(p.isBig) !== '1') continue;
    t.push({ task: 'previews', src: p.rel, out: photoOutPath(o.thumbs, p, true), kind: 'preview',
      root: rootOfRecord(p, roots), rootId: p.rootId || 0 });
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

/**
 * 源文件绝对路径（库里存的是相对媒体根的正斜杠路径）
 *
 * <p>媒体根优先用记录自己的（多库根 / --root-from-db），没有再回落到调用方传的单一根。</p>
 */
function srcPathOf(item, root) {
  return path.resolve(item && item.root ? item.root : root, item.src.replace(/[\\/]+/g, path.sep));
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

/**
 * 按内容寻址的任务里「计划行数」常远大于「真实产物数」：多张重复内容共用一个 <content_hash>.jpg。
 * 汇总一行 {@code DISTINCT <task> rows= files= missing=}，用来把「ok=0 skip=470」和
 * 「产物真的没落地」区分开——missing 才是缺文件，skip 只是本来就已经生成过。
 */
function distinctStats(tasks, taskName) {
  const outs = new Set();
  let rows = 0;
  for (const t of tasks) {
    if (t.task !== taskName) continue;
    rows += 1;
    outs.add(t.out);
  }
  let missing = 0;
  for (const out of outs) {
    if (!alreadyDone(out)) missing += 1;
  }
  return { rows, files: outs.size, missing };
}

// ---------------------------------------------------------------- ITEM / 汇总行

/** 任务档 → ITEM 行的 task 取值（图片档不在契约里，故意不列） */
const ITEM_TASK_OF = { videos: 'video', sprites: 'sprite', pvclips: 'pvclip' };

/** DONE ids= 最多列多少个 videoId（超了截断并注明 truncated，避免一行把日志撑爆） */
const MAX_ITEM_IDS = 200;

/** 产物路径 → 相对 --thumbs 的正斜杠路径（跨平台，前端 / 后端都直接可用作 URL 尾巴） */
function outRel(thumbs, out) {
  const rel = path.relative(thumbs, out).split(path.sep).join('/');
  return rel === '' ? '-' : rel;
}

/** 可读单行文本（保留单个空格，只压掉换行 / 连续空白）：ROOTFAIL 的明细行用它 */
function oneLineText(text) {
  const out = String(text == null ? '' : text).replace(/\s+/g, ' ').trim().slice(0, 240);
  return out === '' ? '-' : out;
}

/** 失败原因压成单行单词（ITEM 行按空白切字段，reason 里不能有空格） */
function oneLine(reason) {
  const text = String(reason == null ? '' : reason).replace(/\s+/g, '_').slice(0, 200);
  return text === '' ? '-' : text;
}

/** ROOTFAIL 每根最多列几条失败原因（够定位就行，别把日志撑爆） */
const ROOT_FAIL_REASONS = 3;

/**
 * 每根汇总（P5.96，只有 --root-from-db 才打）
 *
 * <pre>
 * ROOTSUMS root_id=1 name=主库 ok=176 skip=0 fail=0 root=E:/111/视频
 * ROOTSUMS root_id=3 name=剪辑视频 ok=0 skip=0 fail=63 root=E:/视频
 * ROOTFAIL root_id=3 name=剪辑视频 fail=63 shown=3
 *   - 源文件不存在：E:/111/视频/a.mp4
 * </pre>
 *
 * <p>ROOTSUMS 的 root= 放行尾且 root_id / name 在它前面：路径里有空格也不会把前面的键切歪；
 * ROOTFAIL 的明细行是可读文本（保留空格），后端 / 人都能直接看出「哪个根、为什么失败」。</p>
 */
function emitRootSums(perRoot) {
  const list = Array.from(perRoot.values()).sort((a, b) => a.rootId - b.rootId);
  for (const r of list) {
    log('ROOTSUMS root_id=' + r.rootId + ' name=' + oneLine(r.name || '-') +
      ' ok=' + r.ok + ' skip=' + r.skip + ' fail=' + r.fail + ' root=' + r.root);
    if (r.fail > 0) {
      log('ROOTFAIL root_id=' + r.rootId + ' name=' + oneLine(r.name || '-') +
        ' fail=' + r.fail + ' shown=' + r.reasons.length);
      for (const reason of r.reasons) log('  - ' + oneLineText(reason));
    }
  }
}

/** DONE ids= 的取值：逗号分隔的已处理 videoId，超过 200 个截断并注明 truncated=1 */
function doneIdsText(ids) {
  if (!ids || ids.length === 0) return '-';
  if (ids.length <= MAX_ITEM_IDS) return ids.join(',');
  return ids.slice(0, MAX_ITEM_IDS).join(',') + ' truncated=1';
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

function ffmpegArgsFor(item, src, o) {
  const base = ['-y', '-loglevel', 'error'];
  if (item.kind === 'video') return base.concat(['-ss', '3', '-i', src, '-frames:v', '1', '-vf', 'scale=480:-2', '-q:v', '4', item.out]);
  // P6.00 自带封面：静态图，没有 -ss（对图片 seek 会抽不到帧），参数其余与视频封面一致，
  // 这样「截帧封面」和「自带封面」落盘的分辨率/质量完全一样，换来源时视觉上不跳。
  if (item.kind === 'cover') return base.concat(['-i', src, '-frames:v', '1', '-vf', 'scale=480:-2', '-q:v', '4', item.out]);
  if (item.kind === 'photo') return base.concat(['-i', src, '-frames:v', '1', '-vf', 'scale=400:-2', '-q:v', '4', item.out]);
  if (item.kind === 'sprite') return base.concat(['-i', src, '-vf', spriteFilter(item, o), '-frames:v', '1', '-q:v', '4', item.out]);
  if (item.kind === 'pvclip') return base.concat(pvclipArgs(item, src, o));
  return base.concat(['-i', src, '-frames:v', '1', '-vf', 'scale=1600:-2', '-q:v', '4', item.out]);
}

function buildOne(ffmpeg, item, root, o) {
  const src = srcPathOf(item, root);
  if (!fs.existsSync(src)) return { ok: false, reason: '源文件不存在：' + src };
  fs.mkdirSync(path.dirname(item.out), { recursive: true });
  const args = ffmpegArgsFor(item, src, o);
  let r = runFfmpeg(ffmpeg, args);
  // 源不足 3 秒时 -ss 3 抽不到帧：回退 -ss 0 再试一次
  if (!r.ok && item.kind === 'video') {
    args[args.indexOf('-ss') + 1] = '0';
    r = runFfmpeg(ffmpeg, args);
  }
  if (!r.ok) return { ok: false, reason: r.reason };
  if (!alreadyDone(item.out)) return { ok: false, reason: 'ffmpeg 返回 0 但没有产物' };
  return { ok: true, reason: null };
}

// ---------------------------------------------------------------- 精灵图 / 预览视频

/** 时长（秒）；库里 dur 为空 / 非正数时返回 0，由调用方退到安全默认值 */
function durOf(item) {
  const d = Number(item.dur);
  return Number.isFinite(d) && d > 0 ? d : 0;
}

function round6(x) {
  return String(Number(x.toFixed(6)));
}

/** --sprite-cols / --sprite-rows 的取值：正整数，1~64（64x64 已经是 10240x5760，再大没有意义） */
function parseGrid(raw, flag) {
  const n = Number(String(raw).trim());
  if (!Number.isInteger(n) || n < 1 || n > 64) {
    throw new Error(flag + ' 必须是 1~64 的整数（当前 ' + raw + '）');
  }
  return n;
}

/** 本次运行用的精灵图网格：命令行给了就用，否则用 SPRITE 默认值（20x20） */
function spriteGrid(o) {
  const cols = (o && o.spriteCols) || SPRITE.cols;
  const rows = (o && o.spriteRows) || SPRITE.rows;
  return { cols, rows, cells: cols * rows, w: SPRITE.w, h: SPRITE.h };
}

/** 精灵图采样格宽（秒）：整片切成 cells 个等宽格，一格一帧；时长未知退回 1 秒一格 */
function spriteStep(item, grid) {
  const dur = durOf(item);
  return dur > 0 ? dur / grid.cells : 1;
}

/**
 * 精灵图滤镜：格栅取帧 → 缩到单格尺寸 → tile 拼成一张。
 *
 * 取帧与 pvFilter 用**同一套绝对时间格栅**（第 k 格 = [k*step,(k+1)*step)，每格取第一帧），
 * 所以第 k 格严格对应影片 k*step 秒——前端「ratio → 格号」的换算因此成立。
 * 不用旧的 fps=cells/时长 滤镜：fps 要被舍入到 6 位小数，乘上几百格后整段会偏出几格；
 * 格栅按绝对时间算，误差恒在一个源帧内（30fps 源 ≤33ms），不随格号累积。
 */
function spriteFilter(item, o) {
  const g = spriteGrid(o);
  const step = round6(spriteStep(item, g));
  return "select='isnan(prev_selected_t)+gt(floor(t/" + step + "),floor(prev_selected_t/" + step + "))'" +
    ',scale=' + g.w + ':' + g.h + ',tile=' + g.cols + 'x' + g.rows;
}

/**
 * 预览视频取帧方案。成片固定 5~8 秒，**帧数按影片时长取**（每 sampleSec 秒一帧，夹在
 * minFrames~maxFrames），帧率由此反推：fps = 帧数 / 成片秒数。
 *
 * 为什么不写死 4fps：片段从不播放，只在悬停时被 seek 到某一帧，唯一的指标是
 * 「一个采样帧代表影片多长」= dur / frames。写死 4fps×8s 就只有 32 帧，
 * 464 秒的片子每帧代表 14.5 秒，悬停显示的画面能差出半分钟；
 * 帧数按 dur/sampleSec 取，464 秒 → 304 帧（每帧 1.53 秒），98 秒 → 104 帧（0.94 秒）。
 *
 * 取帧按 step = dur/frames 切成绝对时间格栅（每格取第一帧，见 pvFilter），
 * setpts 再把它们重排成 1/fps 一格的连续时间轴，所以成片是「整段视频的等间隔缩时」
 * 而不是「开头 8 秒」，且第 k 帧严格对应影片 k*dur/frames，
 * 前端 clipSeek = ratio × 成片时长 的换算因此成立。
 */
function pvPlan(item) {
  const dur = durOf(item);
  const seconds = dur > 0 ? Math.min(PV.maxSec, Math.max(PV.minSec, dur)) : PV.maxSec;
  const want = dur > 0
    ? Math.min(PV.maxFrames, Math.max(PV.minFrames, Math.round(dur / PV.sampleSec)))
    : PV.minFrames;
  const fps = Math.min(PV.maxFps, Math.max(PV.fps, Math.ceil(want / seconds)));
  const frames = Math.max(1, Math.round(seconds * fps));
  const step = dur > 0 ? Math.max(0.05, dur / frames) : 1;
  return { seconds, frames, fps, step };
}

function pvFilter(item) {
  const p = pvPlan(item);
  // 取帧：把整片切成 frames 个等宽的**绝对时间格栅**（第 k 格 = [k*step, (k+1)*step)），
  // 每格取第一帧 —— 「当前帧与上一采样帧不在同一格」即选中。
  //
  // 为什么不用旧的 select='...gte(t-prev_selected_t,step)'：那是「距上一采样帧够 step 就取」，
  // 每次都要补到下一个源帧上（最多补一个源帧间隔），误差随帧号累积：304 帧时中段漂 +2.5 秒、
  // 末尾 +5 秒，片段内时间就不再线性对应影片进度（悬停位置随之系统性偏早）。
  // 格栅按绝对时间算，误差恒为一个源帧内（30fps 源 ≤33ms），不累积。
  //
  // 也不用 fps=N/时长 滤镜：它会把输出帧率元数据改成 0.65fps、每帧时长 1.5 秒，
  // 后面 -r 38 只能靠复制帧补齐（实测 304 帧里只有个位数内容不同），整片会「一帧到底」。
  return "select='isnan(prev_selected_t)+gt(floor(t/" + round6(p.step) + "),floor(prev_selected_t/" +
    round6(p.step) + "))',setpts=N/(" + p.fps + '*TB),scale=' + PV.w + ':' + PV.h +
    // 素材本身不满 5 秒时补最后一帧，保证成片仍落在 5~8 秒（不做二次编码、不引入音频）
    ',tpad=stop_mode=clone:stop_duration=' + round6(p.seconds);
}

/**
 * 预览视频的输出参数：帧率 / 时长 / 关键帧间隔。
 * 每 1 秒一个关键帧：悬停 seek 只需解码 ≤1 秒的画面，拖动时不会因为
 * 「从唯一的关键帧解到第 300 帧」而卡顿（片段 8 秒、1080p 源，代价可以忽略）。
 */
function pvOutArgs(p) {
  return ['-g', String(Math.max(1, Math.round(p.fps))), '-r', String(p.fps), '-t', String(p.seconds)];
}

/** 预览视频编码参数（不含输入 / 输出路径，-vf 由调用方拼） */
function pvEncodeArgs(o) {
  const enc = (o && o.previewEncoder) || null;
  if (!enc) throw new Error('内部错误：未探测到可用的预览视频编码器');
  return enc.args.slice();
}

function pvclipArgs(item, src, o) {
  const p = pvPlan(item);
  return ['-i', src, '-vf', pvFilter(item), '-an'].concat(pvEncodeArgs(o))
    .concat(pvOutArgs(p)).concat([item.out]);
}

/** 精灵图 + 预览视频一次解码同时出两张产物 */
function bothArgs(spriteItem, pvItem, src, o) {
  const p = pvPlan(pvItem);
  return ['-y', '-loglevel', 'error', '-i', src,
    '-filter_complex', '[0:v]' + spriteFilter(spriteItem, o) + '[s];[0:v]' + pvFilter(pvItem) + '[p]',
    '-map', '[s]', '-frames:v', '1', '-q:v', '4', spriteItem.out,
  ].concat(['-map', '[p]', '-an']).concat(pvEncodeArgs(o))
    .concat(pvOutArgs(p)).concat([pvItem.out]);
}

/**
 * 同时生成精灵图与预览视频：先试一次 ffmpeg 双输出；
 * 失败（或只出了其中一张）再逐项重试，让失败只落在真正坏掉的那一项上。
 */
function buildSpriteAndClip(ffmpeg, spriteItem, pvItem, root, o) {
  const src = srcPathOf(spriteItem, root);
  if (!fs.existsSync(src)) {
    const reason = '源文件不存在：' + src;
    return { sprite: { ok: false, reason }, pvclip: { ok: false, reason } };
  }
  fs.mkdirSync(path.dirname(spriteItem.out), { recursive: true });
  fs.mkdirSync(path.dirname(pvItem.out), { recursive: true });
  const r = runFfmpeg(ffmpeg, bothArgs(spriteItem, pvItem, src, o));
  const spriteOk = r.ok && alreadyDone(spriteItem.out);
  const pvOk = r.ok && alreadyDone(pvItem.out);
  if (spriteOk && pvOk) {
    return { sprite: { ok: true, reason: null }, pvclip: { ok: true, reason: null } };
  }
  const result = {};
  result.sprite = spriteOk ? { ok: true, reason: null } : buildOne(ffmpeg, spriteItem, root, o);
  result.pvclip = pvOk ? { ok: true, reason: null } : buildOne(ffmpeg, pvItem, root, o);
  if (!r.ok && result.sprite.ok && result.pvclip.ok) {
    // 双输出那一版失败但逐项都成了：不算错，只是没有合并成功
    warn('合并生成失败、逐项重试成功：' + spriteItem.src);
  }
  return result;
}

// ---------------------------------------------------------------- 编码器探测 / GC

/** 按 ffmpeg -encoders 的输出挑一个能用的预览视频编码器（mp4 优先，退 .webm） */
function detectPreviewEncoder(ffmpeg) {
  const r = spawnSync(ffmpeg, ['-hide_banner', '-encoders'], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  const text = (r.stdout || '') + (r.stderr || '');
  const has = (name) => new RegExp('\\b' + name + '\\b').test(text);
  if (has('libx264')) {
    return {
      ext: 'mp4', name: 'libx264',
      args: ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', String(PV.crf), '-pix_fmt', 'yuv420p', '-movflags', '+faststart'],
    };
  }
  if (has('libvpx-vp9')) {
    return {
      ext: 'webm', name: 'libvpx-vp9',
      args: ['-c:v', 'libvpx-vp9', '-b:v', '300k', '-deadline', 'realtime', '-cpu-used', '5', '-pix_fmt', 'yuv420p'],
    };
  }
  if (has('libvpx')) {
    return {
      ext: 'webm', name: 'libvpx',
      args: ['-c:v', 'libvpx', '-b:v', '300k', '-deadline', 'realtime', '-cpu-used', '5', '-pix_fmt', 'yuv420p'],
    };
  }
  if (has('mpeg4')) {
    return { ext: 'mp4', name: 'mpeg4', args: ['-c:v', 'mpeg4', '-q:v', '6', '-pix_fmt', 'yuv420p'] };
  }
  return null;
}

/**
 * thumbs/frames/ 的桶扫描（P5.15 实时取帧缓存）
 *
 * 结构与 v/ s/ pv2/ 不同：不是一层平铺文件，而是 frames/<桶>/<秒>.jpg。所以孤儿判定落在
 * <b>桶</b>上 —— 桶名与 v/ s/ pv2 同一套（content_hash 优先，回退 videoId），桶内的秒数是
 * 无限的、全部保留；只有视频被删或内容变了（哈希换了）才会整桶变孤儿。
 * 顶层落单文件（历史残留）仍按 v/ s/ pv2 的 stem 规则判。
 *
 * 体积按整棵目录累加，GC TOP 里才能看出「这部片到底占了多少盘」。
 */
function scanFrameBuckets(o, keep, scanned) {
  const root = path.join(o.thumbs, 'frames');
  let entries;
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch (e) {
    if (e.code !== 'ENOENT') warn('GC 目录读不到，跳过 ' + root + '：' + (e.code || e.message));
    return;
  }
  for (const ent of entries) {
    const full = path.join(root, ent.name);
    if (ent.isDirectory()) {
      let size = 0;
      try {
        size = dirBytes(full);
      } catch (e) {
        size = 0; // 权限 / 竞态：体积算不出不影响判孤儿
      }
      scanned.push({ sub: 'frames', name: ent.name, full, size, orphan: !keep.has(ent.name), dir: true });
      continue;
    }
    if (!ent.isFile()) continue; // 链接一律不动
    let size = 0;
    try {
      size = fs.statSync(full).size;
    } catch (e) {
      continue;
    }
    const stem = ent.name.replace(/\.[^.]+$/, '');
    scanned.push({ sub: 'frames', name: ent.name, full, size, orphan: !keep.has(stem), dir: false });
  }
}

/** 目录总字节（递归，只统计普通文件；读不到的子项跳过） */
function dirBytes(dir) {
  let total = 0;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    try {
      if (ent.isDirectory()) total += dirBytes(full);
      else total += fs.statSync(full).size;
    } catch (e) {
      continue;
    }
  }
  return total;
}

/**
 * GC：扫 thumbs/v、thumbs/s、thumbs/pv2、thumbs/p、thumbs/pv，把「文件名去掉扩展名后
 * 不在对应 keep 集合里」的文件当孤儿：
 *   · v/ s/ pv2/ 的 keep = 所有 media_video.content_hash + media_video.id；
 *   · p/ pv/ 的 keep = 所有 media_photo.content_hash（内容寻址后的顶层文件名）。
 * 只处理这些目录里的普通文件：不递归、不删目录、不碰媒体源；老命名的
 * p/<albumId>/、pv/<albumId>/ 子目录整个保留（不是文件，直接跳过）。
 * 默认干跑只打印，gcYes（--yes）才真删。
 */
function gcDerived(o, videos, photos) {
  const videoKeep = new Set();
  for (const v of videos) {
    if (v.id != null && String(v.id) !== '') videoKeep.add(String(v.id));
    const hash = v.hash == null ? '' : String(v.hash).trim();
    if (hash !== '') videoKeep.add(hash);
  }
  const photoKeep = new Set();
  for (const p of (photos || [])) {
    const hash = photoHashOf(p);
    if (hash !== '') photoKeep.add(hash);
  }
  const scanPlan = [
    { sub: 'v', keep: videoKeep }, { sub: 's', keep: videoKeep }, { sub: 'pv2', keep: videoKeep },
    { sub: 'p', keep: photoKeep }, { sub: 'pv', keep: photoKeep },
  ];
  const scanned = [];
  scanFrameBuckets(o, videoKeep, scanned);
  for (const { sub, keep } of scanPlan) {
    const dir = path.join(o.thumbs, sub);
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch (e) {
      if (e.code !== 'ENOENT') warn('GC 目录读不到，跳过 ' + dir + '：' + (e.code || e.message));
      continue;
    }
    for (const ent of entries) {
      if (!ent.isFile()) continue; // 目录 / 链接一律不动
      const full = path.join(dir, ent.name);
      let size = 0;
      try {
        size = fs.statSync(full).size;
      } catch (e) {
        continue;
      }
      const stem = ent.name.replace(/\.[^.]+$/, '');
      scanned.push({ sub, name: ent.name, full, size, orphan: !keep.has(stem) });
    }
  }
  const orphans = scanned.filter((f) => f.orphan).sort((a, b) => b.size - a.size);
  const bytes = orphans.reduce((s, f) => s + f.size, 0);
  log('GC scan dirs=v,s,pv2,p,pv,frames files=' + scanned.length + ' orphans=' + orphans.length + ' bytes=' + bytes);
  for (const f of orphans.slice(0, 10)) log('GC TOP ' + f.sub + '/' + f.name + ' size=' + f.size);
  if (!o.gcYes) {
    log('GC dry-run 未删除任何文件（确认无误后加 --yes 才真删）');
    return { removed: 0, kept: scanned.length };
  }
  let removed = 0;
  let failed = 0;
  for (const f of orphans) {
    try {
      // frames/<桶> 是整棵目录（秒数不限，只能整桶回收）；其余都是平铺文件
      if (f.dir) fs.rmSync(f.full, { recursive: true, force: true });
      else fs.unlinkSync(f.full);
      removed += 1;
    } catch (e) {
      failed += 1;
      warn('GC 删除失败 ' + f.sub + '/' + f.name + '：' + (e.code || e.message));
    }
  }
  log('GC remove dirs=v,s,pv2,p,pv,frames removed=' + removed + ' failed=' + failed + ' bytes=' + bytes);
  return { removed, kept: scanned.length - removed };
}

/**
 * 找同一源视频、同样待重建的「另一种」视频派生任务（精灵图 <-> 预览视频），
 * 好让一次 ffmpeg 解码出两张产物；找不到返回 null。
 */
function findMate(tasks, item, needBuild) {
  if (item.kind !== 'sprite' && item.kind !== 'pvclip') return null;
  for (const other of tasks) {
    if (other === item || other.src !== item.src) continue;
    if (other.kind !== 'sprite' && other.kind !== 'pvclip') continue;
    if (other.kind === item.kind) continue;
    return needBuild(other) ? other : null;
  }
  return null;
}

// ---------------------------------------------------------------- 主流程

/**
 * START 汇总行（管理端逐 token 解析，加 token 只能追加、不能挪用已有的键）。
 * sprite=<0|1> 是开关；P5.14 起追加 spritegrid=<cols>x<rows>，让日志自己说清这批产物是几宫格
 * （10x10 与 20x20 的产物**不能混用**，出问题时先看这一格对不上前端常量）。
 */
function startLine(o, total, taskNames, root) {
  const g = spriteGrid(o);
  return 'START total=' + total + ' tasks=' + taskNames.join(',') + ' force=' + (o.force ? 1 : 0) +
    ' fresh=' + (o.fresh ? 1 : 0) + ' sprite=' + (o.sprite ? 1 : 0) +
    ' spritegrid=' + g.cols + 'x' + g.rows +
    ' preview=' + (o.preview ? 1 : 0) + ' gc=' + (o.gc ? 1 : 0) +
    // P5.96：rootfromdb=1 表示源文件按每行自己的 media_root 解析（root= 只作兜底），
    // roots= 是本次用到的媒体根个数——多根部署出问题时先看这两格
    ' rootfromdb=' + (o.rootFromDb ? 1 : 0) + ' roots=' + (o.roots ? o.roots.size : 0) +
    ' root=' + root + ' thumbs=' + o.thumbs;
}

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

  // 多库根（P5.96）：--root-from-db 时先取 media_root，逐条按 root_id 解析媒体根；
  // --root 退化成「root_id 为空 / 查不到这个根」的兜底，单根部署行为不变
  if (o.rootFromDb) {
    o.roots = loadRoots(mysqlExe, o, password);
    if (o.roots.size === 0) {
      warn('--root-from-db 没取到任何媒体根（media_root 空 / 读不到），全部按 --root 解析：' + root);
    }
  }

  // 预览视频的编码器只探测一次（--preview 没开就不花这次子进程）
  if (o.preview) {
    o.previewEncoder = detectPreviewEncoder(ffmpeg);
    if (!o.previewEncoder) {
      throw new Error('--preview 需要 ffmpeg 里至少有 libx264 / libvpx-vp9 / libvpx / mpeg4 之一，' +
        '当前一个都没有：请换一个带编码器的 ffmpeg，或去掉 --preview');
    }
    warn('预览视频编码器：' + o.previewEncoder.name + ' -> pv2/<hash>.' + o.previewEncoder.ext);
  }

  // P6.00：封面来源两列在不在（不在就整段按截帧，绝不因为缺列让派生整批失败）
  o.posterEnabled = posterColumnsReady(mysqlExe, o, password);

  // 指定了 videoId 就只查这几行（IN (...)），不再把全库视频拉进来
  const wanted = selectedVideoIds(o);
  const videos = wanted.length
    ? mapVideoRows(queryVideosByIds(mysqlExe, o, password, wanted))
    : mapVideoRows(queryAllVideos(mysqlExe, o, password));
  // 指定 videoId 时图片缩略图 / 大图一律不碰：调用方要的是「这几条视频的派生产物」
  const photoRows = wanted.length ? [] : query(mysqlExe, o, password,
    'SELECT album_id,seq,rel_path,is_big,content_hash,IFNULL(root_id,0) FROM media_photo ORDER BY album_id,seq;');
  const photos = photoRows.map((c) => ({
    albumId: c[0], seq: Number(c[1]), rel: c[2], isBig: c[3],
    hash: c[4] === undefined || c[4] === 'NULL' ? null : c[4],
    rootId: c[5] === undefined || c[5] === 'NULL' ? 0 : Number(c[5]) || 0,
  }));
  // 请求了但库里没有的 id：不算错，WARN + 计入 skipped（调用方的 ids 可能已被删/改名）
  const found = {};
  for (const v of videos) found[v.id] = true;
  const missingIds = wanted.filter((id) => !found[id]);

  // root_id 指向 media_root 里不存在的行（被删的根 / 手改的 root_id）：一条 WARN 说清有几条，
  // 它们的源文件按 --root 解析（多半找不到，会逐条报「源文件不存在」）
  if (o.roots && o.roots.size > 0) {
    const orphan = videos.filter((v) => v.rootId && !o.roots.has(v.rootId)).length;
    if (orphan > 0) {
      warn(orphan + ' 个视频的 root_id 在 media_root 里查不到，按 --root 解析源文件：' + root);
    }
  }

  const planned = planTasks(videos, photos, o);
  // P6.00：封面来源统计（管理端日志里一眼看出这批封面有多少来自自带封面文件）
  if (o.coverUsed || o.coverMissing) {
    log('cover 自带封面=' + (o.coverUsed || 0) + ' 回退截帧=' + (o.coverMissing || 0));
  }
  // --only：按档过滤（未知档名静默忽略，与「管理端可能传旧参数」的一致处理）
  const tasks = o.only && o.only.length ? planned.filter((t) => o.only.indexOf(t.task) >= 0) : planned;
  // 库里有不存在的 id 时它们也各占一条（打 ITEM skip + PROGRESS），total 因此按 tasks + missing 算
  const total = tasks.length + missingIds.length;
  const startedAt = Date.now();
  const counts = { ok: 0, skip: 0, fail: 0 };
  const taskNames = ['videos', 'photos', 'previews'];
  if (o.sprite) taskNames.push('sprites');
  if (o.preview) taskNames.push('pvclips');
  log(startLine(o, total, taskNames, root));
  // 哈希没回填的视频只能按老命名产出：整批提示一次，避免刷屏（stderr，不干扰管理端解析 stdout）
  const noHash = videos.filter((v) => !v.hash || String(v.hash).trim() === '').length;
  if (noHash > 0) {
    warn(noHash + ' 个视频 content_hash 为 NULL，封面回退 v/<videoId>.jpg（重命名后会重复生成，建议先回填哈希）');
  }
  const noHashPhoto = photos.filter((p) => photoHashOf(p) === '').length;
  if (noHashPhoto > 0) {
    warn(noHashPhoto + ' 张图片 content_hash 为 NULL，缩略图/预览图回退 p|pv/<albumId>/<seq>.jpg' +
      '（相册内加图会让 seq 后移、缩略图可能串位；先跑 scan-media.js --backfill-hash）');
  }

  const needBuild = (t) => o.force || !isFresh(t.out, srcPathOf(t, root), o.fresh);
  // 同一条视频的精灵图 + 预览视频都过期时合并成一次 ffmpeg：这里记「已被同伴顺带生成」的项
  const doneByMate = new Map();

  let done = 0;
  const perTask = {};
  // 每根一份计数（P5.96）：多库根时「成功 0 / 跳过 176 / 失败 63」这种总数看不出是哪个根坏了
  const perRoot = new Map();
  const rootStatOf = (item) => {
    const rootId = item.rootId || 0;
    if (!perRoot.has(rootId)) {
      const hit = o.roots ? o.roots.get(rootId) : null;
      perRoot.set(rootId, {
        rootId: rootId,
        name: hit ? hit.name : '',
        root: item.root || (hit ? hit.path : root),
        ok: 0, skip: 0, fail: 0, reasons: [],
      });
    }
    return perRoot.get(rootId);
  };
  // 出过 ITEM 行的 videoId（去重保序）：DONE ids= 用它，后端据此知道「跑了哪几条」
  const processedIds = [];
  // 指定了 id 但库里没有：WARN 一行（stderr）+ ITEM skip 一行（stdout，机器可解析），计入 skipped
  for (const id of missingIds) {
    warn('视频不存在 ' + id);
    counts.skip += 1;
    log('ITEM video=' + id + ' task=video result=skip out=- seconds=0 reason=video-not-found');
    done += 1;
    log('PROGRESS ' + done + '/' + total);
  }
  for (const t of tasks) {
    if (!perTask[t.task]) perTask[t.task] = { total: 0, ok: 0, skip: 0, fail: 0 };
    perTask[t.task].total += 1;
    const itemStart = Date.now();
    let result;
    if (doneByMate.has(t)) {
      result = doneByMate.get(t);
    } else if (!needBuild(t)) {
      result = { ok: true, skip: true, reason: null };
    } else {
      const mate = findMate(tasks, t, needBuild);
      if (mate) {
        const spriteItem = t.kind === 'sprite' ? t : mate;
        const pvItem = t.kind === 'pvclip' ? t : mate;
        const pair = buildSpriteAndClip(ffmpeg, spriteItem, pvItem, root, o);
        result = Object.assign({ skip: false }, pair[t.kind]);
        doneByMate.set(mate, Object.assign({ skip: false }, pair[mate.kind]));
      } else {
        const r = buildOne(ffmpeg, t, root, o);
        result = { ok: r.ok, skip: false, reason: r.reason };
      }
    }
    const rootStat = rootStatOf(t);
    if (!result.ok) {
      counts.fail += 1;
      perTask[t.task].fail += 1;
      rootStat.fail += 1;
      if (rootStat.reasons.length < ROOT_FAIL_REASONS) rootStat.reasons.push(result.reason);
      log('FAIL ' + t.src + ': ' + result.reason);
    } else if (result.skip) {
      counts.skip += 1;
      perTask[t.task].skip += 1;
      rootStat.skip += 1;
    } else {
      counts.ok += 1;
      perTask[t.task].ok += 1;
      rootStat.ok += 1;
    }
    // 逐条结果行（机器可解析，后端 MediaScanServiceImpl 按它汇总 RESULT）：
    // 视频的三档各打一行，图片任务不打（它们的 id 是 albumId/seq，不在本行的契约里）
    const itemToken = ITEM_TASK_OF[t.task];
    if (itemToken) {
      const label = result.ok ? (result.skip ? 'skip' : 'ok') : 'fail';
      if (processedIds.indexOf(t.videoId) < 0) processedIds.push(t.videoId);
      log('ITEM video=' + t.videoId + ' task=' + itemToken + ' result=' + label +
        ' out=' + outRel(o.thumbs, t.out) + ' seconds=' + Math.round((Date.now() - itemStart) / 1000) +
        (result.ok ? '' : ' reason=' + oneLine(result.reason)));
    }
    done += 1;
    log('PROGRESS ' + done + '/' + total);
  }

  for (const task of ['videos', 'photos', 'previews', 'sprites', 'pvclips']) {
    const c = perTask[task];
    if (!c) continue;
    log('TASK ' + task + ' total=' + c.total + ' ok=' + c.ok + ' skip=' + c.skip + ' fail=' + c.fail);
  }
  // 每根分别的数字（P5.96）：ROOTSUMS 一行一根，ROOTFAIL 再跟最多 3 条失败原因。
  // 只有 --root-from-db 时才打（单根 + 不带该参数的老调用输出一个字节都不变）
  if (o.rootFromDb) {
    emitRootSums(perRoot);
  }
  // 内容寻址的产物比行数少是正常的（重复内容共用一个文件）：missing 才是真缺
  for (const task of ['photos', 'previews']) {
    if (!perTask[task]) continue;
    const d = distinctStats(tasks, task);
    log('DISTINCT ' + task + ' rows=' + d.rows + ' files=' + d.files + ' missing=' + d.missing);
  }

  // GC 放在建完之后：刚产出的文件都是有效名字，不会被误当孤儿
  let gcTail = '';
  if (o.gc) {
    const gc = gcDerived(o, videos, photos);
    gcTail = ' GC removed=' + gc.removed + ' kept=' + gc.kept;
  }

  log('DONE ok=' + counts.ok + ' skip=' + counts.skip + ' fail=' + counts.fail +
    ' seconds=' + Math.round((Date.now() - startedAt) / 1000) + gcTail + ' ids=' + doneIdsText(processedIds));
  return 0; // 单条失败不改变退出码（与原作者 build_thumbs.js 一致）
}

// 只有被当脚本跑才执行主流程：被 require（单测直接调 spriteFilter / buildOne）时只导出纯函数
if (require.main === module) {
  try {
    process.exitCode = main();
  } catch (e) {
    process.stderr.write('ERROR ' + (e && e.message ? e.message : String(e)) + '\n');
    process.exitCode = 1;
  }
}

module.exports = {
  SPRITE, PV, parseArgs, parseGrid, spriteGrid, spriteStep, spriteFilter, pvPlan, pvFilter,
  planTasks, ffmpegArgsFor, buildOne, startLine, log,
};
