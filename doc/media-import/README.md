# 媒体站 → QTools 数据迁移（M1）

## 换成你自己的媒体内容（通用导入）

五个零依赖脚本（只用 Node 内置模块 + 外部 `mysql` / `ffmpeg` / `ffprobe` / `mysqldump`），把**任意媒体目录**变成媒体站内容，
不需要旧 Node 站的 `index.json`：

| 脚本 | 做什么（一句话） | 关键参数 |
| --- | --- | --- |
| `scan-media.js` | 只读遍历媒体根，把目录结构（含内容哈希、多库根、重复副本别名）写进 `media_video` / `media_album` / `media_photo`（别名进 `media_*_alias`） | `--root=`（必填）、`--root-id=`、`--db= --user= --host= --port=`、`--secret=`、`--dry-run`、`--truncate --yes`、`--album-mode=`、`--purge-unclaimed`、`--backfill-hash` |
| `build-thumbs.js` | 读库调 ffmpeg 生成视频封面 / 图片缩略图 / 大图预览，封面按 `content_hash` 内容寻址 | `--thumbs=`（必填）、`--root=`、`--ffmpeg=`、`--force`、`--no-fresh`、`--limit=` |
| `enrich-meta.js` | 读库调 ffprobe 补齐 `media_video` 的 `dur/w/h/vcodec`（待补集合 = 四列任一 NULL） | `--root=`、`--ffprobe=`（或 `MS_FFPROBE`）、`--limit=`、`--dry-run`、`--force` |
| `export-bundle.js` | 把一个库打成便携包目录（`manifest.json` + `media.sql.gz` + `user-state.json` + 可选 `thumbs/`） | `--root= --out=`（必填）、`--with-thumbs`、`--thumbs=`、`--with-stats`、`--dump=`、`--dry-run` |
| `import-bundle.js` | 校验 `schemaVersion` 后把便携包导入目标库（**表级替换**），可选合并用户资产 | `--in=`（必填）、`--db=`、`--create-schema`、`--merge-user-state`、`--force`、`--dry-run` |

每个脚本各一条最小可用示例（口令只走 `MYSQL_PWD` 或 `--secret`，见第 2 节的第 0 步）：

```powershell
node ...\media-import\scan-media.js    --root='D:\我的媒体' --port=3307 --root-id=1
node ...\media-import\build-thumbs.js  --root='D:\我的媒体' --thumbs='D:\我的媒体\_thumbs' --port=3307
node ...\media-import\enrich-meta.js   --root='D:\我的媒体' --port=3307
node ...\media-import\export-bundle.js --root='D:\我的媒体' --out='D:\bundle\qtools' --with-thumbs
node ...\media-import\import-bundle.js --in='D:\bundle\qtools' --db=qtools_new --create-schema --merge-user-state
```

### 1. 目录怎么摆

```text
D:\我的媒体\                     <- --root（只读，脚本绝不写这里）
  散图.jpg                       <- 根目录散图：单独一本相册，title = 根目录名
  跳舞\
    clip.mp4                     <- 视频：folder = 跳舞，id = sha1("跳舞/clip.mp4") 前 12 位
  写真\
    2024-海边\                   <- 一个目录 = 一本相册（id 按目录路径自然序递增 a001、a002…，根目录算一本）
      1.jpg  2.jpg  10.jpg       <- seq 按文件名自然序 0,1,2（10 排在 2 之后，不是字典序）
      大图.jpg                   <- 单文件 >= --big-bytes（默认 2MB）→ is_big=1 → 多生成 pv/ 大图预览
      trash\                     <- 默认排除
  _媒体站\                       <- 默认排除
```

由目录直接推导的规则：

| 目标 | 规则 |
| --- | --- |
| `media_video.id` | `sha1(rel_path)` 前 12 位（与 Java 端算 id 的方式一致） |
| `media_video.folder` | `rel_path` 第一段；文件直接放在根目录时用 `默认` |
| `media_video.rel_path` | 正斜杠相对路径 |
| `media_video.title` | 文件名去扩展名 |
| `media_video.browser_ok` | `.mp4` / `.webm` → 1，其它 → 0 |
| `media_video.poster_path` | 写入值仍是旧命名 `thumbs/v/<id>.jpg`；封面**文件**从 P4 起按 `v/<content_hash>.jpg` 落盘（见「已知限制」） |
| `media_video.content_hash` / `media_photo.content_hash` | oshash = md5(十进制 size + 首/中/尾各 `--hash-bytes` 字节) 前 16 位十六进制；`--no-hash` 时写 NULL |
| `media_video.root_id` / `media_photo.root_id` | `--root-id=<n>`；缺省取 `media_root` 里 `kind='root'` 的第一行，取不到 NULL（= 后端用配置根） |
| `media_album.id` | `a` + 3 位序号，目录相对路径按自然序递增（`a001`、`a002`…） |
| `media_album.title` / `dir` | 目录名 / 目录相对路径（根目录散图：title 取根目录名，dir 为空串） |
| `media_album.photo_count` / `bytes` / `cover_path` | 该目录图片张数 / 总字节 / `thumbs/p/<albumId>/0000.jpg` |
| `media_photo.seq` | 目录内文件名**自然序**（数字感知）从 0 递增 |
| `media_photo.is_big` | `bytes >= --big-bytes` |
| `people` | 恒为 0（通用扫描不建人物，`media_album.person_id` 留 NULL） |

### 2. 五步命令（扫盘 → 缩略图 → 元数据 → 便携包导出/导入）

```powershell
# 0) 数据库密码二选一（都不打印、不落盘、不进命令行参数）
$env:MYSQL_PWD = '<root 密码>'                                     # ① 环境变量
# ② 或从 secret.yml 取 spring.datasource.druid.master.password：
#    --secret='D:\cfg\secret.yml'

# 1) 先扫描导入（默认 upsert，可重复执行；--dry-run 只统计不写库）
node E:\111\project\QTools\QTools\doc\media-import\scan-media.js --root='D:\我的媒体'
node E:\111\project\QTools\QTools\doc\media-import\scan-media.js --root='D:\我的媒体' --secret='D:\cfg\secret.yml'
#    第二个库根：--root-id 指向 media_root.id（不给就取 kind='root' 的第一行，取不到写 NULL 并 WARN）
node E:\111\project\QTools\QTools\doc\media-import\scan-media.js --root='E:\备份盘\媒体' --root-id=2

# 2) 再生成缩略图（先导入后缩略图，顺序不能反：它按库里的 id/相册/seq 决定产物路径）
node E:\111\project\QTools\QTools\doc\media-import\build-thumbs.js --root='D:\我的媒体' --thumbs='D:\我的媒体\_thumbs'
node E:\111\project\QTools\QTools\doc\media-import\build-thumbs.js --root='D:\我的媒体' --thumbs='D:\我的媒体\_thumbs' --secret='D:\cfg\secret.yml'

# 3) 补视频元数据（dur/w/h/vcodec）：ffprobe 优先级 --ffprobe > MS_FFPROBE > PATH
node E:\111\project\QTools\QTools\doc\media-import\enrich-meta.js --root='D:\我的媒体'
node E:\111\project\QTools\QTools\doc\media-import\enrich-meta.js --root='D:\我的媒体' --secret='D:\cfg\secret.yml'
#    只想先看看解出什么：--dry-run（不写库）；已补齐的行想重探：--force
node E:\111\project\QTools\QTools\doc\media-import\enrich-meta.js --root='D:\我的媒体' --dry-run --limit=20

# 4) 导出便携包（换机器用；thumbs 可选，缺了能重建）
node E:\111\project\QTools\QTools\doc\media-import\export-bundle.js --root='D:\我的媒体' --out='D:\bundle\qtools' --with-thumbs

# 5) 在新机器导入（表级替换；--force 是覆盖已有 media_video 的安全闸）
node E:\111\project\QTools\QTools\doc\media-import\import-bundle.js --in='D:\bundle\qtools' --db=qtools --create-schema --merge-user-state
```

> 五个脚本的 `--port` 默认都是 `3306`，而本项目 `application-druid.yml` 默认 `3307`：
> 本地跑一律显式写 `--port=3307`（见第 5 节「常见坑」）。

> **管理端按钮触发时必须用 `--secret=`**：`MediaScanServiceImpl` 只把 `node + 脚本 + 参数` 拼进
> `ProcessBuilder`，管理端触发时自动注入 `MS_FFMPEG` / `MS_MYSQL`（取 `qtools.media.ffmpeg` /
> `qtools.media.mysqlClient`，留空则不注入），但**不会**给你注入 `MYSQL_PWD`。
> 所以把按钮指向新脚本时，要在 `scanArgs` / `thumbArgs` 里带上 `--secret=<secret.yml 绝对路径>`
> （见下面第 4 节），或者让后端进程自己的环境变量里带 `MYSQL_PWD`。

`scan-media.js` 参数：

