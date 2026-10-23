# 媒体站 → QTools 数据迁移（M1）

## 换成你自己的媒体内容（通用导入）

五个零依赖脚本（只用 Node 内置模块 + 外部 `mysql` / `ffmpeg` / `ffprobe` / `mysqldump`），把**任意媒体目录**变成媒体站内容，
不需要旧 Node 站的 `index.json`：

| 脚本 | 做什么（一句话） | 关键参数 |
| --- | --- | --- |
| `scan-media.js` | 只读遍历媒体根，把目录结构（含内容哈希、多库根、重复副本别名）写进 `media_video` / `media_album` / `media_photo`（别名进 `media_*_alias`） | `--root=`（必填）、`--root-id=`、`--db= --user= --host= --port=`、`--secret=`、`--dry-run`、`--truncate --yes`、`--album-mode=`、`--backfill-hash` |
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
> `ProcessBuilder`（缩略图模式只额外注入 `MS_FFMPEG`），**不会**给你注入 `MYSQL_PWD`。
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
| 图片缩略图 | `-i <src> -frames:v 1 -vf scale=400:-2 -q:v 4` | `p/<albumId>/<%04d>.jpg` |
| 大图预览（仅 `is_big=1`） | `-i <src> -frames:v 1 -vf scale=1600:-2 -q:v 4` | `pv/<albumId>/<%04d>.jpg` |

跳过条件（默认）：产物存在、非空，且产物 `mtime >= 源文件 mtime`。源文件被替换 / 重编码后 mtime 变新，
下一次运行会自动重建那一条；`--no-fresh` 退回「存在即跳过」，`--force` 全量重建。

服务端取封面时**先找 `<content_hash>.jpg`，找不到再回退 `<id>.jpg`**，对外 URL 仍是
`/api/media/thumb/v/<id>` —— 只在服务端内部换文件，前端零改动（`MediaFileServiceImpl.java:300-345`）。

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
> `scanArgs` / `thumbArgs`，没有 enrich 的配置项；`MediaScanServiceImpl` 也只注入 `MS_FFMPEG`，
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
- **时序**：先 `scan-media.js`，再 `build-thumbs.js` 与 `enrich-meta.js`。缩略图文件名里的
  id / 相册 / seq 全部来自库，封面文件名还来自 `content_hash`，反过来做会找不到源、拿不到哈希。
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
  - 范围：视频别名**只登记同一次扫描内**发现的副本（跨次扫描的同内容靠 `content_hash` 认「改名/移动」，
    不是别名）；照片别名**只登记同一本相册内**的副本（同一张图出现在不同相册是合法的内容复用，不算重复）。
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
    （或改用环境变量 `MS_MEDIA_ROOT` / `MS_THUMBS`）。ffmpeg 走 `qtools.media.ffmpeg`，Java 会以
    `MS_FFMPEG` 环境变量传进来，新脚本已支持。
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
  - **P4 遗留**：`media_video.poster_path` 仍写旧命名 `thumbs/v/<id>.jpg`（封面文件本身已按
    `<content_hash>.jpg` 落盘），两者不一致**待实现**统一；旧的 `<id>.jpg` 目前**没有回收**
    （派生资源 GC 待做）。
  - 别名表只沉淀不消费（后端待实现读别名）；`enrich-meta.js` 还没接入管理端按钮（没有 `enrichScript`
    配置项，也没有 `MS_FFPROBE` 注入）。

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
- ffmpeg 路径由 Java 通过环境变量 `MS_FFMPEG` 传给缩略图脚本（优先级：`--ffmpeg=` > `MS_FFMPEG` > `PATH`）。
- 缩略图产物从 P4 起按 `v/<content_hash>.jpg` 落盘：老 `v/<id>.jpg` 不会被删，服务端先找 hash 再回退 id，
  所以「换脚本后老图还能显示」是设计如此，不是没生效——想确认的话看 `--thumbs` 下有没有新的 `v/<16位hex>.jpg`。
- **`enrich-meta.js` 暂时没有对应的管理端配置项（待实现）**：`MediaProperties` 只有 `scanScript` /
  `thumbScript` / `scanArgs` / `thumbArgs`，且 `MediaScanServiceImpl` 只注入 `MS_FFMPEG`。
  要用 ffprobe 补元数据，现阶段手工跑（见第 2 节第 3 步）。

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
