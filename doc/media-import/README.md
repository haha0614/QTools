# 媒体站 → QTools 数据迁移（M1）

## 换成你自己的媒体内容（通用导入）

两个零依赖脚本（只用 Node 内置模块 + 外部 `mysql` / `ffmpeg`），把**任意媒体目录**变成媒体站内容，
不需要旧 Node 站的 `index.json`：

| 脚本 | 做什么 | 产物 |
| --- | --- | --- |
| `scan-media.js` | 只读遍历媒体根，把目录结构写进 `media_video` / `media_album` / `media_photo` | 数据库里的行 |
| `build-thumbs.js` | 读库，用 ffmpeg 生成视频封面 / 图片缩略图 / 大图预览 | `<thumbs>/v`、`<thumbs>/p`、`<thumbs>/pv` |

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
| `media_video.poster_path` | `thumbs/v/<id>.jpg` |
| `media_album.id` | `a` + 3 位序号，目录相对路径按自然序递增（`a001`、`a002`…） |
| `media_album.title` / `dir` | 目录名 / 目录相对路径（根目录散图：title 取根目录名，dir 为空串） |
| `media_album.photo_count` / `bytes` / `cover_path` | 该目录图片张数 / 总字节 / `thumbs/p/<albumId>/0000.jpg` |
| `media_photo.seq` | 目录内文件名**自然序**（数字感知）从 0 递增 |
| `media_photo.is_big` | `bytes >= --big-bytes` |
| `people` | 恒为 0（通用扫描不建人物，`media_album.person_id` 留 NULL） |

### 2. 两条命令

```powershell
# 0) 密码只从环境变量读（不打印、不落盘）
$env:MYSQL_PWD = '<root 密码>'

# 1) 先扫描导入（默认 upsert，可重复执行；--dry-run 只统计不写库）
node E:\111\project\QTools\QTools\doc\media-import\scan-media.js --root='D:\我的媒体'

# 2) 再生成缩略图（先导入后缩略图，顺序不能反：它按库里的 id/相册/seq 决定产物路径）
node E:\111\project\QTools\QTools\doc\media-import\build-thumbs.js --root='D:\我的媒体' --thumbs='D:\我的媒体\_thumbs'
```

`scan-media.js` 参数：

| 参数 | 说明 |
| --- | --- |
| `--root=<dir>` | **必填**，媒体根目录（只读遍历） |
| `--db=` `--user=` `--host=` `--port=` | 默认 `qtools` / `root` / `127.0.0.1` / `3306` |
| `--mysql=<exe>` | mysql 客户端；默认从 PATH 找 `mysql`（找不到会提示显式传入） |
| `--truncate` + `--yes` | 先 `DELETE FROM media_photo/media_album/media_video` 再插入；**只给 `--truncate` 不给 `--yes` 会直接报错退出，不写库** |
| `--dry-run` | 只统计并打印，不写库（不需要数据库，也不需要 mysql 客户端） |
| `--dump-sql=<file>` | 把生成的 SQL 写进该文件并打印前 20 行，**不执行**（可与 `--dry-run` 同用，用于审查 upsert 写法） |
| `--big-bytes=<n>` | `is_big=1` 的字节阈值，默认 `2097152`（2MB） |
| `--exclude=<a,b>` | 按目录名排除（任意层级），默认 `_媒体站,trash,.git,node_modules,thumbs,pv` |
| `--help` | 参数说明 |

`build-thumbs.js` 参数：

| 参数 | 说明 |
| --- | --- |
| `--thumbs=<dir>` | **必填**，缩略图根（也认环境变量 `MS_THUMBS`） |
| `--root=<dir>` | 媒体根，用于定位源文件；默认取环境变量 `MS_MEDIA_ROOT`，再退到当前目录 |
| `--ffmpeg=<exe>` | 优先级：`--ffmpeg` > 环境变量 `MS_FFMPEG` > PATH |
| `--force` | 重建已有产物（默认「存在且非空就跳过」的增量） |
| `--limit=<n>` | 只处理前 n 条（调试用） |
| `--mysql=` `--db=` `--user=` `--host=` `--port=` | 同 `scan-media.js` |

产物路径（相对 `--thumbs`，与 Java 端读取的约定一致）：

| 类型 | 命令 | 路径 |
| --- | --- | --- |
| 视频封面 | `-ss 3 -i <src> -frames:v 1 -vf scale=480:-2 -q:v 4`（不足 3 秒自动回退 `-ss 0`） | `v/<videoId>.jpg` |
| 图片缩略图 | `-i <src> -frames:v 1 -vf scale=400:-2 -q:v 4` | `p/<albumId>/<%04d>.jpg` |
| 大图预览（仅 `is_big=1`） | `-i <src> -frames:v 1 -vf scale=1600:-2 -q:v 4` | `pv/<albumId>/<%04d>.jpg` |

