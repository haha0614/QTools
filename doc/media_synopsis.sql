-- =====================================================================
-- 媒体库「作品简介 / 剧情梗概」列 —— P5.38
--
-- 需求（用户原话）：「信息栏补作品简介，剧情梗概」——
--   视频需要一个**长文本**描述字段，在播放页信息栏里可查看 / 可编辑。
--
-- 列定义：synopsis TEXT NULL
--   * 为什么是 TEXT 而不是 VARCHAR(2000)：接口上限是 8000 **字符**，UTF-8 下中文
--     一个字 3 字节 = 24000 字节，VARCHAR(N) 在 MySQL 里 N 是**字符数**、本身够用，
--     但一条 8000 字的梗概已经接近 VARCHAR 行内存储的红线（行内太长会退化到 off-page），
--     语义上它本来就是「正文」而不是「短标签」；TEXT（上限 65535 字节 ≈ 21800 个汉字）
--     既装得下上限、又不会让 media_video 的行内长度继续膨胀。
--   * NULL = 没有简介（绝大多数存量行）。空串不落库：服务层把纯空白一律归一化成 NULL，
--     所以「有事/没事」只有一种表示。
--   * 换行原样保留（前端 white-space: pre-wrap 渲染）；只 trim 首尾空白。
--   * 与 media_video.rating / fps 一样是**人工属性**：扫描脚本从不写这一列，
--     重建索引不会冲掉用户写的简介。
--
-- 幂等：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 才有），
--       沿用 media_fps.sql / media_missing.sql 的 information_schema + PREPARE 写法：
--       列已存在时执行 DO 0，反复执行不报错、不改数据。
--
-- 执行：
--     mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_synopsis.sql"
--
-- 适用：MySQL 8.0（只用 5.7 起就有的语法）
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;

-- ---------------------------------------------------------------------
-- 1) media_video.synopsis
-- ---------------------------------------------------------------------
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_video' AND column_name = 'synopsis') = 0,
  'ALTER TABLE media_video ADD COLUMN synopsis TEXT NULL DEFAULT NULL COMMENT ''作品简介/剧情梗概（P5.38 站端信息栏可编辑；≤8000 字符，保留换行，NULL = 无）''',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2) 自检（只读，可反复执行）
--    col：应看到 1 行 synopsis text YES NULL
--    totals：filled = 已写简介的片数（迁移刚跑完一定是 0）
--    longest：最长简介的字符数（超 8000 说明有人绕过接口直接写库）
-- ---------------------------------------------------------------------
SELECT column_name, column_type, is_nullable, column_default, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'media_video'
   AND column_name = 'synopsis';

SELECT COUNT(*) AS videos, SUM(synopsis IS NOT NULL AND synopsis <> '') AS filled FROM media_video;

SELECT MAX(CHAR_LENGTH(synopsis)) AS longest_chars
  FROM media_video
 WHERE synopsis IS NOT NULL;
