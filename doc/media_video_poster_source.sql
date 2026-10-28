-- ============================================================================
-- media_video 封面来源：poster_source / poster_file（P6.00）
-- ----------------------------------------------------------------------------
-- 背景（用户原话）：「我刚上传的库根里面，有些视频是自带封面的，一般就在同目录下，
--   命名方式为"视频名-封面"，这个情况有考虑到吗，这个封面是不是也不应该搞到相册里面展示」。
--   现状：① 扫描器把目录里的独立图片一律当相册照片入库，于是「视频名-封面.jpg」变成
--   相册里的一张照片（一个只含封面的单图相册，重复又难看）；② 视频海报只来自
--   ffmpeg 截帧（thumbs/v/<content_hash>.jpg），现成的封面文件没被利用。
--
-- 本文件只做两件小事（纯加列，不改任何现有行）：
--   poster_source  VARCHAR(8) NOT NULL DEFAULT 'frame'
--       frame  = 自动截帧（历史行为，默认值就是它 → 存量行原地变成 frame，语义不变）
--       file   = 同目录「视频名-封面」自带封面（scan-media.js 识别）
--       manual = 手动指定（当前无 UI 写入，先占位；重扫不会覆盖它）
--   poster_file    VARCHAR(700) NULL
--       自带封面相对媒体根的路径（正斜杠）；仅在 poster_source='file' 时有值。
--       为什么要这一列：poster_path 必须继续是「派生产物」路径（thumbs/v/<key>.jpg，
--       后端 /api/media/thumb/v/<id> 按内容寻址落盘文件），所以源封面文件得另存一处，
--       build-thumbs.js 才能不重跑一遍命名规则就知道拿哪张图派生。
--
-- 幂等：两列都用 information_schema 判定后再 ALTER，重复执行只打印「已存在」。
--
-- 用法（PowerShell 里先 $env:MYSQL_PWD='<root 密码>'）：
--   mysql -h127.0.0.1 -P3307 -uroot --default-character-set=utf8mb4 -t -D qtools
--          < QTools/doc/media_video_poster_source.sql
--
-- 顺序提醒：先跑本文件，再让 scan-media.js 识别 / build-thumbs.js 派生。
--   旧列缺失时两个脚本都会整体降级（不写这两列、按 ffmpeg 截帧走老路），不会报错。
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------- 1) poster_source
SET @has_source := (SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'media_video'
                       AND column_name = 'poster_source');

SET @ddl := IF(@has_source = 0,
  'ALTER TABLE media_video ADD COLUMN poster_source VARCHAR(8) NOT NULL DEFAULT ''frame'' COMMENT ''封面来源：frame 截帧 / file 自带封面 / manual 手动''',
  'SELECT ''poster_source 已存在，跳过'' AS info');
PREPARE st FROM @ddl;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------- 2) poster_file
SET @has_file := (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'media_video'
                     AND column_name = 'poster_file');

SET @ddl := IF(@has_file = 0,
  'ALTER TABLE media_video ADD COLUMN poster_file VARCHAR(700) NULL DEFAULT NULL COMMENT ''自带封面文件（相对媒体根、正斜杠）；仅 poster_source=file 时有值''',
  'SELECT ''poster_file 已存在，跳过'' AS info');
PREPARE st FROM @ddl;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------- 3) 自检
-- 判据：frame 应等于「视频总数」（存量全部按历史行为落为 frame），file 为 0
--       （自带封面要等 scan-media.js 识别，或 --detect-covers 纠正存量）
SELECT COUNT(*) AS `视频总数`
     , SUM(poster_source = 'frame')  AS `frame`
     , SUM(poster_source = 'file')   AS `file`
     , SUM(poster_source = 'manual') AS `manual`
     , SUM(poster_file IS NOT NULL)  AS `有封面文件`
  FROM media_video;
