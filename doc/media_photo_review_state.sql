-- ============================================================================
-- P6.56  照片级审核状态：media_photo.review_state（NULL = 继承所属相册）
-- 文件：doc/media_photo_review_state.sql
--
-- 问题：「内容审核」原来只有册/视频两级，照片一律继承册。于是两个洞：
--   1) 已上站的册里，某几张不适合展示的图只能靠「分级更严」这种曲线办法压住，没有"这张驳回"；
--   2) 把照片移入/并入一本已上站（approved + confirmed）的册时，审核触发器只覆盖 INSERT
--      （trg_media_photo_after_insert），UPDATE album_id 不触发 —— 这些图**未经审核就上线了**。
--
-- 方案：加一列 NULL 默认的 review_state，语义与 content_rating 完全对称：
--   NULL     = 继承所属相册（默认，绝大多数照片都是这个值）
--   pending  = 这张要单独看（例如刚被移进一本已上站的册）
--   approved = 单独放行
--   rejected = 单独驳回，永不展示
--   照片只能比册更严格：册没过，照片的 approved 也不会让它上线。
--
-- 性质：幂等；只加列 + 加索引，存量的 9745 行全是 NULL（行为与升级前逐字相同）。
-- 部署：迁移必须先于新 jar（相册图片列表会读到这一列）。
-- 回滚：ALTER TABLE media_photo DROP COLUMN review_state;
-- ============================================================================
SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;

SELECT '0-1 照片总数' AS step, COUNT(*) AS v FROM media_photo;
SELECT '0-2 已有 review_state 列' AS step, COUNT(*) AS v
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'review_state';

SET @has := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'review_state');
SET @sql := IF(@has = 0,
  'ALTER TABLE media_photo ADD COLUMN review_state VARCHAR(16) NULL DEFAULT NULL COMMENT ''照片自身的审核状态；NULL=继承所属相册（只能更严）'' AFTER content_rating',
  'SELECT ''review_state 已存在，跳过'' AS step');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @hasIdx := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND index_name = 'idx_photo_review');
SET @sql2 := IF(@hasIdx = 0,
  'CREATE INDEX idx_photo_review ON media_photo (review_state)',
  'SELECT ''idx_photo_review 已存在，跳过'' AS step');
PREPARE stmt2 FROM @sql2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

SELECT '1 显式设了照片级状态的张数（升级后应为 0）' AS step, COUNT(*) AS v
  FROM media_photo WHERE review_state IS NOT NULL;
SELECT '2 照片总数（应与 0-1 相同）' AS step, COUNT(*) AS v FROM media_photo;
