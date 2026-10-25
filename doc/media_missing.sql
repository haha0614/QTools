-- =====================================================================
-- 媒体库「消失文件」标记（missing）—— 数据缺口修复 D1
--
-- 背景（真实故障）：相册 a006「小鱼」磁盘上 6 个文件 + 1 个 _说明.txt，库里也有
--   6 行，但其中一行指向 写真/小鱼/151169裁剪 - 副本.jpg（磁盘早已删除），两行
--   content_hash 相同（691cf4dc…）、bytes 相同（850463）。用户端因此显示 6 张，
--   其中一张是幽灵行：缩略图还是旧文件生成的，看起来"图片不对"。
--   原因：media_photo / media_video 没有「文件已消失」的概念，扫描器从不标记，
--   用户端与索引器也就无从过滤。
--
-- 本迁移只加"可观测状态"，不动任何既有列、不删任何行：
--   * missing   TINYINT NOT NULL DEFAULT 0 —— 1 = 本次扫描在磁盘上没找到该 rel_path；
--   * last_seen DATETIME NULL             —— 最后一次在磁盘上看到它的扫描时间
--                                            （= 缺失行的「最后 seen 时间」，管理端
--                                             GET /api/media/admin/missing 直接回显它）。
--   两列都由 doc/media-import/scan-media.js 在**每次真实扫描**的收尾阶段维护：
--   库里存在、磁盘不存在 -> missing=1；磁盘又有了 -> missing=0（复活）。
--   扫描器默认只标记不删除；确需清掉幽灵行时用 --prune-missing --yes（见 README）。
--
-- 幂等：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB 语法），
--       所以这里用 information_schema + PREPARE 逐条判断；反复执行不报错、不清数据。
--
-- 执行：
--     mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_missing.sql"
--   或（PowerShell / cmd 均可）：
--     mysql -uroot -P3307 qtools < doc/media_missing.sql
--   幂等重跑：
--     mysql ... qtools -e "source doc/media_missing.sql"
--
-- 适用：MySQL 8.0（脚本与仓库其它迁移一致，只用到 5.7 起就有的语法）
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;

-- ---------------------------------------------------------------------
-- 1) media_photo：missing + last_seen
-- ---------------------------------------------------------------------
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'missing') = 0,
  'ALTER TABLE media_photo ADD COLUMN missing TINYINT NOT NULL DEFAULT 0 COMMENT ''缺失标记：1=扫描时磁盘上不存在，0=正常'' AFTER hidden',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'last_seen') = 0,
  'ALTER TABLE media_photo ADD COLUMN last_seen DATETIME NULL DEFAULT NULL COMMENT ''最后一次在磁盘上看到该文件的扫描时间（缺失行即「最后 seen」）'' AFTER missing',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 管理端弹窗按相册看缺失（GET /api/media/admin/album/{id}/photos + /missing?kind=photo）
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND index_name = 'idx_photo_missing') = 0,
  'ALTER TABLE media_photo ADD KEY idx_photo_missing (album_id, missing)',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2) media_video：missing + last_seen + 索引
--    （视频没有 album_id，缺失列表按 missing 单列过滤）
-- ---------------------------------------------------------------------
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_video' AND column_name = 'missing') = 0,
  'ALTER TABLE media_video ADD COLUMN missing TINYINT NOT NULL DEFAULT 0 COMMENT ''缺失标记：1=扫描时磁盘上不存在，0=正常'' AFTER hidden',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_video' AND column_name = 'last_seen') = 0,
  'ALTER TABLE media_video ADD COLUMN last_seen DATETIME NULL DEFAULT NULL COMMENT ''最后一次在磁盘上看到该文件的扫描时间（缺失行即「最后 seen」）'' AFTER missing',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'media_video' AND index_name = 'idx_video_missing') = 0,
  'ALTER TABLE media_video ADD KEY idx_video_missing (missing)',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 3) 自检：应看到 2 行新列 + 2 行新索引
-- ---------------------------------------------------------------------
SELECT table_name, column_name, column_type, column_default
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name IN ('media_photo', 'media_video')
   AND column_name IN ('missing', 'last_seen')
 ORDER BY table_name, column_name;

SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('idx_photo_missing', 'idx_video_missing')
 GROUP BY table_name, index_name
 ORDER BY table_name;

SELECT 'media_photo' AS tbl, COUNT(*) AS rows_total, COALESCE(SUM(missing), 0) AS missing_rows FROM media_photo
UNION ALL
SELECT 'media_video', COUNT(*), COALESCE(SUM(missing), 0) FROM media_video;
