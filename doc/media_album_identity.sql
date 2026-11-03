-- ============================================================================
-- P6.05  相册身份唯一化
-- 文件：doc/media_album_identity.sql
--
-- 要解决的问题（2026-10-29 实测踩到两次）：
--   相册身份原来只有 (dir_key, title) 一个键，而 title 是推导出来的：
--   · 换 --album-mode（dir ↔ prefix）→ 同一目录产出另一套标题，旧行既不复用也不删；
--   · 同目录多册靠前缀区分，但「目录里只分出一册」时标题会被改写成目录名，
--     于是「按标题认相册」在这些目录上根本对不上。
--   结果就是幽灵相册（a308：声明 9017 张、实际 0 行）和僵尸相册（a163：目录已删、行还在）。
--
-- 这次改动：
--   1) media_album 增加两列：
--        identity_key  相册在目录内的身份：'#<前缀>'（prefix 分册）或 '*'（整目录一册）
--        source        产生它的分册模式：prefix / dir
--   2) 建唯一索引 uk_album_identity (dir_key, identity_key)：
--      同一目录下同一个身份只能有一行 → 重复相册在库层面就写不进来。
--   3) 回填：按「册内第一张图（seq 最小）的文件名前缀」算身份，与 scan-media.js 的
--      PREFIX_RE = ^(\d{1,4})[_\-\s] 完全同口径；没有图的行退回按标题算。
--
-- 性质：幂等（连跑两遍，第二遍只打印「已存在」）；只加列 / 加索引 / 回填新列，
--       不改任一行已有字段，不动 media_photo。
--
-- 用法：
--   1) 先备份（必做）：
--      tools\mysql\bin\mysqldump.exe -uroot -h127.0.0.1 -P3307 --single-transaction ^
--        --set-gtid-purged=OFF --default-character-set=utf8mb4 qtools media_album ^
--        > tmp\backup\media_album_before_identity_YYYYMMDD.sql
--   2) 执行：
--      tools\mysql\bin\mysql.exe -uroot -h127.0.0.1 -P3307 --default-character-set=utf8mb4 ^
--        qtools < doc\media_album_identity.sql
--
-- 回滚（两列都是新增的，扫描器缺列时会自动降级为老行为）：
--   ALTER TABLE media_album DROP INDEX uk_album_identity;
--   ALTER TABLE media_album DROP COLUMN identity_key, DROP COLUMN source;
-- ============================================================================
SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;

-- ---------------------------------------------------------------- 0 现状快照
SELECT '0-1 相册总数' AS step, COUNT(*) AS v FROM media_album;
SELECT '0-2 已有 identity_key 列' AS step, COUNT(*) AS v
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_album' AND column_name = 'identity_key';

-- ---------------------------------------------------------------- 1 加列（幂等）
SET @has := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'media_album' AND column_name = 'identity_key');
SET @sql := IF(@has = 0,
  'ALTER TABLE media_album ADD COLUMN identity_key VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''册内身份：#<前缀>（prefix 分册）或 *（整目录一册）'' AFTER dir_key',
  'SELECT ''identity_key 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'media_album' AND column_name = 'source');
SET @sql := IF(@has = 0,
  'ALTER TABLE media_album ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT ''prefix'' COMMENT ''产生它的分册模式：prefix / dir'' AFTER identity_key',
  'SELECT ''source 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- 2 回填
-- 身份口径与 scan-media.js 一致：取册内 seq 最小的那张图，文件名 ^(\d{1,4})[_\-\s] 的前缀；
-- 没有前缀（或整册没有可用图）→ '*'。source 一律先记 prefix：现存相册都是 prefix 模式扫出来的
-- （dir 模式那几行已经清掉），后续扫描会按实际模式覆盖。
UPDATE media_album a
LEFT JOIN (
  SELECT album_id, SUBSTRING_INDEX(rel_path, '/', -1) AS base
  FROM (
    SELECT album_id, rel_path, ROW_NUMBER() OVER (PARTITION BY album_id ORDER BY seq) AS rn
    FROM media_photo WHERE missing = 0
  ) t
  WHERE rn = 1
) f ON f.album_id = a.id
SET a.identity_key = CASE
      WHEN f.base IS NOT NULL
        THEN COALESCE(CONCAT('#', REGEXP_SUBSTR(f.base, '^[0-9]{1,4}(?=[_ -])')), '*')
      WHEN a.title REGEXP '^[0-9]{1,4}[_ -]'
        THEN CONCAT('#', REGEXP_SUBSTR(a.title, '^[0-9]{1,4}(?=[_ -])'))
      ELSE '*'
    END
WHERE a.identity_key = '';

-- ---------------------------------------------------------------- 3 建索引前的冲突检查
-- 有输出说明同一 (dir_key, identity_key) 出现多行 —— 此时第 4 步会失败，先处理这些行：
-- 用 doc/media_album_dedupe.sql 清空壳，或人工合并。
SELECT '3-1 冲突组（必须为空）' AS step, dir_key, identity_key, COUNT(*) AS n,
       GROUP_CONCAT(id ORDER BY id) AS ids
  FROM media_album
 GROUP BY dir_key, identity_key
HAVING COUNT(*) > 1;

-- ---------------------------------------------------------------- 4 唯一索引（幂等）
SET @has := (SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = DATABASE() AND table_name = 'media_album' AND index_name = 'uk_album_identity');
SET @sql := IF(@has = 0,
  'ALTER TABLE media_album ADD UNIQUE KEY uk_album_identity (dir_key(191), identity_key)',
  'SELECT ''uk_album_identity 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- 5 复核
SELECT '5-1 相册总数' AS step, COUNT(*) AS v FROM media_album;
SELECT '5-2 身份为空的相册（应为 0）' AS step, COUNT(*) AS v FROM media_album WHERE identity_key = '';
SELECT '5-3 冲突组（应为 0）' AS step, COUNT(*) AS v
  FROM (SELECT dir_key, identity_key FROM media_album GROUP BY dir_key, identity_key HAVING COUNT(*) > 1) t;
SELECT '5-4 唯一索引' AS step, index_name, COUNT(*) AS cols
  FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'media_album' AND index_name = 'uk_album_identity'
 GROUP BY index_name;
SELECT '5-5 身份分布' AS step, LEFT(identity_key, 12) AS k, COUNT(*) AS v
  FROM media_album GROUP BY k ORDER BY v DESC LIMIT 5;