### 3. 注意事项

- **`--truncate` 是危险参数**：它清空三张表，管理端设置的 `hidden` 和 `media_favorite` / `media_recent`
  里的引用会一起失效。日常重扫用默认的 upsert（不覆盖 `hidden`、`person_id`、`note`、`dur/w/h/vcodec`）。
- **源目录只读**：`scan-media.js` 只 `readdirSync` / `statSync`；`build-thumbs.js` 只往 `--thumbs` 下写。
  不要把自己的媒体目录设成 `--thumbs` 的子目录之外的位置搞混（举例：把 `--thumbs` 指到媒体根内部的
  `_thumbs`，记得把 `_thumbs` 加进 `--exclude`）。
- **时序**：先 `scan-media.js` 后 `build-thumbs.js`。缩略图文件名里的 id / 相册 / seq 全部来自库，
  反过来做会找不到源。
- **重扫不会删行**：目录里删掉的图片靠「`seq >= 当前张数` 的残留行清理 + 孤儿图清理」处理；
  但如果整本相册目录被删，它的 `media_album` 行会留下（upsert 语义），要彻底对齐请用 `--truncate --yes`。
- **MySQL 版本**：脚本用 `INSERT ... AS n ON DUPLICATE KEY UPDATE`（8.0.19+），与 `import.js` 相同。
- **管理端「重建索引」按钮如何指向新脚本**（`qtools.media.*`，见 `qtools-media` 的 `MediaProperties`）：
  - `scanScript` → 本目录的 `scan-media.js`，并且 `scanArgs` 里必须给出 `--root=<媒体根>`
    （Java 还会自己追加 `--out=<dir>`，新脚本会忽略不认识的参数，不会因此报错）。
  - `thumbScript` → 本目录的 `build-thumbs.js`，`thumbArgs` 里给出 `--root=<媒体根> --thumbs=<缩略图根>`
    （或改用环境变量 `MS_MEDIA_ROOT` / `MS_THUMBS`）。ffmpeg 走 `qtools.media.ffmpeg`，Java 会以
    `MS_FFMPEG` 环境变量传进来，新脚本已支持。
  - 打印的行与 `MediaScanServiceImpl` 的正则一一对应：`videos=N albums=N photos=N people=N`、
    `条数 videos=N albums=N photos=N people=N`、`START … total=N`、`PROGRESS d/total`、
    `DONE ok=N skip=N fail=N`。改这些行之前先看那个类。
  - 缩略图任务有单条失败时退出码仍是 0（失败条目打在 `FAIL <rel_path>: <原因>` 里），
    只有 ffmpeg 缺失 / 读库失败等致命错误才非 0。
- **已知限制**：`dur / w / h / vcodec` 留 NULL（不跑 ffprobe），`media_photo.w/h` 也留 NULL；
  `people = 0`；缩略图串行生成（一条 ffmpeg 一个进程，1 万张会慢，但不会打满 CPU）；
  `rel_path` 超过 700 字符的文件直接跳过并告警；依赖外部 `mysql` 与 `ffmpeg`。

---

### 4. 接到管理端「重建索引」按钮（可选，不用改 Java）

管理端执行的是 `node <qtools.media.scanScript> <qtools.media.scanArgs>`（缩略图同理 `thumbScript` / `thumbArgs`），
所以只要把这四项配置指向新脚本并补全参数即可：

```yaml
qtools:
  media:
    # 扫描导入
    scanScript: '${qtools.home}/project/QTools/QTools/doc/media-import/scan-media.js'
    scanArgs: '--root=${qtools.home}/我的媒体 --db=qtools --host=127.0.0.1 --port=3307'   # 必须含 --root=
    # 缩略图
    thumbScript: '${qtools.home}/project/QTools/QTools/doc/media-import/build-thumbs.js'
    thumbArgs: '--root=${qtools.home}/我的媒体 --thumbs=${qtools.home}/data/media/thumbs'  # 必须含 --root= 与 --thumbs=
```

几个必须知道的点：

- **参数由 Java 按空格拆词传入**：媒体根路径若含空格会拆错，请把媒体根放在无空格路径下，或直接手工执行脚本。
- 数据库口令**不在参数里**：Java 会从 `secret.yml` 读出并注入子进程的 `MYSQL_PWD`（与旧脚本一致）。
- `--truncate` 会被 Java 侧过滤掉（管理端永远不做全量清空）；脚本自身还要求 `--truncate` 必须同时给 `--yes`。
- 无法识别的参数（例如旧脚本时代的 `--out=`、`--dir-mode=raw`）脚本只 `WARN` 忽略、不中断。
- ffmpeg 路径由 Java 通过环境变量 `MS_FFMPEG` 传给缩略图脚本（优先级：`--ffmpeg=` > `MS_FFMPEG` > `PATH`）。

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
