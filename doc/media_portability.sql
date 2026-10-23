-- =====================================================================
-- 媒体库可移植性迁移（P1）：稳定身份 + 多库根预留
--
-- 目标：让媒体库能跨盘、跨机器、跨重扫搬运而不丢用户数据。
-- 做法：给视频/图片加内容哈希（oshash）作为稳定身份，相册改按 dir_key 认，
--       收藏/最近浏览/播放进度改以 content_hash 为准（老 target_id 保留回退）。
--
-- 特性：
--   * 纯追加式变更（ADD COLUMN / ADD INDEX / CREATE TABLE），不删不改既有列；
--   * 可重复执行：每步先查 information_schema，已存在则跳过；
--   * 执行前的库备份：E:\111\backup\qtools-<时间戳>-before-portability.sql
--
-- 适用：MySQL 8.0.19+（scan 脚本本身依赖该版本的行别名语法）
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0) 辅助过程：列不存在才添加
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_add_column;
DROP PROCEDURE IF EXISTS qtools_add_index;
DROP PROCEDURE IF EXISTS qtools_create_root_table;

DELIMITER $$

CREATE PROCEDURE qtools_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(255))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$

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

CREATE PROCEDURE qtools_create_root_table()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'media_root'
  ) THEN
    CREATE TABLE media_root (
      id          INT          NOT NULL AUTO_INCREMENT COMMENT '库根ID',
      name        VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '显示名，如「主库」「备份盘」',
      path        VARCHAR(700) NOT NULL                COMMENT '库根绝对路径（换盘时改这里即可）',
      kind        VARCHAR(16)  NOT NULL DEFAULT 'root'  COMMENT 'root=媒体根 / thumbs=缩略图根 / trash=回收站',
      note        VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '备注',
      created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id),
      UNIQUE KEY uk_media_root_path (path)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '媒体库根目录（多库根预留）';
  END IF;
END$$

DELIMITER ;

-- ---------------------------------------------------------------------
-- 1) 多库根表（先留位，行为仍是单根）
-- ---------------------------------------------------------------------
CALL qtools_create_root_table();

-- ---------------------------------------------------------------------
-- 2) 视频：内容哈希 + 所属库根
-- ---------------------------------------------------------------------
CALL qtools_add_column('media_video', 'content_hash', 'CHAR(16) NULL DEFAULT NULL COMMENT ''内容哈希(oshash)：size+首/中/尾64KB的MD5前16位，改名/移动不变''');
CALL qtools_add_column('media_video', 'root_id',      'INT NULL DEFAULT NULL COMMENT ''所属库根 media_root.id，NULL=默认根''');
CALL qtools_add_index('media_video', 'uk_media_video_hash', '`content_hash`', 1);
CALL qtools_add_index('media_video', 'idx_media_video_root', '`root_id`', 0);

-- ---------------------------------------------------------------------
-- 3) 图片：内容哈希（同一张图可合法出现在多个相册，故只建普通索引）
-- ---------------------------------------------------------------------
CALL qtools_add_column('media_photo', 'content_hash', 'CHAR(16) NULL DEFAULT NULL COMMENT ''内容哈希(oshash)，用于相册内新增图片后不错位''');
CALL qtools_add_column('media_photo', 'root_id',      'INT NULL DEFAULT NULL COMMENT ''所属库根 media_root.id，NULL=默认根''');
CALL qtools_add_index('media_photo', 'idx_media_photo_hash', '`content_hash`', 0);
CALL qtools_add_index('media_photo', 'idx_media_photo_root', '`root_id`', 0);

-- ---------------------------------------------------------------------
-- 4) 相册：按规范化相对目录认身份（避免新增目录导致 id 重排）
-- ---------------------------------------------------------------------
CALL qtools_add_column('media_album', 'dir_key', 'VARCHAR(600) NULL DEFAULT NULL COMMENT ''该册图片所在的目录（不含文件名），空串表示根目录散图册''');
-- 普通索引而不是唯一：一个目录可以有多本相册（实测 156 本册只对应 13 个目录，
-- 其中写真/念念 Nian Nian 一个目录下有 144 本按文件名前缀分册）
CALL qtools_add_index('media_album', 'idx_media_album_dir_key', '`dir_key`', 0);

-- ---------------------------------------------------------------------
-- 5) 收藏 / 最近浏览：以 content_hash 为准（老 target_id 保留回退）
-- ---------------------------------------------------------------------
CALL qtools_add_column('media_favorite', 'content_hash', 'CHAR(16) NULL DEFAULT NULL COMMENT ''目标内容哈希；非空时以此为准，NULL 时回退 target_id''');
CALL qtools_add_index('media_favorite', 'idx_media_favorite_hash', '`content_hash`', 0);

CALL qtools_add_column('media_recent', 'content_hash', 'CHAR(16) NULL DEFAULT NULL COMMENT ''目标内容哈希；播放进度随之绑定，改名不丢''');
CALL qtools_add_index('media_recent', 'idx_media_recent_hash', '`content_hash`', 0);

-- ---------------------------------------------------------------------
-- 6) 收尾：删除辅助过程
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_add_column;
DROP PROCEDURE IF EXISTS qtools_add_index;
DROP PROCEDURE IF EXISTS qtools_create_root_table;

-- ---------------------------------------------------------------------
-- 7) 自检：应看到新增的列与索引
-- ---------------------------------------------------------------------
SELECT table_name, column_name, column_type
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND column_name IN ('content_hash', 'root_id', 'dir_key')
 ORDER BY table_name, ordinal_position;

SELECT table_name, index_name, non_unique, column_name
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND (index_name LIKE '%hash%' OR index_name = 'idx_media_album_dir_key')
 ORDER BY table_name, index_name;
