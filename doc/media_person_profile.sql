-- =====================================================================
-- 媒体库人物资料字段补全（P5-A）
--
-- 交付内容：media_person 增加 14 个「资料属性」列，全部可空（NULL = 未填写）：
--   measurements VARCHAR(128)   三围 / 尺寸等自由文本
--   career_start YEAR NULL      出道年份（应用侧校验 1900-2100 且 end >= start）
--   career_end   YEAR NULL      退役 / 结束年份
--   tattoos      VARCHAR(255)   纹身
--   piercings    VARCHAR(255)   穿孔 / 饰钉
--   ethnicity    VARCHAR(32)    族裔
--   hair_color   VARCHAR(32)    发色
--   eye_color    VARCHAR(32)    瞳色
--   height_cm    SMALLINT NULL  身高 cm（应用侧校验 100-250）
--   weight_kg    SMALLINT NULL  体重 kg（应用侧校验 30-200）
--   fake_boobs   TINYINT(1) NULL 是否隆胸（NULL = 未标注，避免用 0 冒充「否」）
--   nationality  VARCHAR(64)    国籍
--   twitter      VARCHAR(128)   X / Twitter 账号或链接
--   instagram    VARCHAR(128)   Instagram 账号或链接
--
-- 说明：
--   * 范围 / 长度约束落在应用侧（MediaPersonServiceImpl）：MySQL 8 的 CHECK 跨列比较
--     career_start / career_end 也可以写，但改列时要同时改约束，反而不如集中在一处校验
--   * fake_boobs 用 TINYINT(1) NULL 而不是 NOT NULL DEFAULT 0：三态（是 / 否 / 未标注）
--   * 幂等：所有 DDL 先查 information_schema，已存在则跳过；本文件可反复执行
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_person_profile.sql"
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS qtools_person_profile_add_column;

DELIMITER $$

CREATE PROCEDURE qtools_person_profile_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(500))
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

CALL qtools_person_profile_add_column('media_person', 'measurements',
  'VARCHAR(128) NULL COMMENT ''三围 / 尺寸等自由文本（可空）''');

CALL qtools_person_profile_add_column('media_person', 'career_start',
  'YEAR NULL COMMENT ''出道年份（可空；应用侧校验 1900-2100）''');

CALL qtools_person_profile_add_column('media_person', 'career_end',
  'YEAR NULL COMMENT ''退役 / 结束年份（可空；应用侧校验 1900-2100 且 >= career_start）''');

CALL qtools_person_profile_add_column('media_person', 'tattoos',
  'VARCHAR(255) NULL COMMENT ''纹身（可空）''');

CALL qtools_person_profile_add_column('media_person', 'piercings',
  'VARCHAR(255) NULL COMMENT ''穿孔 / 饰钉（可空）''');

CALL qtools_person_profile_add_column('media_person', 'ethnicity',
  'VARCHAR(32) NULL COMMENT ''族裔（可空）''');

CALL qtools_person_profile_add_column('media_person', 'hair_color',
  'VARCHAR(32) NULL COMMENT ''发色（可空）''');

CALL qtools_person_profile_add_column('media_person', 'eye_color',
  'VARCHAR(32) NULL COMMENT ''瞳色（可空）''');

CALL qtools_person_profile_add_column('media_person', 'height_cm',
  'SMALLINT NULL COMMENT ''身高 cm（可空；应用侧校验 100-250）''');

CALL qtools_person_profile_add_column('media_person', 'weight_kg',
  'SMALLINT NULL COMMENT ''体重 kg（可空；应用侧校验 30-200）''');

CALL qtools_person_profile_add_column('media_person', 'fake_boobs',
  'TINYINT(1) NULL COMMENT ''是否隆胸（NULL = 未标注）''');

CALL qtools_person_profile_add_column('media_person', 'nationality',
  'VARCHAR(64) NULL COMMENT ''国籍（可空）''');

CALL qtools_person_profile_add_column('media_person', 'twitter',
  'VARCHAR(128) NULL COMMENT ''X / Twitter 账号或链接（可空）''');

CALL qtools_person_profile_add_column('media_person', 'instagram',
  'VARCHAR(128) NULL COMMENT ''Instagram 账号或链接（可空）''');

DROP PROCEDURE IF EXISTS qtools_person_profile_add_column;

-- ---------------------------------------------------------------------
-- 自检（只读，可反复执行）
-- ---------------------------------------------------------------------
SELECT column_name, column_type, is_nullable, column_default, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'media_person'
   AND column_name IN ('measurements','career_start','career_end','tattoos','piercings','ethnicity',
                       'hair_color','eye_color','height_cm','weight_kg','fake_boobs','nationality',
                       'twitter','instagram')
 ORDER BY ordinal_position;

SELECT count(*) AS persons FROM media_person;
