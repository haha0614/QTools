# 媒体站 → QTools 数据迁移（M1）

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
