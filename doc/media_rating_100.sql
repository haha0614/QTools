-- =====================================================================
-- 媒体库评分值域 0-10 → 0-100（P5-A）
--
-- 交付内容：
--   media_video.rating  TINYINT NULL（0-10）  →  SMALLINT NULL（0-100）
--   并把存量 0-10 的分数 ×10（8 → 80），语义仍是「NULL = 未评分，0 是有效最低分」。
--
-- 幂等保护（关键）：
--   只有「列类型仍然是 tinyint」时才执行 ×10 换算。换算与扩容在同一步里完成，
--   跑完列类型变成 smallint，第二遍直接跳过 IF 分支 —— 不会把 80 再乘成 800。
--   因此本文件可以反复执行（部署脚本重跑 / 手工重跑都安全）。
--
-- 兼容性：
--   * 列名与接口字段名都不变（rating），只是值域扩大：老前端按 0-10 渲染会显示 80，
--     因此前端必须同步改成 0-100（见 doc/自定义与迁移指南.md「评分 0-100」一节）
--   * 只放宽类型、不建索引：rating 目前只用于详情 / 索引输出，没有范围查询
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_rating_100.sql"
--   （必须带 --default-character-set=utf8mb4，否则中文注释会按 GBK 写进数据字典）
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS qtools_rating_to_100;

DELIMITER $$

CREATE PROCEDURE qtools_rating_to_100()
BEGIN
  DECLARE v_type VARCHAR(64) DEFAULT NULL;

  SELECT column_type INTO v_type
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'media_video'
     AND column_name = 'rating'
   LIMIT 1;

  IF v_type IS NULL THEN
    -- 极老库连 rating 列都没有：直接按新语义建列（0-100），无需换算
    ALTER TABLE media_video
      ADD COLUMN rating SMALLINT NULL DEFAULT NULL COMMENT '评分 0-100（NULL = 未评分）';
  ELSEIF v_type LIKE 'tinyint%' THEN
    -- 老语义（0-10）：先扩容再 ×10，两步在同一 CALL 内完成
    ALTER TABLE media_video
      MODIFY COLUMN rating SMALLINT NULL DEFAULT NULL COMMENT '评分 0-100（NULL = 未评分）';
    -- 0 分是有效最低分，×10 仍是 0；只在 0-10 范围内的值参与换算（防御性上限）
    UPDATE media_video
       SET rating = rating * 10
     WHERE rating IS NOT NULL
       AND rating BETWEEN 0 AND 10;
  ELSE
    -- 已经是 smallint（或更宽）：迁移做过了，跳过换算
    SET @qtools_rating_skip = 1;
  END IF;
END$$

DELIMITER ;

CALL qtools_rating_to_100();

DROP PROCEDURE IF EXISTS qtools_rating_to_100;

-- ---------------------------------------------------------------------
-- 自检（只读，可反复执行）
-- ---------------------------------------------------------------------
SELECT column_name, column_type, is_nullable, column_default, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'media_video'
   AND column_name = 'rating';

SELECT COALESCE(rating, -1) AS rating_or_null, count(*) AS videos
  FROM media_video
 GROUP BY rating
 ORDER BY rating IS NULL, rating;

SELECT count(*) AS out_of_range
  FROM media_video
 WHERE rating IS NOT NULL AND (rating < 0 OR rating > 100);
