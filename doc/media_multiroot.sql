-- =====================================================================
-- 多库根（M1）：索引补强
--
-- 前提：media_root 表与 media_video/media_photo 的 root_id 列已由
--       doc/media_portability.sql 建立（前两项：CALL qtools_create_root_table /
--       qtools_add_column 'root_id'）。本文件**只补索引**，不改任何既有列、不动数据。
--
-- 为什么需要：
--   * 扫描器 --root-id 缺省时要取「kind='root' 的第一行」→ media_root(kind, id)；
--   * 后端按 root_id 选根、按根内 rel_path 定位 → (root_id, rel_path) 组合索引，
--     避免多根后 rel_path 扫描退化成全表。root_id 为 NULL 的存量行仍走原有索引，
--     因为 (root_id, rel_path) 的前导列是 NULL 时 MySQL 用不到它，但也不会更慢。
--
-- 幂等：每步先查 information_schema.statistics，已存在则跳过；可反复执行。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_multiroot.sql"
--
-- 注意：本文件不创建 media_root（那属于 media_portability.sql 的职责），
--       若两者顺序错，第一条 CALL 会因为表不存在直接报错（这是有意的 fail-fast）。
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0) 辅助过程：索引不存在才添加（与 media_portability.sql 同名同签名）
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
-- 1) media_root：按 kind 取默认根（扫描器缺省根 + 后端列根）
-- ---------------------------------------------------------------------
CALL qtools_add_index('media_root', 'idx_media_root_kind', '`kind`,`id`', 0);

-- ---------------------------------------------------------------------
-- 2) 视频 / 图片：按根 + 根内相对路径定位
--    只取 rel_path 前 255 字符（与既有 uk_video_rel 前缀长度一致），索引更小
-- ---------------------------------------------------------------------
CALL qtools_add_index('media_video', 'idx_media_video_root_rel', '`root_id`,`rel_path`(255)', 0);
CALL qtools_add_index('media_photo', 'idx_media_photo_root_rel', '`root_id`,`rel_path`(255)', 0);

-- ---------------------------------------------------------------------
-- 3) 收尾：删除辅助过程
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_add_index;

-- ---------------------------------------------------------------------
-- 4) 自检：应看到三个新索引
-- ---------------------------------------------------------------------
SELECT table_name, index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('idx_media_root_kind', 'idx_media_video_root_rel', 'idx_media_photo_root_rel')
 GROUP BY table_name, index_name, non_unique
 ORDER BY table_name, index_name;