| 参数 | 说明 |
| --- | --- |
| `--root=<dir>` | **必填**，媒体根目录（只读遍历） |
| `--root-id=<n>` | 本次扫描写入 `media_video` / `media_photo` 的 `root_id`（多库根）。缺省取 `media_root` 里 `kind='root'` 的第一行；取不到就写 `NULL` 并 WARN（NULL = 后端用配置 `qtools.media.rootPath`）。必须是正整数 |
| `--db=` `--user=` `--host=` `--port=` | 默认 `qtools` / `root` / `127.0.0.1` / `3306` |
| `--mysql=<exe>` | mysql 客户端；默认从 PATH 找 `mysql`（找不到会提示显式传入） |
| `--secret=<file>` | 密码来源②：从该 YAML 取 `spring.datasource.druid.master.password`（只认 `master`，不会误取 `spring.data.redis.password` / `slave.password`）。优先级：`MYSQL_PWD` > `--secret` > 报错 |
| `--truncate` + `--yes` | 先 `DELETE FROM media_photo/media_album/media_video` 再插入；**只给 `--truncate` 不给 `--yes` 会直接报错退出，不写库** |
| `--dry-run` | 只统计并打印，不写库（不需要数据库，也不需要 mysql 客户端） |
| `--dump-sql=<file>` | 把生成的 SQL 写进该文件并打印前 20 行，**不执行**（可与 `--dry-run` 同用，用于审查 upsert 写法） |
| `--big-bytes=<n>` | `is_big=1` 的字节阈值，默认 `2097152`（2MB） |
| `--exclude=<a,b>` | 按目录名排除（任意层级），默认 `_媒体站,trash,.git,node_modules,thumbs,pv` |
| `--album-mode=dir|prefix` | 相册划分。`dir`（默认）= 一个目录一本册；`prefix` = 目录内再按文件名开头 `^(\d{1,4})[_\-\s]` 分组（兼容「001_xxx 一册一套」的旧库） |
| `--purge-unclaimed` | 把「本次没被任何分组认领 + 册内 0 图 + `dir_key` 落在本次产物里」的旧相册行连同 `media_album_person` 关联行一起删掉（同一事务）。不加它时一旦发现这类旧行就**报错退出、不写库**——不允许两套相册静默并存（详见 4.3） |
| `--no-hash` | 不算内容哈希：`content_hash` 写 NULL，同时关闭「改名/移动自动识别」 |
| `--hash-bytes=<n>` | oshash 每段字节数，默认 `65536`（64KB） |
| `--backfill-hash` | 老库升级用：给 `content_hash IS NULL` 的行补哈希（按 `--root` + `rel_path` 读文件），顺带补 `media_album.dir_key`；文件不存在只计数不删行 |
| `--limit=<n>` | 仅配 `--backfill-hash`：最多处理多少行 |
| `--help` | 参数说明 |

`build-thumbs.js` 参数：

| 参数 | 说明 |
| --- | --- |
| `--thumbs=<dir>` | **必填**，缩略图根（也认环境变量 `MS_THUMBS`） |
| `--root=<dir>` | 媒体根，用于定位源文件；默认取环境变量 `MS_MEDIA_ROOT`，再退到当前目录 |
| `--ffmpeg=<exe>` | 优先级：`--ffmpeg` > 环境变量 `MS_FFMPEG` > PATH |
| `--force` | 无条件重建全部产物（忽略 mtime 与存在性，慎用） |
| `--no-fresh` | 关掉新鲜度判断，退回改动前的旧语义「产物存在即跳过」；默认判断是「产物存在且 `mtime >= 源文件 mtime`」 |
| `--limit=<n>` | 只处理前 n 条（调试用） |
| `--secret=<file>` | 同 `scan-media.js`（管理端「重建缩略图」按钮没有 `MYSQL_PWD`，必须用它） |
| `--mysql=` `--db=` `--user=` `--host=` `--port=` | 同 `scan-media.js`；`--mysql` 也认 `MS_MYSQL` |

产物路径（相对 `--thumbs`，与 Java 端读取的约定一致）：

| 类型 | 命令 | 路径 |
| --- | --- | --- |
| 视频封面（P4 内容寻址） | `-ss 3 -i <src> -frames:v 1 -vf scale=480:-2 -q:v 4`（不足 3 秒自动回退 `-ss 0`） | `v/<content_hash>.jpg`；`content_hash` 为 NULL 时回退 `v/<videoId>.jpg`（老命名） |
| 图片缩略图（内容寻址） | `-i <src> -frames:v 1 -vf scale=400:-2 -q:v 4` | `p/<content_hash>.jpg`；`content_hash` 为 NULL 时回退 `p/<albumId>/<%04d>.jpg`（老命名） |
| 大图预览（仅 `is_big=1`，同一套规则） | `-i <src> -frames:v 1 -vf scale=1600:-2 -q:v 4` | `pv/<content_hash>.jpg`；`content_hash` 为 NULL 时回退 `pv/<albumId>/<%04d>.jpg` |

跳过条件（默认）：产物存在、非空，且产物 `mtime >= 源文件 mtime`。源文件被替换 / 重编码后 mtime 变新，
下一次运行会自动重建那一条；`--no-fresh` 退回「存在即跳过」，`--force` 全量重建。

服务端取封面时**先找 `<content_hash>.jpg`，找不到再回退 `<id>.jpg`**，对外 URL 仍是
`/api/media/thumb/v/<id>` —— 只在服务端内部换文件，前端零改动（`MediaFileServiceImpl.java:300-345`）。

### 2.1 照片缩略图按内容寻址（相册内顺序变化不再影响缩略图）

**问题（实测）**：缩略图以前按 `p/<albumId>/<%04d>.jpg` 落盘，`<%04d>` 就是册内 `seq`。相册里
插入一张字母序居中的新图，后面所有图的 `seq` 整体后移，旧缩略图文件名不动 —— 于是同一个
`0002.jpg` 先属于 A 图、后属于 B 图。用户端表现为「点开是这张、缩略图是另一张」，而且旧缩略图
是旧文件生成的，看起来像"图片不对"。真实库 a006 就踩过：`thumbs/p/a006/0002.jpg` 与
`0003.jpg` 字节数相同（25461）、mtime 不同，同一张图被两个 seq 共用。

**现在的规则**（与视频封面 P4 的做法一致，请一起看）：

| 项目 | 规则 |
| --- | --- |
| 图片缩略图 | `p/<content_hash>.jpg`（`content_hash` = `md5(size+首/中/尾64KB)` 前 16 位，改名 / 移动 / 相册内顺序变化都不变） |
| 大图预览 | `pv/<content_hash>.jpg`（同上，仅 `is_big=1`） |
| `content_hash` 为 NULL | 回退老命名 `p|pv/<albumId>/<%04d>.jpg`，并在启动时打一条 WARN（提示先跑 `--backfill-hash`）；**绝不**因此失败 |
| 服务端解析 | 先查 `p|pv/<content_hash>.jpg`，命中不了再回退 `p|pv/<albumId>/<%04d>.jpg`（`MediaFileServiceImpl#resolveThumbPhoto`） |
| URL | 两种写法都认：内容寻址 `/api/media/thumb/p/<content_hash>`、`/api/media/preview/p/<content_hash>`（推荐），老命名 `/api/media/thumb/p/<albumId>/<i>`、`/api/media/preview/p/<albumId>/<i>`（`<i>` 传序号或内容哈希）；管理端 `GET /api/media/admin/album/{id}/photos` 直接返回内容寻址的 `thumbUrl`/`previewUrl`，前端不必自己拼 |
| 缓存头 | **按「地址是否内容寻址」分级**：定位符是 16 位内容哈希 → `Cache-Control: public, max-age=31536000, immutable`；序号 / 视频 id 等非内容寻址 → `no-cache, must-revalidate`（配 ETag + Last-Modified，命中回 304）。以前一律 `max-age=604800`，序号 URL 重建后内容变而 URL 不变，浏览器 7 天继续用旧图 —— 这正是「管理端预览弹窗显示上一格的脸」的根因 |
| 旧文件 | **不删、不迁移**：老命名产物继续能命中（回退路径），新旧共存 |
| `--gc` | 一并扫 `p/`、`pv/` 顶层：文件名 stem 不是任何 `media_photo.content_hash` 的当孤儿；老命名的 `<albumId>/` 子目录**整棵不动** |

因此「相册内插入一张图」现在只影响 `seq`（用户端顺序 / 大图预览 URL 里的 i），**不再影响缩略图指向哪张图**。

**产物数 ≠ 行数（别把 `skip` 当「该建未建」）**：内容寻址后，多张「同一份内容」的行共用一个文件。
真实库实测：`media_photo` 9629 行 → `p/` 顶层 9184 个文件；`is_big=1` 的 470 行 → `pv/` 顶层 326 个文件。
所以 `TASK previews total=470 ok=0 skip=470 fail=0` 是**正常的增量结果**（326 个文件早就齐了，
470 行按 `content_hash` 去重后正好落在这 326 个文件上），不是「按老命名判新鲜」。判断「到底缺没缺」看汇总行：

```
DISTINCT photos rows=9629 files=9184 missing=0
DISTINCT previews rows=470 files=326 missing=0
```

