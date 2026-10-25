-- =====================================================================
-- 媒体库标记起止时间迁移（P4-A）
--
-- 目标：media_video_marker 支持「区间标记」——在既有单点标记 pos 之外，
--       增加可空的 end_pos（结束时间，秒，2 位小数）。
--
-- 语义（与 Java 侧 MediaOrganizeServiceImpl 严格一致，DB 不加 CHECK）：
--   * end_pos IS NULL  = 单点标记（老数据全部是这种，行为完全不变）；
--   * end_pos IS NOT NULL = 区间标记，必须满足 end_pos > pos；
--   * 两者都受 media_video.dur 约束（dur 为 NULL 时跳过上限，与 pos 既有规则一致）。
--
-- 为什么不在 DB 上加 CHECK 约束：
--   兼容老库 / 老 MySQL 部署（部分环境 8.0.16 以下 CHECK 被静默忽略，行为不一致），
--   且 dur 上限本来只能在应用侧判断，DB 侧只能做一半校验，反而造成「以为有约束」。
--   因此约束统一由服务层校验：非法一律 400，绝不落库。
--
-- 特性：
--   * 纯追加式变更（ADD COLUMN），不删不改任何既有列、不动数据、不改索引；
--   * 可重复执行：先查 information_schema，列已存在则跳过。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_marker_end.sql"
--   校验：
--     select column_name, column_type, is_nullable from information_schema.columns
--      where table_schema = DATABASE() and table_name = 'media_video_marker'
--      order by ordinal_position;
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0) 辅助过程：列不存在才添加（与 media_portability.sql 同一套写法）
--    只在本文件内建、用完即删，不复用别处过程名，避免互相干扰。
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS media_marker_add_column;

DELIMITER $$

CREATE PROCEDURE media_marker_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(255))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

-- ---------------------------------------------------------------------
-- 1) media_video_marker.end_pos —— 区间标记的结束时间（秒）
--    索引不动：既有 idx_marker_video_pos(video_id, pos) 已覆盖「按视频取标记」，
--    end_pos 只用于展示与区间渲染，不参与筛选。
-- ---------------------------------------------------------------------
CALL media_marker_add_column('media_video_marker', 'end_pos',
  'DECIMAL(10,2) NULL DEFAULT NULL COMMENT ''结束时间（秒，保留 2 位）；NULL = 单点标记，非 NULL 时必须 > pos''');

DROP PROCEDURE IF EXISTS media_marker_add_column;

-- ---------------------------------------------------------------------
-- 2) 自检（只读）：应看到 id / video_id / pos / label / color / create_time / end_pos
-- ---------------------------------------------------------------------
SELECT column_name, column_type, is_nullable, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'media_video_marker'
 ORDER BY ordinal_position;
