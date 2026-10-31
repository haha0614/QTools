-- ============================================================================
-- P6.52  允许无册（真原子化）：media_photo.album_id 改可空
-- 文件：doc/media_photo_null_album.sql
--
-- 问题：一张照片「最多属于一个系列（0..1）」，但 0 这一头一直不合法——
--   album_id 是 NOT NULL，于是「不属于任何册」只能靠隐藏/删行来假装，
--   管理端也没有「未归类」这个视图。
--
-- 方案：album_id 放开为空。空 = 这张图还没被策展进任何册（不是错误状态）。
--   配套：移出册时同时置 album_pinned = 1，扫描才不会按目录把它拽回去；
--   站端所有图片查询都按 album 走，NULL 行天然不出现（不需要额外过滤）。
--
-- 性质：幂等（已经是 NULL 就跳过）；不改任何一行数据。
--
-- 用法：
--   tools\mysql\bin\mysql.exe -uroot -h127.0.0.1 -P3307 --default-character-set=utf8mb4 ^
--     qtools < doc\media_photo_null_album.sql
--
-- 回滚（先把无册的照片挂回某本册，再改回 NOT NULL）：
--   ALTER TABLE media_photo MODIFY COLUMN album_id VARCHAR(16) NOT NULL COMMENT '所属相册 id';
-- ============================================================================
SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;

SELECT '0-1 照片总数' AS step, COUNT(*) AS v FROM media_photo;
SELECT '0-2 album_id 当前可空性' AS step, is_nullable AS v
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'album_id';

SET @nullable := (SELECT is_nullable FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'album_id');
SET @sql := IF(@nullable = 'NO',
  'ALTER TABLE media_photo MODIFY COLUMN album_id VARCHAR(16) NULL COMMENT ''所属相册 id；NULL = 未归类（人工归属，扫描不会拽回）''',
  'SELECT ''album_id 已经可空，跳过'' AS step');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '1 未归类照片数（迁移后应仍为 0）' AS step, COUNT(*) AS v FROM media_photo WHERE album_id IS NULL;
SELECT '2 照片总数（应与 0-1 相同）' AS step, COUNT(*) AS v FROM media_photo;