`rows` 是库里的行数，`files` 是去重后应有的 `<content_hash>.jpg` 个数，`missing` 才是真缺文件。
另外 `isFresh()` 只 stat 内容寻址的那一个路径：`p|pv/<content_hash>.jpg` 不存在就重建，
老命名 `p|pv/<albumId>/<%04d>.jpg` 在不在都不参与判定（实测：删掉 `pv/<hash>.jpg`、保留
`pv/a004/0000.jpg`，下一次运行 `TASK previews ok=1 skip=469`，重建产物与删掉的那份字节完全相同）。

`/media/index` 与 `GET /api/media/album/{id}` 的每张图都带 `hash` 字段（= `content_hash`，未回填时整键不输出），
前端可以据此拼内容寻址 URL；不带也照样能用（服务端按序号解析）。

`enrich-meta.js` 参数（只补 `media_video` 的 `dur` / `w` / `h` / `vcodec`，其它列一概不动）：

| 参数 | 说明 |
| --- | --- |
| `--root=<dir>` | 媒体根，`rel_path` 相对它解析。优先级：`--root` > 环境变量 `MS_MEDIA_ROOT` > `media_root` 表里 `kind='root'` 的第一条 |
| `--ffprobe=<exe>` | 优先级：`--ffprobe` > 环境变量 `MS_FFPROBE` > PATH |
| `--mysql=<exe>` | 优先级：`--mysql` > 环境变量 `MS_MYSQL` > PATH |
| `--db=` `--user=` `--host=` `--port=` | 默认 `qtools` / `root` / `127.0.0.1` / `3306` |
| `--limit=<n>` | 最多检视前 n 条（调试用）；不给则处理全部待补 |
| `--dry-run` | 只调 ffprobe 打印 `DRY <rel_path> dur=.. w=.. h=.. vcodec=..`，不写库 |
| `--force` | 连已补齐的行也重探（默认待补集合 = 四列任一 NULL） |
| `--secret=<file>` | 同 `scan-media.js` |
| `--help` | 参数说明 |

待补集合与幂等：补过的行自动退出待补集合，第二次跑只计 `skip`。单条失败（文件没了 / 不是视频 /
ffprobe 报错）只打 `FAIL <rel_path>: <原因>` 并继续，**退出码仍是 0**；只有 ffprobe / mysql 不可用、
口令取不到这类致命错误才非 0。

输出行：`START total=<n> pending=<n> force=<0|1> dry=<0|1> root=<媒体根>` → `PROGRESS <examined>/<budget>`
→ `PENDING <n>`（跑完还剩多少待补）→ `enrich ok=<n> skip=<n> fail=<n> seconds=<n>`。

> **管理端按钮目前触发不了它（待实现）**：`MediaProperties` 里只有 `scanScript` / `thumbScript` /
> `scanArgs` / `thumbArgs`，没有 enrich 的配置项；`MediaScanServiceImpl` 也只注入 `MS_FFMPEG` / `MS_MYSQL`，
> 不注入 `MS_FFPROBE`。现阶段请手工执行（或先给后端进程的环境变量里配好 `MS_FFPROBE`）。

`export-bundle.js` / `import-bundle.js` 关键参数（便携包，P3）：

| 参数 | 脚本 | 说明 |
| --- | --- | --- |
| `--root=<dir>` | export | **必填**，只写进 `manifest.sourceRoot`，不遍历、不读文件 |
| `--out=<dir>` | export | **必填**，便携包输出目录（不存在则创建，同名文件会被覆盖） |
| `--dump=<exe>` | export | `mysqldump`（也可写 `--mysqldump=`）；默认从 PATH 或 mysql 客户端同目录找 |
| `--with-thumbs` / `--thumbs=<dir>` | export | 顺带复制缩略图到 `<out>/thumbs/`；`--thumbs` 不给则取 `media_root` 里 `kind='thumbs'` 的 path（给出即隐含 `--with-thumbs`） |
| `--with-stats` | export | 额外导出 `media_event` / `media_event_archive` / `media_stats_daily`（默认不导；`media_scan_job` 是运行时状态，永远不导） |
| `--in=<dir>` | import | **必填**，`export-bundle.js` 产出的目录（读 `manifest.json` / `media.sql.gz` / `user-state.json`） |
| `--create-schema` | import | 先 `CREATE DATABASE IF NOT EXISTS`，再 source `doc/media_schema.sql` 与 `doc/media_portability.sql` |
| `--merge-user-state` | import | 把 `user-state.json` 合并进 `media_owner` / `media_favorite` / `media_recent`（按 `content_hash` 解析身份） |
| `--force` | import | 目标库 `media_video` 已有行时的安全闸：不加就报错退出，加了才继续 |
| `--dry-run` | 两个 | export 只打印计划不跑 mysqldump；import 只校验便携包不建库不导入 |

语义两句：**媒体表是「表级替换」**（dump 自带 `DROP TABLE IF EXISTS`，重复导入行数不翻倍），
**用户资产是「合并」**（不删目标库已有行）；导入前校验 `manifest.json` 的 `schemaVersion === 1`，
不支持就明确报错退出（退出码 1）。`--db` 只允许字母 / 数字 / 下划线。

### 3. 注意事项

- **`--truncate` 是危险参数**：它清空三张表，管理端设置的 `hidden` 和 `media_favorite` / `media_recent`
  里的引用会一起失效。日常重扫用默认的 upsert（不覆盖 `hidden`、`person_id`、`note`、`dur/w/h/vcodec`）。
- **源目录只读**：`scan-media.js` 只 `readdirSync` / `statSync`；`build-thumbs.js` 只往 `--thumbs` 下写。
  不要把自己的媒体目录设成 `--thumbs` 的子目录之外的位置搞混（举例：把 `--thumbs` 指到媒体根内部的
  `_thumbs`，记得把 `_thumbs` 加进 `--exclude`）。
- **时序**：先 `scan-media.js`，再 `build-thumbs.js` 与 `enrich-meta.js`。视频封面 / 图片缩略图的
  文件名都来自库里的 `content_hash`（相册 / seq 只在哈希为 NULL 的老数据上才用），反过来做会
  找不到源、拿不到哈希。
- **多库根（`--root-id` / 接口参数 `r`）**：
  - 每个根在 `media_root` 里一行（`kind='root'`）；扫描时 `--root-id=` 决定写进
    `media_video.root_id` / `media_photo.root_id`。不给就取 `kind='root'` 的第一行，
    取不到写 `NULL` 并 WARN（`NULL` = 后端用配置 `qtools.media.rootPath`）。
  - 后端 `resolveMedia(rel, rootId)`：带 `rootId` 就只在该根里解析、越界一律 403；`rootId = null`
    时先试配置根，再依次试其它 `kind='root'` 的根（某个根没挂盘只是跳过，不报错）。
  - `GET /api/media/file` 与 `/media/sub` 都新增可选参数 `r=<media_root.id>`；
    前端现在还不传，所以存量行（`root_id IS NULL`）靠「配置根 + 多根兜底」也能播。
  - `GET /api/media/admin/config` 的 `media.roots[]` 是「配了几个根、哪个没挂上」的唯一可视入口。
  - **硬限制**：`uk_video_rel` 建在 `rel_path` 上、视频 `id = sha1(rel_path)[:12]`，
    所以**两个根的相对路径必须互不相同**。两个盘上都有 `写真/1.jpg` 这种同名相对路径时，
    后扫描的会覆盖先扫描的主表行（同名视频的 id 也会撞），这不是「支持同构镜像库」的功能。
- **重复文件别名（`media_video_alias` / `media_photo_alias`）**：
  - 建表：`mysql -uroot --default-character-set=utf8mb4 qtools -e "source doc/media_dup_alias.sql"`
    （纯新增表、可重复执行，`UNIQUE(content_hash, rel_path)`）。
  - 主表**保留先入库那一行**当主文件，其余同内容路径写别名表；重扫幂等：`(hash, rel_path)` 命中就只刷
    `root_id` / `size`，`create_time` 保留首次登记时间。
  - 范围：视频别名登记「同一次扫描内的副本」+「跨次扫描、两份文件都还在磁盘上的副本」；照片别名
    **只登记同一本相册内**的副本（同一张图出现在不同相册是合法的内容复用，不算重复）。
    判定规则见下面「4.1 同相册同内容副本的判定规则」。
  - 别名表没建时自动降级成老行为：打印 `DUP <hash> 保留=<path> 忽略=<path>`、`别名=0`，扫描不失败。
  - 本期后端**不读**别名表（只沉淀数据），所以「磁盘副本被登记了」≠「站点能看到它」。
- **重扫不会删行**：目录里删掉的图片靠「`seq >= 当前张数` 的残留行清理 + 孤儿图清理」处理；
  但如果整本相册目录被删，它的 `media_album` 行会留下（upsert 语义），要彻底对齐请用 `--truncate --yes`。
