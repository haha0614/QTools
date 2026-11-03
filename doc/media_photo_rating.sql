-- ============================================================================
-- P6.09  原子级分级：media_photo.content_rating
-- 文件：doc/media_photo_rating.sql
--
-- 现状：分级只在「视频」和「相册」两级，册内所有图片继承相册那一档。
-- 需求（用户反馈）：一本册里混着少量不能外露的图时，只能整册抬高分级，代价太大。
--
-- 语义（重要）：
--   content_rating IS NULL  → 这张图继承相册分级（默认，99% 的图都是这样）
--   content_rating 有值      → 这张图**只会更严**，不会更松：
--                              相册收紧 → 图不可见；图自身收紧 → 图也不可见。
--   即访客可见 = 库根可见 ∧ 相册档可见 ∧ (图自身档可见 ∨ 图自身未设置)
--   不允许「相册是限制级、某张图改成大众级就放出来」——那等于绕过整册。
--
-- 性质：幂等；只加一列 + 一个索引，不动任何既有数据。
--
-- 回滚：
--   ALTER TABLE media_photo DROP INDEX idx_photo_content_rating;
--   ALTER TABLE media_photo DROP COLUMN content_rating;
-- ============================================================================
SET NAMES utf8mb4;

SET @has := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'content_rating');
SET @sql := IF(@has = 0,
  'ALTER TABLE media_photo ADD COLUMN content_rating VARCHAR(16) NULL DEFAULT NULL COMMENT ''图片自身分级；NULL=继承相册；有值时只会更严'' AFTER content_hash',
  'SELECT ''content_rating 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND index_name = 'idx_photo_content_rating');
SET @sql := IF(@has = 0,
  'ALTER TABLE media_photo ADD INDEX idx_photo_content_rating (content_rating)',
  'SELECT ''idx_photo_content_rating 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '0-1 已有单独分级的图片数（应为 0）' AS step, COUNT(*) AS v
  FROM media_photo WHERE content_rating IS NOT NULL;
