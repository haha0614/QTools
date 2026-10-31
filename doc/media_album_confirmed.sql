-- ============================================================================
-- P6.54  入册建议流：media_album.confirmed（册是采纳出来的，不是目录长出来的）
-- 文件：doc/media_album_confirmed.sql
--
-- 问题：扫描按「一个目录 = 一本册」直接建册，等于替人决定了「这算不算一册」。
--   审核状态（review_state）管的是「内容能不能上线」，管不到这件事：
--   一本按目录碎片长出来的册，审核通过了照样是垃圾。
--
-- 方案：加 confirmed 标记 —— 扫描新建的册默认 0（建议册），站端相册列表只认 1；
--   管理端能看到并「一键采纳」或并入别的册。
--   存量全部置 1：升级当天站端表现与之前**逐字相同**，不会有册突然消失。
--   扫描只在新插入时写 0，ON DUPLICATE KEY 不覆盖该列 —— 采纳过的册重扫不会退回建议态。
--
-- 性质：幂等；只加列 + 存量置 1。
--
-- 用法（必须在启新 jar 之前执行，否则相册列表会报 Unknown column）：
--   tools\mysql\bin\mysql.exe -uroot -h127.0.0.1 -P3307 --default-character-set=utf8mb4 ^
--     qtools < doc\media_album_confirmed.sql
--
-- 回滚：ALTER TABLE media_album DROP COLUMN confirmed;
-- ============================================================================
SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;

SELECT '0-1 相册总数' AS step, COUNT(*) AS v FROM media_album;
SELECT '0-2 已有 confirmed 列' AS step, COUNT(*) AS v
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_album' AND column_name = 'confirmed';

SET @has := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'media_album' AND column_name = 'confirmed');
SET @sql := IF(@has = 0,
  'ALTER TABLE media_album ADD COLUMN confirmed TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''册是否被采纳：1=已采纳（站端可见），0=扫描给的建议册'' AFTER hidden',
  'SELECT ''confirmed 已存在，跳过'' AS step');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量一律「已采纳」：升级当天站端行为不变
UPDATE media_album SET confirmed = 1 WHERE confirmed <> 1;

SELECT '1 建议册数（存量应为 0）' AS step, COUNT(*) AS v FROM media_album WHERE confirmed = 0;
SELECT '2 相册总数（应与 0-1 相同）' AS step, COUNT(*) AS v FROM media_album;