- **MySQL 版本**：脚本用 `INSERT ... AS n ON DUPLICATE KEY UPDATE`（8.0.19+），与 `import.js` 相同。
- **管理端「重建索引」按钮如何指向新脚本**（`qtools.media.*`，见 `qtools-media` 的 `MediaProperties`）：
  - `scanScript` → 本目录的 `scan-media.js`，并且 `scanArgs` 里必须给出 `--root=<媒体根>` 与
    `--secret=<secret.yml 绝对路径>`（Java 还会自己追加 `--out=<dir>`，新脚本会忽略不认识的参数，不会因此报错）。
  - `thumbScript` → 本目录的 `build-thumbs.js`，`thumbArgs` 里给出
    `--root=<媒体根> --thumbs=<缩略图根> --secret=<secret.yml 绝对路径>`
    （或改用环境变量 `MS_MEDIA_ROOT` / `MS_THUMBS`）。ffmpeg 走 `qtools.media.ffmpeg`、mysql 客户端走
    `qtools.media.mysqlClient`，管理端触发时自动以 `MS_FFMPEG` / `MS_MYSQL` 环境变量传进来，新脚本已支持。
  - 两个环境变量都只在配置非空时注入（留空 = 沿用脚本自己的 `PATH` 查找），脚本参数显式传 `--ffmpeg=` / `--mysql=` 仍优先。
  - 打印的行与 `MediaScanServiceImpl` 的正则一一对应：`videos=N albums=N photos=N people=N`、
    `条数 videos=N albums=N photos=N people=N`、`START … total=N`、`PROGRESS d/total`、
    `DONE ok=N skip=N fail=N`。改这些行之前先看那个类。
  - 缩略图任务有单条失败时退出码仍是 0（失败条目打在 `FAIL <rel_path>: <原因>` 里），
    只有 ffmpeg 缺失 / 读库失败等致命错误才非 0。
- **已知限制**：
  - `media_photo.w/h` 仍留 NULL：`enrich-meta.js` 只补视频，图片宽高**待实现**。
  - `people = 0`：通用扫描不建人物，`media_album.person_id` 留 NULL。
  - 缩略图串行生成（一条 ffmpeg 一个进程，1 万张会慢，但不会打满 CPU）。
  - `rel_path` 超过 700 字符的文件直接跳过并告警。
  - 依赖外部 `mysql` / `ffmpeg` / `ffprobe` / `mysqldump`；缺哪个都要用 `--mysql= --ffmpeg= --ffprobe= --dump=` 显式指定。
  - **封面命名与顺序**：封面文件按 `<content_hash>.jpg` 落盘，`media_video.poster_path` 由
    `scan-media.js` 写（`posterPathOf()`：有 `content_hash` 就写 `thumbs/v/<content_hash>.jpg`，
    没有才回落 `<id>.jpg`）。所以**先跑 `build-thumbs.js`，再让 `scan-media.js` 带上
    `--fix-poster-path`**（该模式只 UPDATE `poster_path` 一列、不扫盘、不需要 `--root`），
    存量行才会从旧的 `<id>.jpg` 改成 `<content_hash>.jpg` 并与磁盘文件对上；反序则指向还不存在的文件。
    旧的 `<id>.jpg` 文件不会被删除（派生资源 GC 仍待做）。
  - 别名表只沉淀不消费（后端待实现读别名）。
  - `enrich-meta.js` 已接入管理端按钮（`mode=enrich`，配置项 `enrichScript` / `enrichArgs`，并以
    `MS_FFPROBE` 注入 ffprobe），见下面第 6 节的 mode 一览表。

---

### 4. 接到管理端「重建索引」按钮（可选，不用改 Java）

管理端执行的是 `node <qtools.media.scanScript> <qtools.media.scanArgs>`（缩略图同理 `thumbScript` / `thumbArgs`），
所以只要把这四项配置指向新脚本并补全参数即可：

```yaml
qtools:
  media:
    # 扫描导入（--secret= 指向存数据库口令的私密配置，绝对路径）
    scanScript: '${qtools.home}/project/QTools/QTools/doc/media-import/scan-media.js'
    scanArgs: '--root=${qtools.home}/我的媒体 --db=qtools --host=127.0.0.1 --port=3307 --secret=${qtools.home}/project/QTools/QTools/qtools-admin/src/main/resources/application-secret.yml'   # 必须含 --root= 与 --secret=
    # 缩略图
    thumbScript: '${qtools.home}/project/QTools/QTools/doc/media-import/build-thumbs.js'
    thumbArgs: '--root=${qtools.home}/我的媒体 --thumbs=${qtools.home}/data/media/thumbs --secret=${qtools.home}/project/QTools/QTools/qtools-admin/src/main/resources/application-secret.yml'  # 必须含 --root=、--thumbs= 与 --secret=
```

几个必须知道的点：

- **参数由 Java 按空格拆词传入**：媒体根路径若含空格会拆错，请把媒体根放在无空格路径下，或直接手工执行脚本。
- 数据库口令**不经过命令行参数、也不会被脚本打印或落盘**：Java 不会注入 `MYSQL_PWD`，所以要么在
  `scanArgs` / `thumbArgs` 里加 `--secret=<secret.yml 绝对路径>`（脚本自己从
  `spring.datasource.druid.master.password` 读，只取 `master`，不会误取 redis 的口令），
  要么让后端进程的环境变量里带 `MYSQL_PWD`（优先级更高）。两者都没有时脚本会直接报错退出，不会拿空口令硬连。
- `--truncate` 会被 Java 侧过滤掉（管理端永远不做全量清空）；脚本自身还要求 `--truncate` 必须同时给 `--yes`。
- 无法识别的参数（例如旧脚本时代的 `--out=`、`--dir-mode=raw`）脚本只 `WARN` 忽略、不中断。
- ffmpeg / mysql 路径由 Java 通过环境变量 `MS_FFMPEG` / `MS_MYSQL` 传给脚本（优先级：`--ffmpeg=` > `MS_FFMPEG` > `PATH`、`--mysql=` > `MS_MYSQL` > `PATH`）；配了 `qtools.media.mysqlClient` 才注入 `MS_MYSQL`，留空时脚本自己找 `PATH`。
- 缩略图产物从 P4 起按 `v/<content_hash>.jpg` 落盘（本次起图片 / 大图预览同样按内容寻址：
  `p|pv/<content_hash>.jpg`）：老 `v/<id>.jpg`、`p|pv/<albumId>/<seq>.jpg` 都不会被删，服务端先找 hash 再回退老命名，
  所以「换脚本后老图还能显示」是设计如此，不是没生效——想确认的话看 `--thumbs` 下有没有新的 `v/<16位hex>.jpg`。
- **元数据补齐已接按钮**：`mode=enrich` 跑 `enrichScript`（默认就是本目录的 `enrich-meta.js`），
  ffprobe 走配置项 `qtools.media.ffprobe` → 环境变量 `MS_FFPROBE`；参数写在 `enrichArgs` 里
  （与 `scanArgs` 同理必须自带 `--root= --port= --secret=`）。逐字的参数清单见第 6 节。

### 4.1 同相册同内容副本的判定规则（跨扫描）

同一个 `content_hash` 在库里命中、但磁盘上的 `rel_path` 变了，只可能是两件事，**必须先看旧行
对应的文件还在不在磁盘上**（`scan-media.js` 的 `stillOnDisk()` + 「本次扫描看到的 rel_path 集合」）：

| 旧行对应的文件 | 事实 | 处理 |
| --- | --- | --- |
| **还在**磁盘上 | 同内容的两份真实副本（复制出来的那份） | **别名 + 主表新增一行**：照片两张各占一行（册内张数与磁盘一致），同时把这条路径写进 `media_photo_alias`；视频因 `uk_media_video_hash` 唯一键冲突，第二份只进 `media_video_alias`、主表保持先入库那条 |
| **已不在**磁盘上 | 改名 / 移动（或又一次"消失"） | **只改旧行**：`UPDATE rel_path / album_id / seq`，主键 `id` 不变、**不新增行、不产生幽灵行** |

细化到照片还有一条优先级：**同路径的旧行永远优先认领**（`rel_path` 完全一致的那行），
所以「只是册内序号变了」不会误判成副本。

「跨次扫描的同内容副本以前没被去重」正是 a006 那类幽灵行的另一半根因：老版本只看「同一次扫描内」，
第二次扫描遇到同哈希、不同路径时一律当移动，于是把先入库那行改走、把另一条路径彻底丢掉。
现在按上表分叉后，两种事实各走各的路。

### 4.2 缺失文件与 `--prune-missing`

磁盘上的文件被删掉（或整个目录被移走）之后，库里那行**不会**自动消失。以前它就这样一直留着，
用户端照旧显示（缩略图还是旧文件生成的），看起来像"图片不对"——所以要给「库里有、磁盘没有」
一个显式状态。

1. **建列**（一次性，幂等）：

   ```bash
   mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_missing.sql"
   ```

   加的是 `media_photo.missing` / `media_video.missing`（`TINYINT NOT NULL DEFAULT 0`）与
   `last_seen`（`DATETIME NULL`，最后一次在磁盘上看到它的扫描时间），外加
   `media_photo(album_id, missing)`、`media_video(missing)` 两个索引。没建列时扫描器只 WARN，
   生成的 SQL 与改动前逐字相同，扫描不会失败。

