-- =====================================================================
-- 多库根（M2-A）：回收站按 root_id 分桶 —— 索引补强
--
-- 背景：软删 / 恢复 / 彻底删 / 回收站列举改为「按记录的 root_id 选根」。
--   * 文件搬进 _媒体站/trash/<kind>/<rootId>/...（root_id 为 NULL 时沿用老结构
--     _媒体站/trash/<kind>/...，老回收站条目照旧可读、可恢复）；
--   * 相册表没有 root_id 列，所属根由「册内图片的 root_id」推断：
--         select p.root_id from media_photo p
--          where p.album_id = ? and p.root_id is not null
--          order by p.seq asc limit 1
--     这条查询按 (album_id, root_id) 过滤，本次给它补一个覆盖索引。
--
-- 特性：只加索引，不改任何既有列、不动数据；每步先查 information_schema，
--       已存在则跳过，可反复执行。
--
-- 前提：media_root 与 media_video/media_photo 的 root_id 列已由
--       doc/media_portability.sql 建立（本文件不建表、不加列）。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_trash_multiroot.sql"
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0) 辅助过程：索引不存在才添加（与 media_multiroot.sql 同名同签名）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_add_index;

DELIMITER $$

CREATE PROCEDURE qtools_add_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_columns VARCHAR(255), IN p_unique TINYINT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', IF(p_unique = 1, 'UNIQUE ', ''), 'INDEX `', p_index, '` (', p_columns, ')');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

-- ---------------------------------------------------------------------
-- 1) 相册所属根推断：media_photo(album_id, root_id)
--    （idx_photo_album 只有 album_id，推断时还要回表过滤 root_id）
-- ---------------------------------------------------------------------
CALL qtools_add_index('media_photo', 'idx_media_photo_album_root', '`album_id`,`root_id`', 0);

-- ---------------------------------------------------------------------
-- 2) 收尾：删除辅助过程
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_add_index;

-- ---------------------------------------------------------------------
-- 3) 自检：应看到 idx_media_photo_album_root；root_id 索引应已由
--    media_portability.sql / media_multiroot.sql 建好（这里只读不建）
-- ---------------------------------------------------------------------
SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('idx_media_photo_album_root', 'idx_media_video_root_rel',
                      'idx_media_photo_root_rel', 'idx_media_video_root', 'idx_media_photo_root')
 GROUP BY table_name, index_name, non_unique
 ORDER BY table_name, index_name;

-- ---------------------------------------------------------------------
-- 4) 回收站现状（只读）：库里有多少行的 root_id 非空——它们软删后会进
--    trash/<kind>/<rootId>/ 桶；NULL 行仍进老结构 trash/<kind>/
-- ---------------------------------------------------------------------
SELECT 'media_video' AS table_, COUNT(*) AS rows_, SUM(root_id IS NOT NULL) AS with_root_id
  FROM media_video
UNION ALL
SELECT 'media_photo' AS table_, COUNT(*) AS rows_, SUM(root_id IS NOT NULL) AS with_root_id
  FROM media_photo;
