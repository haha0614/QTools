# 媒体站 → QTools 数据迁移报告（M1）

- 生成时间：2026-10-21 10:44:21
- 源数据：`E:\111\视频\_媒体站\data\index.json`（built=2026-10-17T14:09:42+08:00）
- 目标库：`qtools` 表 `media_person / media_album / media_photo / media_video / media_video_tag / media_video_actor`
- 模式：`--truncate`（先 DELETE 再插入），`--dir-mode=raw`

## 1. 用时

| 阶段 | 毫秒 |
| --- | ---: |
| 生成 SQL | 19 |
| mysql 导入 | 335 |
| 校验 | 730 |
| **总计** | **1149** |

## 2. 条数

| 表 | 行数 | 源期望 |
| --- | ---: | ---: |
| media_person | 10 | 10 |
| media_album | 156 | 156 |
| media_photo | 9627 | 9627 |
| media_video | 176 | 176 |
| media_video_tag | 684 | 684 |
| media_video_actor | 17 | 17 |

## 3. 字节

| 项 | 值 |
| --- | ---: |
| sum(media_video.size) | 111,647,487,347 |
| index.json sum(videos[].size) | 111,647,487,347 |
| sum(media_album.bytes) | 29,756,299,110 |
| index.json sum(albums[].bytes) | 29,756,299,110 |

## 4. 校验

结果：**全部通过 ✅**

| 校验项 | 期望 | 实际 | 结果 |
| --- | --- | --- | :---: |
| videos 条数 | 176 | 176 | ✅ |
| albums 条数 | 156 | 156 | ✅ |
| photos 条数 | 9627 | 9627 | ✅ |
| photos 条数 = sum(album.photo_count) | 9627 | 9627 | ✅ |
| people 条数 | 10 | 10 | ✅ |
| video_tag 条数 > 0 且等于源 | 684 | 684 | ✅ |
| video_actor 条数 > 0 且等于源 | 17 | 17 | ✅ |
| sum(media_video.size) 对齐 index.json | 111647487347 | 111647487347 | ✅ |
| sum(media_album.bytes) 对齐 index.json | 29756299110 | 29756299110 | ✅ |
| 所有 album.person_id 非空 | 0 | 0 | ✅ |
| 所有 photo.album_id 都能找到相册 | 0 | 0 | ✅ |
| 随机 20 张 photo 的 rel_path 在磁盘存在 | 20/20 存在 | 20/20 存在 | ✅ |
| 随机 10 个 video 的 rel_path 在磁盘存在 | 10/10 存在 | 10/10 存在 | ✅ |
| 随机 10 个 video 的 poster 文件存在 | 10/10 存在 | 10/10 存在 | ✅ |
| photo 全量 rel_path 在磁盘存在 | 9627 条 / 0 缺失 | 9627 条 / 0 缺失 | ✅ |
| sum(media_album.bytes) = 磁盘图片实际字节 | 29756299110 | 29756299110 | ✅ |
| 每本相册 bytes = 该册图片磁盘字节 | 0 本不符 | 0 本不符 | ✅ |
| video 全量 rel_path 在磁盘存在 | 176 条 / 0 缺失 | 176 条 / 0 缺失 | ✅ |
| 每部视频 size = 磁盘文件大小 | 0 部不符 | 0 部不符 | ✅ |

抽样明细：

- photo `写真/念念 Nian Nian/043_喵少女_0019.webp`
- photo `写真/念念 Nian Nian/016_沐浴女子_0064.webp`
- photo `写真/念念 Nian Nian/038_春日_0029.webp`
- photo `写真/念念 Nian Nian/044_春风_0047.webp`
- photo `写真/念念 Nian Nian/009_背带裤_0059.webp`
- video `写真/写真_YeonWoo_泳装浴室_4K_11m.mp4` → poster `thumbs/v/a33aa6d40c53.jpg`
- video `动漫/动漫_双人_穴_3m.mp4` → poster `thumbs/v/f5933ff272d7.jpg`
- video `FRAC_桌面互动壁纸/media/look-sit-06.webm` → poster `thumbs/v/fbcbf4f871fb.jpg`

## 5. 分布

人物（相册数）：念念 144 / Machi马吉 3 / 未归类 2 / 唐翩翩 1 / 喵小吉 Aeolian-2 1 / 小鱼 1 / 是小逗逗 1 / 桜井宁宁 1 / 梨霜儿 1 / 玥儿玥er 1

分类（视频数）：FRAC_桌面互动壁纸 28 / ASMR 25 / 写真 23 / 跳舞 22 / 词曲 15 / nana 14 / 动漫 14 / 自慰 13 / 战斗 11 / 广告 7 / 合集 4

## 6. 样例行

| 表 | 字段 |
| --- | --- |
| media_person | id=111 name=念念 sort=0；id=112 name=Machi马吉 sort=1；id=113 name=未归类 sort=2 |
| media_album | id=a001 person_id=112 title=【Machi马吉】10月无压🤍逆バニーMachi🤍（194P新） dir=`写真/【Machi马吉】10月无压🤍逆バニーMachi🤍（194P新）` photo_count=194 bytes=14257532029 cover=thumbs/p/a001/0000.jpg sort=0 |
| media_photo | id=105909 album_id=a001 seq=0 rel_path=`写真/【Machi马吉】10月无压🤍逆バニーMachi🤍（194P新）/1 (1).jpg` bytes=0 w/h=NULL/NULL is_big=1 |
| media_video | id=00b0143dfc10 folder=ASMR title=ASMR_直播_3D形象_126m size=2930832989 dur=7551.60 w/h=1920/1080 vcodec=h264 browser_ok=1 poster=thumbs/v/00b0143dfc10.jpg doc=ASMR/ASMR_直播_3D形象_126m.txt sub=ASMR/ASMR_直播_3D形象_126m.vtt mtime=2025-07-17 03:51:54 |

## 7. 媒体根只读证据

脚本对 `E:\111\视频` 与 `E:\111\视频\_媒体站` 只做 `readFileSync` / `statSync` / `existsSync`，无任何写调用。
导入前后快照一致=**是 ✅**

| 路径 | 导入前 | 导入后 |
| --- | --- | --- |
| E:\111\视频 | mtime=1792230229272.2915 | mtime=1792230229272.2915 |
| E:\111\视频\_媒体站 | mtime=1792547776200.9685 | mtime=1792547776200.9685 |
| E:\111\视频\_媒体站\data | mtime=1792550659753.1096 | mtime=1792550659753.1096 |
| E:\111\视频\_媒体站\data\index.json | sha256=73acc55188ede23f | sha256=73acc55188ede23f |

## 8. 已知限制

- `media_photo.bytes=0`、`w/h=NULL`：index.json 的 `photos[]` 只有 `rel/i/big`，按契约留空。校验阶段已 stat 全部 9628 个文件核对相册字节，但**不回填**到 `media_photo.bytes`。
- `media_photo` 的图片尺寸在 index.json 里不存在，因此不做逐图 ffprobe（仅 `big` 标记可用）。
- `media_video_tag` / `media_video_actor` 无唯一键，每次导入都是「先清空再全量写入」，因此这两张表不支持 upsert 语义。
- upsert 模式不覆盖 `hidden` 字段（管理端隐藏状态在重跑后保留）；源数据缩水时请用 `--truncate`。