2. **每次真实扫描的收尾**都会比对：

   | 情况 | 动作 |
   | --- | --- |
   | 库里有、磁盘上没有 | `missing = 1`（`last_seen` 保持最后一次看到的时间，不刷新） |
   | 磁盘上又有了 | `missing = 0` + `last_seen = NOW()`（**复活**） |
   | 磁盘上还在 | upsert 顺手刷 `missing = 0, last_seen = NOW()` |

   判定范围按库根收：显式 `--root-id=N` 只比对 `root_id = N`（默认根额外含 `root_id IS NULL` 的存量行），
   绝不会拿 A 根的磁盘视角去标 B 根的行；落在 `--exclude` 目录里的行不参与判定。

   汇总行因此多两个键：`… 别名=<n> missing=<n> revived=<n>`（`missing` = 扫完仍缺失的行数，
   `revived` = 本次复活的条数）。dry-run / dump-sql 不写库，这两个键给 `-`。

3. **为什么"消失"的行会被挪到 `seq + 1000000`**：`media_photo` 的唯一键是 `(album_id, seq)`。
   文件没了、后面的图 `seq` 前移，残留行会挡住新图 —— 以前表现为「新图插不进去 / 旧行被覆盖成
   别的图」。现在收尾前先把它停泊到 `seq + PARK_BASE(1000000)`：行还在（`missing=1`、仍属于原相册，
   管理端能按相册列出来），册内真实序号槽位则全部腾空；它复活时会被当成「移动」按磁盘序号写回去。

4. **只标记，不删**（默认）。真删是显式动作：

   ```bash
   # 只打印 TOP 10 + 总数（不删）
   node scan-media.js --root='D:\我的媒体' --prune-missing

   # 确认后真删（删前仍会打印 TOP 10 与总数）
   node scan-media.js --root='D:\我的媒体' --prune-missing --yes
   ```

   删除是**硬删**而不是进回收站：这些行指向的文件在磁盘上本来就不存在，回收站里没有源文件可搬，
   软删只会留下「hidden=1 但源不存在」的僵尸行。安全闸：删除语句恒带 `where missing = 1`，
   扫描又把文件找回来（`missing = 0`）的行永远删不掉；视频的标签 / 演员 / 标记行一并清掉。

### 4.3 相册重复与 `--purge-unclaimed`（P5.43）

相册的「身份」只有 **`(dir_key, title)`** 一个键：`title` 是按目录名 / 文件名前缀**推导**出来的。
换 `--album-mode`、改过标题推导方式、或从旧站导入过的库里，同一个目录的同一套图会被推导成另一个
标题 —— `findAlbum` 命中不上，扫描就**新分配**一本相册、并按 `content_hash` 把图片行整批搬过去，
**旧相册行既不删、也不重算 `photo_count`**。用户端看到的就是：

- 同一套内容两条：一条有图（新行），一条点开是空的（旧行）；
- 页头「共 N 本相册 / M 张」取的是 `media_album` 的 `COUNT(*)` 与 `SUM(photo_count)`，
  旧行的过期计数一起被算进去，M 会远大于 `media_photo` 的真实行数。

现在脚本侧**不允许静默并存**：本次扫描没被任何分组认领、册内 0 图、`dir_key` 又落在本次产物里的
旧行会被列出来（日志里的 `STALE album …`），默认直接报错退出（不写库）；确认后用开关清理：

```bash
# 先看（dry-run 不写库，报错信息里会列出旧行）
node scan-media.js --root='D:\我的媒体' --album-mode=prefix --dry-run

# 认了：同一事务里删掉这些空壳（连同 media_album_person 关联行）
node scan-media.js --root='D:\我的媒体' --album-mode=prefix --purge-unclaimed
```

存量清理（不想跑扫描、或要「先预览再删」）用幂等 SQL `doc/media_album_dedupe.sql`：按「有没有图」
判定真相册 / 幽灵行 / 死行，先把 `person_id` 与人物关联交接给保留行再删幽灵，最后按 `media_photo`
实际行重算 `photo_count` / `bytes`；连跑两遍，第二遍全部 0 行变更。另外 `prefix` 模式在「某个目录
本次只分出一册」时直接用目录名做标题（不再出现 `1 (1)` 这种文件名噪音），`dir` / `prefix` 对这类
目录产出同一个标题，切模式不再造第二套行。

5. **管理端接口**（`media:admin:list` / `media:admin:edit`）：

   | 接口 | 说明 |
   | --- | --- |
   | `GET /api/media/admin/missing?kind=photo|video|all&albumId=&pageNum=&pageSize=` | 分页列出缺失行：`{rows:[{kind,id,albumId,seq,title,relPath,bytes,contentHash,missing,lastSeen,missingSince}], total}`（`missingSince` = 最后一次看到的时间，即缺失上界） |
   | `POST /api/media/admin/missing/prune` body `{kind, ids?:[...]}` 或 `{kind, all:true}` | 删除并返回 `{deleted, kind, all, photos, videos}` |
   | `GET /api/media/admin/album/{id}/photos` | **只读库**、不列磁盘：`[{id,seq,relPath,missing,bytes,w,h,thumbUrl,contentHash,lastSeen}]`，缺失行排最后 |
   | `GET /api/media/admin/photos?albumId=` | 旧的库内分页列表（同样带 `missing` / `contentHash`；旧前端回退用） |

   统计接口 `GET /api/media/admin/stats` 多三个数：`missingCount`（图片+视频）、`missingPhotos`、`missingVideos`。
   用户端 `/media/index?includeMissing=false`、`/media/album/{id}?includeMissing=false` 可以让服务端
   直接滤掉缺失行；默认 `true`（老行为，前端自行过滤）。

### 5. 常见坑（本机实测）

- **MySQL 端口**：本机 MySQL 在 `3307`（`application-druid.yml` 的默认值），而这五个脚本的
  `--port` 默认都是 `3306`（`scan-media.js:82`、`build-thumbs.js:46`、`enrich-meta.js:52`、`export-bundle.js:40`、`import-bundle.js:36`）。命令里一律显式写 `--port=3307`——别指望「连上了就是对的库」，
  3306 上如果还有另一个实例，你会得到「执行成功但数据不在站点上」。
- **node 不在 PATH**：`node` 被报「不是内部或外部命令」时用绝对路径，例如
  `D:\Tools\node\node.exe ...\scan-media.js --root=...`；管理端用的是 `qtools.media.nodePath`，同理。
- **口令只有两条渠道**：环境变量 `MYSQL_PWD`（优先）或 `--secret=<yml 绝对路径>`。**不要**把口令写进
  命令行参数（会进 shell 历史与进程列表），也**不要**把真实口令写进任何会被提交的文件；脚本自身不打印、不落盘，
  只通过子进程的 `MYSQL_PWD` 传给 `mysql`。
- **`MYSQL_PWD` 只对当前窗口有效**：PowerShell 里 `$env:MYSQL_PWD = '...'` 关掉窗口就没了，新窗口要重设。
- **工具不在 PATH**：`mysql` / `mysqldump` / `ffmpeg` / `ffprobe` 找不到时分别用
  `--mysql= --dump= --ffmpeg= --ffprobe=` 指定。`enrich-meta.js` 在 **ffprobe 不可用**时直接退出码 1
  （每一条都会失败，没有继续的意义），这一点和「单条失败只计数」不同。

### 6. 维护面板对应的 mode 一览表

管理端「维护 / 重建」面板只发一个接口：`POST /api/media/admin/scan`，body 里给 `mode` 和少量可选参数
（`limit` / `albumMode` / `exclude` / `noFresh` / `force` / `confirm`）。**所有子进程参数都由后端按 mode
从白名单拼装**（`MediaScanServiceImpl` 的 `buildCommand` 系列），前端传不进任意参数。

| mode | 脚本 | 后端追加的关键参数 | 超时（配置项 / 默认） |
| :--- | :--- | :--- | :--- |
| `scan` | `scan-media.js` | `--out=<scanOutPath>` + `scanArgs`；可选 `albumMode=dir\|prefix` → `--album-mode=`（**替换** `scanArgs` 里的同名参数）、`exclude=...` → `--exclude=` | `scanTimeoutMinutes` / 10 分钟 |
| `thumb` | `build-thumbs.js` | `thumbArgs`；可选 `noFresh=true` → `--no-fresh`、`force=true` → `--force`、`limit=N` → `--limit=N` | `thumbTimeoutMinutes` / 30 分钟 |
| `sprite` / `preview` / `sprite,preview` | `build-thumbs.js` | `thumbArgs` + `--sprite` / `--preview`（可组合）+ 可选 `limit=N` | `deriveTimeoutMinutes` / 720 分钟 |
| `gc` | `build-thumbs.js` | `thumbArgs` + `--gc --yes`；**必须** `confirm=true`，否则 400 | `deriveTimeoutMinutes` / 720 分钟 |
| `enrich` | `enrich-meta.js` | `enrichArgs`；可选 `limit=N` → `--limit=N` | `enrichTimeoutMinutes` / 120 分钟 |
| `backfill-hash` | `scan-media.js` | `--out=<scanOutPath>` + `scanArgs` + `--backfill-hash` | `maintainTimeoutMinutes` / 120 分钟 |
| `fix-poster` | `scan-media.js` | `--out=<scanOutPath>` + `scanArgs` + `--fix-poster-path` | `maintainTimeoutMinutes` / 120 分钟 |
| `export` | `export-bundle.js` | `exportArgs` + `--out=<exportPath>/<yyyyMMdd-HHmmss>`（目录由后端生成） | `exportTimeoutMinutes` / 60 分钟 |

