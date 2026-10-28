-- =====================================================================
-- P5.99 · 内容分级（大众 / 家长指导 / 限制级）+ 分级维度的未登录可见
--
-- 目标：
--   1) media_video / media_album 各加一列 content_rating，给**单条**视频 / 相册分级；
--   2) 新增 media_rating_policy：每个分级的「未登录可见」开关（guest_visible）；
--   3) 给 media_photo.rel_path 补一个前缀索引：未登录访客走 /media/file?p= 直连原图时，
--      要按「这张图属于哪本相册 → 那本相册的分级」判可见性（没有索引就是 1 万行全表扫）。
--
-- 值域（与后端 MediaRating 一处定义对齐，非法值接口回 400）：
--   general    = 大众级
--   guidance   = 家长指导级
--   restricted = 限制级
--
-- 默认值全部是 general / guest_visible=1：老库执行完本迁移后行为**零变化**，
-- 只有站长在管理端显式设过分级 / 关过开关之后才收紧。
--
-- 幂等：每一步都先查 information_schema 再决定执行 ALTER 还是 DO 0，可反复执行。
--       表不存在（还没跑 media_schema.sql / media_portability.sql）时同样跳过。
--
-- 执行：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_content_rating.sql"
--   （本机 MySQL 在 3307，加 -h127.0.0.1 -P3307 -p…）
--
-- 生效面（后端统一在 IMediaGuestAccessService / MediaIndexServiceImpl 两个点实现）：
--   GET  /api/media/index                  —— 索引里整行筛掉 + ratings[] 只给可见分级
--   GET  /api/media/video/{id}、/album/{id} —— 未登录且该分级不可见 → 404
--   GET  /api/media/{thumb|frame|preview|sprite|stream|file}
--   GET  /api/media/persons、/person/{id}   —— 名下没有可见作品的人物整条不出现
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1) media_video.content_rating
-- ---------------------------------------------------------------------
SET @has_table = (SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'media_video');
SET @has_col   = (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'media_video'
                     AND column_name = 'content_rating');

SET @ddl = IF(@has_table = 1 AND @has_col = 0,
  'ALTER TABLE `media_video` ADD COLUMN `content_rating` VARCHAR(16) NOT NULL DEFAULT ''general'' COMMENT ''内容分级：general 大众 / guidance 家长指导 / restricted 限制级'' AFTER `rating`',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2) media_album.content_rating
-- ---------------------------------------------------------------------
SET @has_table = (SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'media_album');
SET @has_col   = (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'media_album'
                     AND column_name = 'content_rating');

SET @ddl = IF(@has_table = 1 AND @has_col = 0,
  'ALTER TABLE `media_album` ADD COLUMN `content_rating` VARCHAR(16) NOT NULL DEFAULT ''general'' COMMENT ''内容分级：general 大众 / guidance 家长指导 / restricted 限制级'' AFTER `note`',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 3) 两个分级索引（管理端按分级筛 / 索引按分级筛都要用；MySQL 不支持 ADD INDEX IF NOT EXISTS，先查 statistics）
-- ---------------------------------------------------------------------
SET @has_idx = (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'media_video'
                   AND index_name = 'idx_video_content_rating');
SET @ddl = IF(@has_idx = 0,
  'ALTER TABLE `media_video` ADD INDEX `idx_video_content_rating` (`content_rating`)',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx = (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'media_album'
                   AND index_name = 'idx_album_content_rating');
SET @ddl = IF(@has_idx = 0,
  'ALTER TABLE `media_album` ADD INDEX `idx_album_content_rating` (`content_rating`)',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 4) media_rating_policy：每个分级的「未登录可见」开关
--    guest_visible=1：未登录可见（默认）
--    guest_visible=0：仅登录可见 —— 未登录访客的索引里不出现该分级的任何条目，
--                     直连（按 id / hash / 路径）一律 404，分级筛选里也不出现这一档。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `media_rating_policy` (
  `rating`        VARCHAR(16)  NOT NULL COMMENT '分级值：general / guidance / restricted',
  `guest_visible` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=未登录可见，0=仅登录可见',
  `note`          VARCHAR(255) DEFAULT NULL COMMENT '备注（给人看的，不参与判定）',
  PRIMARY KEY (`rating`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容分级的未登录可见策略（P5.99）';

-- ---------------------------------------------------------------------
-- 5) 预置三行：默认都未登录可见（老库行为不变）
--    INSERT IGNORE：重复执行不会覆盖站长已经改过的值
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `media_rating_policy` (`rating`, `guest_visible`, `note`) VALUES
  ('general',    1, '大众级'),
  ('guidance',   1, '家长指导级'),
  ('restricted', 1, '限制级');

-- ---------------------------------------------------------------------
-- 6) media_photo.rel_path 前缀索引（仅当列存在且索引不存在时补）
--    用途：未登录访客 /media/file|stream|sub?p=<相对路径> 的可见性判定要按图反查相册分级
-- ---------------------------------------------------------------------
SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'media_photo'
                   AND column_name = 'rel_path');
SET @has_idx = (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'media_photo'
                   AND index_name = 'idx_photo_rel');
SET @ddl = IF(@has_col = 1 AND @has_idx = 0,
  'ALTER TABLE `media_photo` ADD INDEX `idx_photo_rel` (`rel_path`(200))',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 7) 自检（只读）：两列 + 策略表三行
-- ---------------------------------------------------------------------
SELECT table_name, column_name, column_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND ((table_name = 'media_video'  AND column_name = 'content_rating')
     OR (table_name = 'media_album'  AND column_name = 'content_rating'))
 ORDER BY table_name;

SELECT rating, guest_visible, note FROM media_rating_policy ORDER BY rating;

-- 8) 现有内容的分级分布（只读，供确认迁移后全部落在 general 档）
SELECT 'video' AS kind, content_rating, COUNT(*) AS cnt FROM media_video GROUP BY content_rating
UNION ALL
SELECT 'album' AS kind, content_rating, COUNT(*) AS cnt FROM media_album GROUP BY content_rating
ORDER BY kind, content_rating;
