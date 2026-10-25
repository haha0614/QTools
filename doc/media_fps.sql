-- =====================================================================
-- 媒体库帧率（fps）列 —— P5.16 帧级 seek / 时间码
--
-- 背景：进度条原本按「整秒」跳转（input[type=range] step=1），2 小时片一个像素
--   ≈ 216 帧，用户根本落不到某一帧；要落到帧栅格 t = round(t*fps)/fps 并显示
--   hh:mm:ss:ff，前端必须先知道这部片的帧率。
--   查证结论：media_video 原本**没有**任何帧率列（vcodec 只有编码名），
--   所以这里补一列，并由 ffprobe 回填（不是前端近似）。
--
-- 列定义：fps DECIMAL(8,3) NULL
--   * 3 位小数是为了 23.976 / 29.97 / 59.94 这类「1001/1000 分数制」帧率；
--     29.970 与真值 30000/1001 的残差 3.3e-8 秒/帧，2 小时片累计 ≈ 7ms，
--     远小于半帧（16.7ms），不会让 snapToFrame 落到相邻帧上。
--   * 回填取的是 ffprobe 的 **r_frame_rate**（容器声明的帧栅格），不是 avg_frame_rate
--     （= 帧数/容器时长，实测同一片 29.969163，22 万帧会偏出 ≈0.24 秒 = 7 帧）。
--   * NULL = 未探测（老库 / 探测失败）。前端遇到 NULL 时**退回现状**：
--     不吸帧、不显示帧号，行为与 P5.15 完全一致 —— 所以本列可以慢慢补。
--
-- 回填（不在本文件里做，SQL 调不了 ffprobe）：
--   node doc/media-import/enrich-meta.js --root=<媒体根> --port=3307 \
--        --ffprobe=<ffprobe> --mysql=<mysql> --secret=<secret.yml>
--   enrich-meta.js 的待补集合已包含 fps IS NULL；补齐后重跑只计 skip（幂等）。
--   176 条实测约 3~5 分钟（每条一次 ffprobe 冷启动）。
--
-- 幂等：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 才有），
--       这里沿用 media_missing.sql 的 information_schema + PREPARE 写法：
--       列已存在时执行 DO 0，反复执行不报错、不改数据。
--
-- 执行：
--     mysql -uroot -P3307 --default-character-set=utf8mb4 qtools -e "source doc/media_fps.sql"
--
-- 适用：MySQL 8.0（只用 5.7 起就有的语法）
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;

-- ---------------------------------------------------------------------
-- 1) media_video.fps
-- ---------------------------------------------------------------------
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_video' AND column_name = 'fps') = 0,
  'ALTER TABLE media_video ADD COLUMN fps DECIMAL(8,3) NULL DEFAULT NULL COMMENT ''平均帧率（帧/秒，P5.16 帧级 seek 用；NULL = 未探测）'' AFTER vcodec',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2) 自检（只读，可反复执行）
--    col：应看到 1 行 fps decimal(8,3) YES NULL
--    totals：pending = 尚未回填帧率（0 表示全部回填完毕）
--    dist：按帧率分桶，一眼看出 23.976/25/29.97/30/50/59.94/60 各多少部
-- ---------------------------------------------------------------------
SELECT column_name, column_type, is_nullable, column_default, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'media_video'
   AND column_name = 'fps';

SELECT COUNT(*) AS videos, SUM(fps IS NULL) AS pending FROM media_video;

SELECT fps, COUNT(*) AS videos
  FROM media_video
 GROUP BY fps
 ORDER BY fps IS NULL, fps;

SELECT COUNT(*) AS out_of_range
  FROM media_video
 WHERE fps IS NOT NULL AND (fps <= 0 OR fps > 240);