参数规则（不合法一律 400，不静默忽略）：

- `albumMode` 只接受 `dir` / `prefix`，且只对 `scan` 生效；给了就整体替换 `scanArgs` 里的 `--album-mode=`
  （同一个参数出现两次时脚本只认先到的那次）。
- `exclude` 只允许目录名文本（字母数字 / CJK / `.` `_` `-` `,`），出现空格、引号、分号、`&`、`|`、`<`、`>`
  等一律 400；只对 `scan` 生效。
- `noFresh` / `force` 只对 `thumb` / `sprite` / `preview` 生效，且二者互斥（同时给 400）。
- `limit` 只对 `thumb` / `sprite` / `preview` / `enrich` 生效；`scan` / `gc` / `backfill-hash` /
  `fix-poster` / `export` 传了直接 400。
- 受管开关（`--force` / `--no-fresh` / `--sprite` / `--preview` / `--gc` / `--yes` / `--backfill-hash` /
  `--fix-poster-path` / `--out=` / `--limit=`）只能由后端追加：写进 `scanArgs` / `thumbArgs` /
  `enrichArgs` / `exportArgs` 会被过滤掉；`--truncate` 永远被过滤。

环境变量注入（配了才注入，留空 = 脚本自己从 `PATH` 找）：`MS_FFMPEG`（`qtools.media.ffmpeg`）、
`MS_FFPROBE`（`qtools.media.ffprobe`，`enrich-meta.js` 用）、`MS_MYSQL`（`qtools.media.mysqlClient`）。
注意 `export-bundle.js` 不认 `MS_MYSQL`，所以 `exportArgs` 里必须显式写 `--mysql=<mysql.exe>`
（`mysqldump` 默认从 mysql 客户端同目录找）。

### 7. 导出产物在哪、怎么下载

- 目录：`<qtools.media.exportPath>/<yyyyMMdd-HHmmss>/`，默认 `exportPath` 是
  `<qtools.home>/data/media/export`（见 `application.yml` 的 media 段）。目录名由后端生成，
  前端只能触发、不能指定写到哪儿。
- 一次导出的产物（与 `export-bundle.js` 一致）：`manifest.json`、`media.sql.gz`、`user-state.json`；
  带 `--with-thumbs` 时还有 `thumbs/` 子目录。
- 任务结束后 job 日志尾部有一行可解析的汇总（`GET /api/media/admin/scan/{jobId}` 的 `logTail` 里）：

  ```text
  EXPORT ok dir=E:/111/project/QTools/data/media/export/20261025-085445 files=3 bytes=231702 names=manifest.json,media.sql.gz,user-state.json
  ```

  失败时是 `EXPORT fail …`；此时 job 的 `total` / `done` 就是文件数。
- 列表：`GET /api/media/admin/export/list`（权限 `media:admin:list`）回最近 10 次导出目录 + 每目录
  文件清单与大小（子目录如 `thumbs/` 只给总字节）；每个文件带 `url` 字段可直接下载。
- 下载：`GET /api/media/admin/export/download?dir=<yyyyMMdd-HHmmss>&file=<名字>`，流式附件返回
  （`Content-Disposition: attachment`）。只允许 `dir` 匹配 `^[0-9]{8}-[0-9]{6}$`、`file` 匹配
  `^[A-Za-z0-9._-]+\.(sql|gz|json|zip)$`，且解析（含 `toRealPath()` 复核）后必须仍在 `exportPath`
  之内——越界 403、不存在 404、名字不合法 400。

---

### 8. 自动扫描（文件监听）与保存筛选

两个 P2 功能，都不改上面的导入命令；细节（全部字段与边界）见 `../自定义与迁移指南.md` 的 4.13 / 4.14。

**自动扫描（文件监听）**——默认关闭，打开后磁盘有变化会自动跑一次 `mode=scan`（等价「重建索引」按钮）。
它是**两层**配置，别再去找 yml 里的 `enabled`（已迁移，且改它不会生效）：

| 层 | 在哪 | 改完 | 管什么 |
| :--- | :--- | :--- | :--- |
| 部署层 | `application.yml` 的 `qtools.media.watch.*` | 需重启进程 | `allowRuntimeToggle`（默认 `true`；`false` = 封掉运行期开关，`PUT /watch/config` 403）、`debounceSeconds` / `quietMinutes` **仅兜底** |
| 运行期层 | `sys_config` 三键 / `PUT /api/media/admin/watch/config` | **立即生效** | `media.watchEnabled`（默认 `false`）、`media.watchDebounceSeconds`（默认 `5`，1-60）、`media.watchQuietMinutes`（默认空 = 不静默） |

建键（幂等，可反复执行）：

```bash
mysql -uroot --default-character-set=utf8mb4 -P3307 qtools -e "source doc/media_watch_config.sql"
```

打开 / 调参（不用重启）：

```bash
curl -X PUT 'http://localhost:8080/api/media/admin/watch/config' \
     -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
     -d '{"enabled":true,"debounceSeconds":5,"quietMinutes":"23:00-07:00"}'
```

- 监听 `media_root` 里所有 `kind='root'` 且存在的根，递归包含子目录（新建目录会即时注册）；
  忽略 `*.tmp` / `*.part` / `*.crdownload` / `~$*` 与 `_媒体站`。
- 同一时刻只跑 1 个任务：正在扫就只置 `pending`，扫完**补跑 1 次**。自动扫描的 job 日志首行是
  `AUTO 触发：<路径> 变化 N 个事件`，手工触发没有这一行。
- 管理端：`GET /api/media/admin/watch`（状态 `{allowed,enabled,running,paused,debounceSeconds,quietMinutes,roots,hint}`）、
  `PUT /api/media/admin/watch/config`（改配置，立即生效）、`POST /api/media/admin/watch/pause|resume`（临时暂停 / 恢复，不写配置）、
  `POST /api/media/admin/watch/scan-now`（立即扫一次，监听没开也能用）。
- 它只做 `scan`（建索引），不会顺带生成缩略图 / 元数据：仍按本文第 2 节的顺序补 `thumb` / `enrich`。

**保存筛选**——站端把筛选组合存成命名条目：

```bash
mysql -uroot --default-character-set=utf8mb4 -P3307 qtools -e "source doc/media_saved_filter.sql"
```

| 接口 | 权限 | 说明 |
| :--- | :--- | :--- |
| `GET /api/media/filters` | 匿名 | `[{id,name,params}]`：自己的 + 全局的 |
| `POST /api/media/filters` `{name,params}` | 匿名 + 编辑策略 | 新建或同名同 owner 覆盖；`name` 1-64、`params` 合法 JSON 且小于 4KB，否则 400 |
| `DELETE /api/media/filters/{id}` | 匿名 + 编辑策略 | 只删自己名下的（否则 404） |
| `GET` / `DELETE /api/media/admin/filters[/{id}]` | `media:admin:list` / `edit` | 全量清单 / 清理任一条 |

### 9. 人物与角色（P3-A）

扫盘脚本只写 `media_video_actor.name`（纯文本演员名）与 `media_album.person_id`（单选），
**不会**写人物字典。人物（`media_person`）与「视频 ↔ 人物 + 角色」「相册 ↔ 人物」的关联由
管理端手工维护，本节是把它跑起来需要的两条命令与端点。

```bash
# 幂等；必须在启动新后端之前跑（索引接口会查 media_album_person）
mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_person_tags.sql"
```

| 表 / 列 | 说明 |
| :--- | :--- |
| `media_person.alias / note / avatar_path / gender / birthday` | 别名 / 备注 / 头像路径 / 性别 / 生日（都可空） |
| `media_person.use_count` | 冗余计数 = 引用它的 `media_video_actor` 行数，写入时重算 |
| `media_video_actor.person_id` | 人物 id；**NULL = 扫盘脚本写的 name-only 历史行**（永不被全量替换清掉） |
| `media_video_actor.role` | 角色（主演 / 配角…），NULL = 未指定 |
| `uk_actor_video_person (video_id, person_id)` | 同一视频同一个人物只有一行 |
| `media_album_person (album_id, person_id)` | 相册 ↔ 人物多对多；`media_album.person_id` 退化为「第一个人物」镜像列 |

| 端点 | 说明 |
| :--- | :--- |
| `GET /api/media/admin/persons?q=&page=&size=` | 人物分页（带 videoCount / albumCount） |
| `POST /api/media/admin/persons` | 新建；name 1-64，重名 409 |
| `PUT /api/media/admin/persons/{id}` | 改名 / 改别名 / 备注 / 性别 / 生日 / 头像路径 |
| `DELETE /api/media/admin/persons/{id}` | 有引用时 400（带 videoCount / albumCount），先解绑再删 |
| `POST /api/media/admin/persons/{id}/avatar` `{path}` | 只存路径；上传走既有的 `POST /common/upload` |
| `PUT /api/media/admin/videos/{id}/persons` `{personIds, roles}` | 全量替换视频人物与角色 |
| `PUT /api/media/admin/albums/{id}/persons` `{personIds}` | 全量替换相册人物 |
| `GET /api/media/persons` · `GET /api/media/person/{id}` · `…/videos` · `…/albums` | 站端匿名只读（人物页 / 筛选器 / 详情分页） |

