-- ============================================================================
-- P6.51  人工归属固化（media_photo.album_pinned）
-- 文件：doc/media_photo_pinned.sql
--
-- 问题：扫描按「一个目录 = 一本册」工作。管理端「拆/移」把某几张图改挂到别的册之后，
--   下次扫描照着目录又把它们拽回去——scan-media.js 的 moves 分支
--   （prior.albumId !== 扫描册 → UPDATE album_id/seq）就是这个动作。
--   于是「册是解释」这条规则实际上不成立，人工策展活不过一次重扫。
--
-- 方案：加一列 album_pinned，标记「这张图的归属是人定的」。
--   1 = 管理端拆/移过：扫描不改它的 album_id/seq（文件改名仍跟着改 rel_path）；
--   0 = 默认：扫描照旧按目录归属，与改动前逐字相同。
--   存量全部 0 —— 这一列本身不改变任何现有数据，也不改变现有扫描行为。
--
-- 配套的扫描器改动（P6.51，都在 doc/media-import/scan-media.js）：
--   · planPhotos：pinned 行直接跳过，不进 moves；
--   · 第 6 步 moves 的 UPDATE 对 pinned 行只改 rel_path，不碰 album_id/seq；
--   · 第 7 步「seq >= 磁盘张数」的残留清理加 AND album_pinned = 0
--     （搬进来的行 seq 排在末尾，否则会被当成残留删掉）；
--   · 收尾对「含 pinned 行的册」按库内实际行重算 photo_count/bytes
--     （相册 upsert 写的是磁盘张数，会让搬入方与搬出方同时算错）。
--
-- 性质：幂等（连跑两遍，第二遍只打印「已存在」）；只加列，不改任何已有行。
--
-- 用法：
--   tools\mysql\bin\mysql.exe -uroot -h127.0.0.1 -P3307 --default-character-set=utf8mb4 ^
--     qtools < doc\media_photo_pinned.sql
--
-- 回滚（脚本缺列时自动降级为老行为，回滚只需删列）：
--   ALTER TABLE media_photo DROP COLUMN album_pinned;
-- ============================================================================
SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;

-- ---------------------------------------------------------------- 0 现状快照
SELECT '0-1 照片总数' AS step, COUNT(*) AS v FROM media_photo;
SELECT '0-2 已有 album_pinned 列' AS step, COUNT(*) AS v
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'album_pinned';

-- ---------------------------------------------------------------- 1 加列（幂等）
SET @has := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'media_photo' AND column_name = 'album_pinned');
SET @sql := IF(@has = 0,
  'ALTER TABLE media_photo ADD COLUMN album_pinned TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''归属是否由人工决定：1=管理端拆/移过，扫描不再按目录改 album_id/seq'' AFTER hidden',
  'SELECT ''album_pinned 已存在，跳过'' AS step');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- 2 结果核对
SELECT '1 人工归属行数（迁移后应仍为 0）' AS step, COUNT(*) AS v FROM media_photo WHERE album_pinned = 1;
SELECT '2 照片总数（应与 0-1 相同）' AS step, COUNT(*) AS v FROM media_photo;
