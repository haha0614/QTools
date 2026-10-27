-- ----------------------------
-- P5.89 · 人物卡背景「站长可持久化」（media_person 三列：缩放 + 两个偏移）
-- 目标库：qtools（MySQL 8.0）
--
-- 执行方式：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/doc/media_person_bg.sql"
--
-- 幂等说明：三处 DDL 都先查 information_schema 再 PREPARE 执行，重复执行 = 无操作。
--
-- 为什么是「列」而不是独立表 media_person_bg(person_id PK, scale, dx, dy, updated_at, updated_by)：
--   1) 读路径在最热的两个接口上：人物墙（一次 1 条 SQL 出整墙）与人物详情。
--      独立表要么每行补一次查询（人物墙 N+1），要么在 personColumns 里再 join/子查询一次；
--      三列都是标量、只存「最后一次调整」，直接放行上最省。
--   2) 写入者是站长一个人（P5.89 起 owner 才可写），没有并发合流或历史版本需求，
--      updated_by 这种审计列在这里没有决策价值（真要审计，media_event 已经有事件表）。
--   3) 与 avatar_kind / avatar_ref 同层：它们也是 media_person 上的「展示态」列，口径一致。
--
-- 为什么 dx / dy 是 DECIMAL 而不是 INT：
--   P5.88 前端存的 dx / dy 是**卡片宽高的比例**（±200%，见 QTools-C/src/views/media/personBgAdjust.js
--   的 OFFSET_LIMIT），卡片是流式 grid（1600 宽 5 列 / 375 宽 1 列），存 px 换个视口就偏了；
--   比例要 4 位小数（0.0001 = 卡片宽度的 0.01%），INT 会把 -0.2 截成 0。
--   scale 同理保留 3 位（前端滑块是整数百分比）。
-- ----------------------------

SET NAMES utf8mb4;

-- ----------------------------
-- 1、bg_scale：缩放倍率（1 = 自动几何，值域 0.5–3）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'media_person' AND COLUMN_NAME = 'bg_scale') = 0,
    'ALTER TABLE media_person ADD COLUMN bg_scale DECIMAL(5,3) NULL COMMENT ''人物卡背景缩放（1=自动几何，0.5-3；P5.89 站长持久化）'' AFTER avatar_ref',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 2、bg_dx：水平偏移（卡片宽度的比例，±2 = ±200%，4 位小数）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'media_person' AND COLUMN_NAME = 'bg_dx') = 0,
    'ALTER TABLE media_person ADD COLUMN bg_dx DECIMAL(6,4) NULL COMMENT ''人物卡背景水平偏移（卡片宽度比例，±2）'' AFTER bg_scale',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 3、bg_dy：垂直偏移（卡片高度的比例，±2，4 位小数）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'media_person' AND COLUMN_NAME = 'bg_dy') = 0,
    'ALTER TABLE media_person ADD COLUMN bg_dy DECIMAL(6,4) NULL COMMENT ''人物卡背景垂直偏移（卡片高度比例，±2）'' AFTER bg_dx',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 4、执行后自检（只读）
-- ----------------------------
SELECT column_name, column_type, is_nullable, column_comment FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'media_person'
    AND column_name IN ('bg_scale', 'bg_dx', 'bg_dy')
  ORDER BY ordinal_position;

-- SELECT COUNT(*) AS adjusted FROM media_person WHERE bg_scale IS NOT NULL OR bg_dx IS NOT NULL OR bg_dy IS NOT NULL;