索引：`/api/media/index` 的 `videos[].persons:[{id,name,role}]` 与 `albums[].persons:[name]`
非空才输出。**扫盘不会自动把 name 里的人物对到字典**：需要一次性回填时手工跑一次

```sql
-- 可选：把「名字恰好等于人物名」的扫盘历史行绑定到字典（幂等）
UPDATE media_video_actor a JOIN media_person p ON p.name = a.name
   SET a.person_id = p.id
 WHERE a.person_id IS NULL;
```

### 10. 标签层级（父子）

`media_tag` 增加 `parent_id`（NULL = 顶层）+ 索引 `idx_tag_parent`，与人物共用一个迁移脚本（见上一节命令）。

- `GET /api/media/admin/tags` / `GET /api/media/tags` 每项带 `parentId` 与 `path`（如 `演员/日本/XX`）。
- `PUT /api/media/admin/tags/{id}` `{parentId}`：`null` = 置顶；`PATCH /tags/{id}` 仍管改名 / 改色（也认 `parentId`）。
- 自引用、成环、沿 parent 链超过 20 层 → 400（服务层校验，MySQL 8 的 CHECK 不能跨行查表）。
- **筛选语义**：站端索引接口没有服务端筛选（只有 `includeMissing`），筛选在前端做 —— 前端拿
  `parentId` 自建子树，选中父标签时把「自身 + 全部后代」的标签名一起匹配，等于命中所有后代标签的媒体；
  管理端 `GET /api/media/admin/videos?tagId=` 由服务端做同样的展开，是这条语义的权威实现。


### 11. 标记起止时间（P4-A）

时间点标记升级成「点 / 区间」两态，只加一列、不动老数据：

```bash
# 幂等；必须在启动新后端之前跑（标记读写都会 select end_pos）
mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_marker_end.sql"
```

- `media_video_marker.end_pos` DECIMAL(10,2) NULL：**NULL = 单点标记**（全部存量行都是这种，行为不变），
  非 NULL = 区间标记。
- 校验在服务层（DB 不加 CHECK）：`pos >= 0`；`endPos` 为 null 或 `pos < endPos <= dur`
  （`dur` 为 NULL 时只校验 `> pos`）；单视频标记 ≤ 100 条；非法一律 **400**。
- 端点：`POST /api/media/admin/video/{id}/markers` 的 body 多一个可选 `endPos`；
  `PUT /api/media/admin/video/{id}/meta` 的 `markers[]` 每项支持 `endPos`；站端
  `PUT /api/media/video/{id}/meta` 同步支持（受编辑策略闸门）。返回的 `markers[]` 每项恒带 `endPos`。
- 批量接口 `markerDelta.add` 仍是单点（同一条标记广播到整批视频，区间语义无意义）。

### 12. 重复文件合并：把别名表用起来（P4-A）

`doc/media_dup_alias.sql` 建的 `media_video_alias` / `media_photo_alias` 以前只沉淀、不读；
P4-A 起它是管理端「重复文件」页的数据源。

| 端点（前缀 `/api/media/admin`） | 说明 |
| :--- | :--- |
| `GET /duplicates?kind=video\|photo&page=&size=` | 按 `content_hash` 分组：`primary`（主表那一行）+ `aliases`（别名行）+ `count` + `bytesWasted` |
| `POST /duplicates/merge` `{kind,contentHash,deleteFiles}` | 删别名行；`deleteFiles=true` 时先把副本文件搬进回收站 |
| `POST /duplicates/keep` `{kind,contentHash,id}` | 把指定别名提升为主行指向的那份（交换 `rel_path`，主行 id 不变） |

- **只列「主行还在」的组**：别名表里 `content_hash` 在主表查不到的孤立行不会出现在列表里（也就 merge 不到），
  这类行来自「主文件被删、副本还在」的历史状态。
- **照片的 primary 选取**：`media_photo.content_hash` 不是唯一键（同一张图可以合法出现在多个相册），
  因此取「未隐藏优先、id 最小」那一行，列表与 keep 用同一口径。
- 判定口径与扫描脚本一致：照片别名只登记**同相册内**的副本；视频别名登记同 hash 的跨路径副本（见 `../media_dup_alias.sql` 与本文件 4.1 节）。

> ⚠️ **合并会移动文件到回收站**：`merge(deleteFiles=true)` 把副本文件搬到
> `_媒体站/trash/<kind>/<rootId>/`，条目名 `<别名 id>__<原名>`。这些条目在
> `GET /api/media/admin/trash` 里是 `orphan:true`（原路径未知，因为别名表不存恢复映射），
> 取回按 `trashRel` 手工搬回，`POST /admin/trash/restore` 对它们无效。
> `deleteFiles=false` 只删别名行、不动磁盘；重复 merge 幂等（第二次全 0，不报错）。

### 13. 评分改 0-100（P5-A）

`media_video.rating` 从 `TINYINT NULL`（0-10）扩到 **`SMALLINT NULL`（0-100）**，存量值统一 ×10。

```bash
# 幂等：只在列类型仍是 tinyint 时扩容 + ×10；跑完变 smallint，第二遍跳过（不会 ×100）
mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_rating_100.sql"
```

- 语义不变：`NULL` = 未评分、`0` = 有效最低分、`100` = 最高分。
- 越界（< 0 或 > 100）由服务层回 **400**：`PUT /admin/video/{id}/meta`、`POST /admin/videos/batch-meta`、
  站端 `PUT /api/media/video/{id}/meta` 走同一个 `checkRating`。
- `GET /api/media/index` 的 `videos[].rating` 仍是 0-100，未评分时整个键不输出。
- **导入脚本不写评分**（`scan-media.js` 只写路径 / 大小 / 时长），所以本迁移只影响「管理端打过分的行」。
- ⚠️ 前端必须同步改成 0-100，否则 80 分会显示成 80 颗星 / 越界。

### 14. 工作室 Studio（P5-A）

```bash
mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_studio.sql"
```

`media_studio`（name 唯一，含 `use_count` / `sort` / `hidden` / `avatar_path`）+ `media_video_studio`
（`PRIMARY KEY (video_id, studio_id)`）。管理端 `GET/POST/PUT/DELETE /api/media/admin/studios` 与
`PUT /api/media/admin/videos/{id}/studios` `{studioIds:[]}`（全量替换）；站端匿名只读
`GET /api/media/studios`、`GET /api/media/studio/{id}`、`GET /api/media/studio/{id}/videos`。
索引 `videos[].studios` 是工作室名数组，非空才输出。删工作室仍有引用时 400 并给计数。

### 15. 人物资料字段（P5-A）

```bash
mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_person_profile.sql"
```

`media_person` 追加 14 个全可空列：`measurements` / `career_start` / `career_end` / `tattoos` /
`piercings` / `ethnicity` / `hair_color` / `eye_color` / `height_cm` / `weight_kg` / `fake_boobs` /
`nationality` / `twitter` / `instagram`。管理端 `POST/PUT /api/media/admin/persons` 支持全部字段，
范围校验：身高 100-250、体重 30-200、年份 1900-2100 且 `career_end >= career_start`；站端
`GET /api/media/person/{id}` 返回已填写字段（空值不输出）。导入脚本不填这些字段（恒为空）。

### 16. 只生成「勾选的这几条」+ 逐条结果（P5.10-A）

管理端勾选若干视频后重生成派生资源，走的是同一支脚本，靠两个新参数收窄范围：

```bash
# ① 逗号分隔（--video 可重复传，多次叠加、去重）
node build-thumbs.js --thumbs=<缩略图根> --root=<媒体根> --sprite --preview \
  --video=id1,id2

# ② 从文件读（每行一个 id，空行与 # 注释忽略）：上千条时命令行会超长，用它绕开
node build-thumbs.js ... --ids-file=ids.txt

# ③ 只重建某几条的预览片段（配 --force 越过 mtime 判定）
node build-thumbs.js ... --preview --only=pvclips --force --video=id1,id2
```

- 给了 `--video` / `--ids-file` 就**只查这几行**（`WHERE id IN (...)`），并且**图片缩略图 / 大图一律不碰**；
- 库里查不到的 id 不算错：stderr 打 `WARN 视频不存在 <id>`，stdout 打一行 `ITEM ... result=skip reason=video-not-found`，并计入 `DONE skip=`；
- **逐条结果行**（机器可解析，管理端按它汇总）：

```
ITEM video=<videoId> task=<video|sprite|pvclip> result=<ok|skip|fail> out=<相对路径> seconds=<n> [reason=...]
```

  `out` 相对 `--thumbs`、正斜杠；失败行多一个 `reason=`（空白替换成 `_`）。图片任务不打这一行
  （它们的 id 是 albumId/seq，不在契约里）。
- `DONE` 行保持兼容，并在末尾多了已处理的 id 清单：`DONE ok=6 skip=7 fail=0 seconds=1 ids=id1,id2,id3,id4`
  （超过 200 个截断并追加 `truncated=1`；一个都没有时是 `ids=-`）。

管理端据此把「哪几条跑完了」写进任务日志的一行 `RESULT ok= skip= fail= ids= [failIds=]`，
`GET /api/media/admin/scan/{jobId}` 的视图里对应 `derive` 字段。接口契约见
`doc/媒体库派生资源状态.md`。

---

## 历史迁移：旧 Node 站 `index.json` → MySQL（`import.js`，保留）

> 下面这节是原作者把旧 Node 站导出的 `index.json` 迁到 MySQL 的说明，脚本是 `import.js` /
> `import-user.js`，**与上面的通用导入无关**，仅为历史迁移保留。

把 Node 版媒体站的索引 `E:\111\视频\_媒体站\data\index.json` 导入 MySQL `qtools` 库的
`media_*` 表（表结构见 `../media_schema.sql`，已建好、无需本脚本建表）。

零依赖（只用 Node 内置模块），不需要 `npm install`；不读也不写 `_媒体站` 之外的媒体文件，
对 `E:\111\视频` **只读**（`readFileSync` / `statSync` / `existsSync`，无任何写调用）。

## 快速开始

```powershell
# 首次导入（默认 upsert，可重复执行）
D:\Tools\Node\node.exe E:\111\project\QTools\QTools\doc\media-import\import.js

# 全量重建（先 DELETE FROM 再插入）
D:\Tools\Node\node.exe import.js --truncate

# 只看帮助
D:\Tools\Node\node.exe import.js --help
```

导入后自动校验，并写 `report.md`（条数 / 字节 / 用时 / 校验结果 / 样例行）。
**任一校验失败 → 退出码 1**，并在 stdout 打印期望值与实际值的差异。

## 密码

优先使用环境变量：

```powershell
$env:MYSQL_PWD = '<root 密码>'; node import.js
```

未设置时脚本会从 `E:\111\project\QTools\QTools\qtools-admin\src\main\resources\application-secret.yml`
的 `spring.datasource.druid.master.password` 读取，**只通过子进程的 `MYSQL_PWD` 环境变量传递**，
不打印、不落盘、不进入 `import.sql`。可用 `--secret=<yml>` 换路径。

## 参数

| 参数 | 说明 |
| --- | --- |
| `--truncate` | 先 `DELETE FROM`（注意：6 张表全清，`media_person.id` 会重新分配）再插入；默认按主键/唯一键 upsert |
| `--dir-mode=mark` \| `raw` | `media_album.dir` 的写入方式，见「已知限制 1」，默认 `mark` |
| `--skip-verify` | 只导入不校验（不写 report.md） |
| `--no-deep-bytes` | 跳过全量磁盘字节核对（默认开启，约多 0.6 s） |
| `--source=<file>` | 索引文件，默认 `E:\111\视频\_媒体站\data\index.json` |
| `--media-root=<dir>` | 媒体根（`rel_path` 的基准），默认 `E:\111\视频` |
| `--station-root=<dir>` | 站根（`poster` 的基准），默认 `E:\111\视频\_媒体站` |
| `--mysql=<exe>` | mysql 客户端，默认 `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe` |
| `--db=<name>` `--user=<name>` | 默认 `qtools` / `root` |
| `--out=<dir>` | `import.sql` 与 `report.md` 的输出目录，默认脚本所在目录 |

## 导入方式（为什么不是逐条 INSERT）

脚本在内存里生成一个 UTF-8 的 `import.sql`（多行 `INSERT ... VALUES (...),(...)`，默认每 400 行一条语句，
单事务提交），再用 `mysql --default-character-set=utf8mb4 -D qtools` 以 **stdin 重定向**喂进去。
9628 张图 + 684 标签的整库导入实测 ~300 ms，远低于 2 分钟预算。

字段值一律经 `sqlStr()` 转义（反斜杠、单引号、`\0`、`\n`、`\r`、`\Z`），不是字符串拼接 SQL 注入面。

## 表映射

| 源 | 目标 | 说明 |
| --- | --- | --- |
| `people[]` | `media_person` | `name` + `sort`（people 已按 count 降序，`sort` 即数组下标）；`未归类` 也建行 |
| `albums[]` | `media_album` | `id` 沿用 `a001…a156`；`person` 用 `name` 查 `media_person.id`（标量子查询）；`dir/photo_count/bytes/cover_path/note`；`sort` 为数组下标；`mtime` 留 NULL |
| `albums[].photos[]` | `media_photo` | `album_id` + `seq`（取数组下标，与 `i` 校验一致）+ `rel_path` + `is_big`；`bytes=0`、`w/h=NULL`（源里没有） |
| `videos[]` | `media_video` | `id/folder/rel_path/title/size/dur/w/h/vcodec/browser_ok/poster_path/doc_path/sub_path`；**`mtime` 取磁盘文件 mtime**（文件缺失则 NULL 并计入报告） |
| `videos[].tags[]` | `media_video_tag` | `(video_id, facet, name)` |
| `videos[].actors[]` | `media_video_actor` | `(video_id, name)` |

## 幂等语义

- `media_person` / `media_album` / `media_photo` / `media_video`：`INSERT ... AS n ON DUPLICATE KEY UPDATE`
  （按主键或唯一键 `uk_photo_album_seq` / `uk_video_rel` 命中）。
- **`media_video_tag` / `media_video_actor` 没有唯一键**，无法 upsert，因此每次导入都「先 `DELETE FROM` 再全量写入」。
- upsert 不覆盖 `hidden` 列，管理端隐藏状态在重跑后保留。
- 导入末尾会清理 `album_id` 已不存在的孤图，保证「每张 photo 都能找到相册」恒成立。
- 源数据**缩水**（相册变少、图片变少）时 upsert 不会删旧行 → 条数校验会报错，此时用 `--truncate`。

## 校验项（全部通过才算成功）

条数：`media_video=176`、`media_album=156`、`media_photo=9628`、`media_person=10`、`media_video_tag>0`（684）、
`media_video_actor>0`（17），并与 `index.json` 的 `stats` / 数组长度互相核对。

字节：`sum(media_video.size)` 与 `sum(media_album.bytes)` 先对齐 `index.json`（`stats` 里没有总字节字段，
所以用数组求和），再用**磁盘实际字节**独立核对一次（全量 stat 9628 张图 + 176 个视频）——
这样即使 `index.json` 自身的字节被改坏也能发现。

抽样：随机 20 张 photo 的 `rel_path` 在磁盘存在；随机 10 个 video 的 `rel_path` 存在且 `poster_path`
文件存在（poster 先按 `--station-root` 解析，再回落到 `--media-root`）。

关系：`media_album.person_id` 全部非空；`media_photo.album_id` 全部能在 `media_album` 找到。

只读：导入前后对 `E:\111\视频`、`_媒体站`、`_媒体站\data` 的 mtime 与 `index.json` 的 sha256 做快照比对，
不一致同样算失败。

## 已知限制

1. **`media_album.dir` 与 `UNIQUE KEY uk_album_dir (dir(200))` 冲突**：念念 144 套的物理目录都是
   `写真/念念 Nian Nian`（CONTRACT §2.2 规则 1：按文件名切套，不按子目录），144 行同一 `dir` 会被唯一键拒绝。
   默认 `--dir-mode=mark` 给这些行的 `dir` 追加 ` #<albumId>`（如 `写真/念念 Nian Nian #a007`），
   去掉 ` #aNNN` 后缀即可还原真实目录。若希望存原始 `dir`，需要先把唯一键放宽
   （例如 `UNIQUE KEY uk_album_dir (dir(200), id)`），然后 `--dir-mode=raw --truncate` 重跑。
   `--dir-mode=raw` 在当前表结构下会**在导入前直接报错退出**（不是写一半）——因为 `ON DUPLICATE KEY UPDATE`
   会把 `uk_album_dir` 冲突当成「同一本相册」并静默合并 144 行为 1 行，那是数据损坏而非可接受的失败。
2. `media_photo.bytes=0`、`w/h=NULL`：`index.json` 的 `photos[]` 只有 `rel/i/big`。校验阶段虽然 stat 了全部文件，
   但按契约不回填。
3. `media_album.mtime` 留 NULL（源数据没有相册级 mtime）。
4. `media_owner` / `media_favorite` / `media_recent` / `media_scan_job` 不在本次导入范围（用户数据迁移属 M2）。
5. 中文/emoji 依赖 `--default-character-set=utf8mb4`（脚本已固定该参数）+ 表 `DEFAULT CHARSET=utf8mb4`。

## 产物

| 文件 | 说明 |
| --- | --- |
| `import.js` | 迁移脚本本体 |
| `import.sql` | 每次运行生成的 SQL（可留档，也可直接 `mysql -u root --default-character-set=utf8mb4 -D qtools < import.sql`） |
| `report.md` | 最近一次导入的校验报告 |
| `run1-truncate.log` / `run2-upsert.log` | 最后一次自检（首轮 `--truncate` + 次轮 upsert）的原始输出 |
